package com.smartcast.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * 投屏前台服务:MediaProjection 抓屏 → MediaCodec 编 H.264 → TCP 推流到黑板。
 *
 * Step2 增强:
 *   - 分辨率自适应:屏幕超过 1080p 时降到 1080p(16:9 等比缩放,带宽延迟友好)
 *   - 首帧快速出画面:连接后立即发送一个关键帧(I帧),避免开场黑屏/花屏
 *   - 断线自动重连:网络抖动断了不用学生手动重点,后台重连
 */
class ScreenCastService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        // 推流目标地址(由 MainActivity 通过 EXTRA 传入)
        @Volatile var targetHost: String = "192.168.1.100"
        @Volatile var targetPort: Int = 8888

        // 状态回调(给界面显示用)
        @Volatile var statusText: String = "未连接"
        @Volatile var onStatusChange: ((String) -> Unit)? = null

        private const val TAG = "ScreenCastService"
        private const val CHANNEL_ID = "smartcast_channel"
        private const val NOTIFICATION_ID = 1

        // 目标编码分辨率上限:1080p。高于此分辨率的屏幕等比缩小。
        private const val MAX_WIDTH = 1920
        private const val MAX_HEIGHT = 1080
        // 重连参数
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT = 5
    }

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var socket: Socket? = null
    private var streamThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var connected = false

    // 当前编码分辨率(等比缩放后的)
    private var encodeWidth = 0
    private var encodeHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        intent?.getStringExtra(EXTRA_HOST)?.let { targetHost = it }
        intent?.getIntExtra(EXTRA_PORT, 0)?.let { if (it != 0) targetPort = it }

        startForeground()
        if (data != null) {
            startCasting(resultCode, data)
        }
        return START_NOT_STICKY
    }

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "投屏服务", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("智慧投屏")
            .setContentText("正在投屏中…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCasting(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projectionManager.getMediaProjection(resultCode, data)

        // 取屏幕真实分辨率,做等比缩放到 ≤1080p
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val (w, h) = scaleDown(metrics.widthPixels, metrics.heightPixels)
        encodeWidth = w
        encodeHeight = h
        Log.i(TAG, "屏幕 ${metrics.widthPixels}x${metrics.heightPixels} → 编码 ${w}x${h},目标 $targetHost:$targetPort")

        // 配置 H.264 编码器
        val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }

        encoder = MediaCodec.createEncoderByType("video/avc").apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = encoder!!.createInputSurface()

        // MediaProjection 把屏幕画面投到编码器的 inputSurface(用缩放后的分辨率)
        virtualDisplay = projection!!.createVirtualDisplay(
            "smartcast", w, h, metrics.densityDpi,
            0, inputSurface, null, null
        )

        running = true
        updateStatus("正在连接 $targetHost…")
        streamThread = thread(name = "smartcast-stream") {
            streamLoopWithReconnect()
        }
    }

    /**
     * 带重连的推流主循环。
     * 网络断开后,自动重建 socket 重推,最多重试 MAX_RECONNECT 次。
     * 抓屏/编码不停,只重连传输通道,体验上学生无感。
     */
    private fun streamLoopWithReconnect() {
        var retryCount = 0
        while (running) {
            var out: OutputStream? = null
            try {
                updateStatus("连接中…")
                socket = Socket()
                socket!!.soTimeout = 5000
                socket!!.connect(java.net.InetSocketAddress(targetHost, targetPort), 3000)
                out = socket!!.getOutputStream()
                connected = true
                retryCount = 0
                updateStatus("已连接,投屏中")

                // 先发分辨率头
                val header = ByteBuffer.allocate(8).putInt(encodeWidth).putInt(encodeHeight).array()
                out.write(header)
                out.flush()

                val codec = encoder!!
                if (retryCount == 0) codec.start()

                // 首帧请求:通过 PARAMETER_KEY_REQUEST_SYNC_FLAG 让下一帧为关键帧
                // (仅在重连后或首帧时触发,确保黑板端尽快解码出画面)
                requestKeyFrame()

                val bufferInfo = MediaCodec.BufferInfo()
                Log.i(TAG, "开始推流 → $targetHost:$targetPort")

                while (running) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex >= 0 -> {
                            val buf: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                            val data = ByteArray(bufferInfo.size)
                            buf.position(bufferInfo.offset)
                            buf.get(data, 0, bufferInfo.size)
                            out.write(data, 0, bufferInfo.size)
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            Log.i(TAG, "编码器输出格式已就绪")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "推流异常: ${e.message}")
                connected = false
                if (running) {
                    retryCount++
                    if (retryCount > MAX_RECONNECT) {
                        updateStatus("重连失败,请检查网络后重试")
                        Log.e(TAG, "超过最大重连次数 $MAX_RECONNECT,停止")
                        break
                    }
                    updateStatus("断线,${RECONNECT_DELAY_MS / 1000}秒后重试($retryCount/$MAX_RECONNECT)…")
                    try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: Exception) {}
                }
            } finally {
                connected = false
                try { out?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        stopSelf()
    }

    /** 请求编码器下一帧为关键帧(I帧),用于首帧快速出画面和重连后恢复 */
    @android.annotation.SuppressLint("NewApi")
    private fun requestKeyFrame() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val params = android.os.Bundle()
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                encoder?.setParameters(params)
                Log.i(TAG, "已请求关键帧")
            } catch (e: Exception) {
                Log.w(TAG, "请求关键帧失败: ${e.message}")
            }
        }
    }

    /** 等比缩小到 ≤1080p。保证宽高都是偶数(H.264 要求) */
    private fun scaleDown(w: Int, h: Int): Pair<Int, Int> {
        if (w <= MAX_WIDTH && h <= MAX_HEIGHT) return Pair(w, h)
        val scale = minOf(MAX_WIDTH.toFloat() / w, MAX_HEIGHT.toFloat() / h)
        var nw = (w * scale).toInt()
        var nh = (h * scale).toInt()
        // 保证偶数
        if (nw % 2 != 0) nw -= 1
        if (nh % 2 != 0) nh -= 1
        return Pair(nw, nh)
    }

    private fun updateStatus(text: String) {
        statusText = text
        Log.i(TAG, "状态:$text")
        onStatusChange?.let { it(text) }
    }

    override fun onDestroy() {
        running = false
        try { streamThread?.join(500) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        socket?.close()
        updateStatus("已停止")
        super.onDestroy()
    }
}
