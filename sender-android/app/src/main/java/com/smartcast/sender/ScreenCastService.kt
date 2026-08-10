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
 * 这一段是整个方案的核心。编码参数参考 scrcpy 默认值,针对局域网做了简化。
 *
 * Step1 说明:
 *   - 目标 IP/端口写死从 MainActivity 静态变量传入,先用通的最简链路。
 *   - Step2 起把目标地址改成 mDNS 自动发现的黑板地址。
 *   - Step3 起加身份握手(教室号/pad 号)、自动重连。
 */
class ScreenCastService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        // Step1 临时:目标地址由界面写入。Step2 换 mDNS。
        @Volatile var targetHost: String = "192.168.1.100"
        @Volatile var targetPort: Int = 8888

        private const val TAG = "ScreenCastService"
        private const val CHANNEL_ID = "smartcast_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var socket: Socket? = null
    private var castThread: Thread? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        startForeground()
        if (data != null) {
            startCasting(resultCode, data)
        }
        return START_NOT_STICKY
    }

    /** Android 10+ 必须用前台服务(mediaProjection 类型)才能抓屏 */
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

        // 取屏幕真实分辨率
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        Log.i(TAG, "屏幕分辨率 ${width}x${height},目标 $targetHost:$targetPort")

        // 配置 H.264 编码器(参数参考 scrcpy,均衡画质与延迟)
        val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)        // 4Mbps,1080p 够用
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)             // 30fps
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)        // 关键帧间隔 2 秒
        }

        encoder = MediaCodec.createEncoderByType("video/avc").apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = encoder!!.createInputSurface()

        // MediaProjection 把屏幕画面投到编码器的 inputSurface
        projection!!.createVirtualDisplay(
            "smartcast", width, height, metrics.densityDpi,
            0, inputSurface, null, null
        )

        running = true
        castThread = thread(name = "smartcast-stream") {
            streamLoop(width, height)
        }
    }

    /**
     * 推流主循环:从编码器读出 H.264 帧 → 写到 TCP socket。
     *
     * 协议约定(Step1 极简版):
     *   连接建立后,先发 8 字节 header = [width(4, big-endian)] [height(4, big-endian)]
     *   随后源源不断地发 H.264 裸流。黑板端按 H.264 解析。
     */
    private fun streamLoop(width: Int, height: Int) {
        var out: OutputStream? = null
        try {
            socket = Socket(targetHost, targetPort)
            out = socket!!.getOutputStream()

            // 1. 先发分辨率头,方便黑板端初始化解码器
            val header = ByteBuffer.allocate(8).putInt(width).putInt(height).array()
            out.write(header)
            out.flush()

            val codec = encoder!!
            codec.start()

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
            Log.e(TAG, "推流异常: ${e.message}", e)
        } finally {
            running = false
            try { out?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        try { castThread?.join(500) } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        socket?.close()
        super.onDestroy()
    }
}
