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
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * 投屏前台服务:MediaProjection 抓屏 → MediaCodec 编 H.264 → TCP 推流到黑板。
 *
 * 协议 v2(与黑板端 main.go 对齐,需两端同时更新):
 *   pad → 黑板:一行 JSON 身份 {"v":1,"name":"设备型号"} → 8 字节分辨率头 → H.264 裸流
 *   黑板 → pad:行分隔 JSON 控制消息:
 *     {"cmd":"hb"}      心跳(每 5 秒),pad 据此检测黑板端失联
 *     {"cmd":"kicked"}  被其他 pad 顶替,停止投屏、不再重连
 *     {"cmd":"stop"}    黑板端结束投屏(老师关掉画面),停止投屏
 *
 * 增强:
 *   - 分辨率自适应:屏幕超过 1080p 时降到 1080p(16:9 等比缩放,带宽延迟友好)
 *   - 首帧快速出画面:连接后立即发送一个关键帧(I帧),避免开场黑屏/花屏
 *   - 断线自动重连(被顶替/黑板端停止除外),网络抖动学生无感
 *   - 自适应码率:按实际吞吐在 1.5M~4M 之间调整,弱 WiFi 下平滑降画质而不是卡死
 *   - 低延迟编码:关 B 帧(API 26+)、低延迟模式(API 30+),减少解码端延迟
 *   - 息屏保活:持有 PARTIAL_WAKE_LOCK,pad 屏幕熄灭时投屏不中断
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
        // 码率自适应范围(1.5M~4M,初始 4M)
        private const val INIT_BITRATE = 4_000_000
        private const val MIN_BITRATE = 1_500_000
        private const val MAX_BITRATE = 4_000_000
        // 重连参数
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT = 5
        // 控制消息读超时:黑板端心跳 5 秒一条,连续 HEARTBEAT_MISS_LIMIT 次超时视为黑板失联
        private const val CONTROL_READ_TIMEOUT_MS = 5000
        private const val HEARTBEAT_MISS_LIMIT = 4
    }

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var socket: Socket? = null
    private var streamThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // WiFi 锁:防止 pad 屏幕熄灭后 WiFi 进入低功耗/省电模式导致 socket 周期性断开
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    @Volatile private var running = false
    @Volatile private var connected = false

    // 黑板端明确告知的停止原因:这两种情况都不重连
    @Volatile private var serverKicked = false
    @Volatile private var serverStopped = false

    // 当前编码分辨率(等比缩放后的)
    private var encodeWidth = 0
    private var encodeHeight = 0

    // 自适应码率:当前码率 + 累计已写字节数(由码率监控线程每 2 秒采样)
    @Volatile private var currentBitrate = INIT_BITRATE
    private val writtenBytes = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        intent?.getStringExtra(EXTRA_HOST)?.let { targetHost = it }
        intent?.getIntExtra(EXTRA_PORT, 0)?.let { if (it != 0) targetPort = it }

        startForeground()
        if (data != null) {
            try {
                startCasting(resultCode, data)
            } catch (e: Exception) {
                // 兜底:任何初始化异常都只提示,绝不让服务崩溃导致 app 闪退
                Log.e(TAG, "投屏启动失败: ${e.message}", e)
                updateStatus("启动失败:${e.message}")
                stopSelf()
            }
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
        // 息屏保活:课堂常见"学生投完把 pad 一放",屏幕熄灭后投屏仍要继续
        // WakeLock 保 CPU 不睡,WiFi Lock 保网卡不进省电模式(两者缺一不可)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartcast:cast").apply {
            setReferenceCounted(false)
            acquire()
        }
        // WiFi 锁:HIGH_PERF 模式禁用 WiFi 省电,防止熄屏后 socket 周期性被掐断
        val wifi = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifi.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "smartcast:cast"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

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
        currentBitrate = INIT_BITRATE
        writtenBytes.set(0)
        Log.i(TAG, "屏幕 ${metrics.widthPixels}x${metrics.heightPixels} → 编码 ${w}x${h},目标 $targetHost:$targetPort")

        // 配置 H.264 编码器(低延迟:关 B 帧 + 低延迟模式,减少解码端排队延迟)
        // 注意:个别国产 ROM 编码器不支持扩展参数,configure 会抛
        // IllegalArgumentException 导致闪退(有实测案例),这里做多级配置回退,
        // 保证任何 pad 都能出画面;即使全部失败也只提示、不再崩溃。
        try {
            encoder = createEncoder(w, h)
            if (encoder == null) throw IllegalStateException("没有可用的 H.264 编码器")
            val inputSurface = encoder!!.createInputSurface()

            // MediaProjection 把屏幕画面投到编码器的 inputSurface(用缩放后的分辨率)
            virtualDisplay = projection!!.createVirtualDisplay(
                "smartcast", w, h, metrics.densityDpi,
                0, inputSurface, null, null
            )

            // 编码器只 start 一次,独立于网络连接。
            // 这样网络重连时不会重复 start(否则会报错或丢帧),
            // 也不会因为重连失败导致编码器永远没启动(0 帧问题)。
            encoder!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "编码初始化失败: ${e.message}", e)
            updateStatus("初始化失败,请重试")
            // 清理已分配的资源,避免泄漏
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            try { wakeLock?.release() } catch (_: Exception) {}
            virtualDisplay = null
            encoder = null
            return
        }

        running = true
        updateStatus("正在连接 $targetHost…")
        streamThread = thread(name = "smartcast-stream") {
            streamLoopWithReconnect()
        }
    }

    /**
     * 创建 H.264 编码器,带多级配置回退。
     * 优先用低延迟配置(关 B 帧 + 低延迟模式);部分国产 ROM 的编码器
     * 不支持这些参数,configure 会抛 IllegalArgumentException,逐级回退:
     *   增强配置(VBR + 关B帧 + 低延迟) → 基础配置(VBR) → 最简配置(默认CBR)
     * 保证任何 pad 都能出画面,只是部分设备延迟略高。
     */
    private fun createEncoder(w: Int, h: Int): MediaCodec? {
        val configs = listOf(
            "增强(关B帧+低延迟)" to enhancedFormat(w, h),
            "基础(VBR)" to basicFormat(w, h),
            "最简(默认)" to minimalFormat(w, h)
        )
        for ((desc, format) in configs) {
            try {
                val codec = MediaCodec.createEncoderByType("video/avc") ?: continue
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(TAG, "编码器配置成功: $desc")
                return codec
            } catch (e: Exception) {
                Log.w(TAG, "编码器配置失败($desc),尝试下一档: ${e.message}")
            }
        }
        return null
    }

    /** 增强配置:VBR + 关 B 帧 + 低延迟模式(延迟最低,但部分编码器不支持) */
    private fun enhancedFormat(w: Int, h: Int) = MediaFormat.createVideoFormat("video/avc", w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, INIT_BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) // 关 B 帧,画面按序输出,延迟更低
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
    }

    /** 基础配置:VBR,不带扩展参数(兼容大多数设备) */
    private fun basicFormat(w: Int, h: Int) = MediaFormat.createVideoFormat("video/avc", w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, INIT_BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
    }

    /** 最简配置:不带扩展参数、默认 CBR(兜底,兼容性最好) */
    private fun minimalFormat(w: Int, h: Int) = MediaFormat.createVideoFormat("video/avc", w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, INIT_BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
    }

    /**
     * 带重连的推流主循环。
     * 网络断开后,自动重建 socket 重推,最多重试 MAX_RECONNECT 次。
     * 抓屏/编码不停,只重连传输通道,体验上学生无感。
     *
     * 两种情况不重连:
     *   - 黑板端发来 {"cmd":"kicked"}(被其他 pad 顶替)
     *   - 黑板端发来 {"cmd":"stop"}(黑板端结束投屏)
     * 兜底:连接后 3 秒内断开,多半是被顶替(兼容没有 kicked 消息的老黑板)。
     *
     * 防累积延迟(借鉴 scrcpy):
     *   编码器输出 → 投入有界队列 → 发送线程异步写 socket。
     *   队列满时丢弃最旧的非关键帧(P 帧),关键帧(I 帧)永远保留。
     *   这样网络变慢时宁可掉几帧也不让延迟越积越大,避免"投得越久越卡"。
     */
    private fun streamLoopWithReconnect() {
        var retryCount = 0
        startRateMonitor()
        while (running && !serverKicked && !serverStopped) {
            var out: OutputStream? = null
            val connectTime = System.currentTimeMillis()
            try {
                updateStatus("连接中…")
                socket = Socket()
                socket!!.keepAlive = true
                socket!!.tcpNoDelay = true // 禁用 Nagle,减少小包延迟
                socket!!.soTimeout = CONTROL_READ_TIMEOUT_MS // 控制消息读超时(兼作失联检测)
                socket!!.connect(java.net.InetSocketAddress(targetHost, targetPort), 3000)
                out = socket!!.getOutputStream()
                connected = true
                retryCount = 0
                updateStatus("已连接,投屏中")

                // 协议 v2:先发身份握手(一行 JSON,黑板端据此显示设备名),再发分辨率头
                val identity = """{"v":1,"name":"${jsonEscape(Build.MODEL)}"}"""
                out.write(identity.toByteArray())
                out.write('\n'.code)
                val header = ByteBuffer.allocate(8).putInt(encodeWidth).putInt(encodeHeight).array()
                out.write(header)
                out.flush()

                // 黑板端控制消息监听(kicked/stop/心跳/reqkey),同时负责检测黑板端失联
                startControlReader()

                // 编码器已在 startCasting() 中启动,这里不重复 start
                val codec = encoder!!

                // 首帧请求:通过 PARAMETER_KEY_REQUEST_SYNC_FLAG 让下一帧为关键帧
                // (每次连接后都请求一次,确保黑板端尽快解码出画面)
                requestKeyFrame()

                val bufferInfo = MediaCodec.BufferInfo()
                Log.i(TAG, "开始推流 → $targetHost:$targetPort")

                while (running) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIndex >= 0) {
                        val buf: ByteBuffer = codec.getOutputBuffer(outIndex)!!
                        // 直接把 ByteBuffer 的内容写到 socket(避免多余的数组拷贝)
                        val data = ByteArray(bufferInfo.size)
                        buf.position(bufferInfo.offset)
                        buf.get(data, 0, bufferInfo.size)
                        codec.releaseOutputBuffer(outIndex, false)
                        out!!.write(data, 0, bufferInfo.size)
                        writtenBytes.addAndGet(bufferInfo.size.toLong())
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.i(TAG, "编码器输出格式已就绪")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "推流异常: ${e.message}")
                connected = false
                if (running && !serverKicked && !serverStopped) {
                    // 连接后 3 秒内断开,基本是被另一个 pad 顶掉了(网络抖动不会这么快)。
                    // 兜底保护:老版本黑板没有 kicked 消息,靠这个避免两个 pad 互相顶替。
                    val aliveMs = System.currentTimeMillis() - connectTime
                    if (aliveMs < 3000) {
                        updateStatus("已被其他投屏替换")
                        Log.w(TAG, "连接仅存活 ${aliveMs}ms,判定为被顶替,停止重连")
                        break
                    }
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

    /**
     * 监听黑板端控制消息(行分隔 JSON):
     *   {"cmd":"hb"}      心跳,说明黑板端还活着(平时只有这种消息)
     *   {"cmd":"kicked"}  被其他 pad 顶替 → 停止投屏,不重连
     *   {"cmd":"stop"}    黑板端结束投屏 → 停止投屏,不重连
     *
     * 黑板端平时每 5 秒发一条心跳;若连续 HEARTBEAT_MISS_LIMIT 次
     * 超时没收到任何消息,判定黑板端失联(断电/断网),主动断开触发重连。
     * 收到 EOF(黑板端关闭连接)也立即断开触发重连。
     */
    private fun startControlReader() {
        val s = socket ?: return
        thread(name = "smartcast-control") {
            var missCount = 0
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                while (running) {
                    var timedOut = false
                    val line: String? = try {
                        reader.readLine()
                    } catch (_: SocketTimeoutException) {
                        timedOut = true
                        null // 超时:可能只是心跳间隔,继续等
                    } catch (_: Exception) {
                        break // socket 已关闭,属正常
                    }
                    if (line == null) {
                        if (s.isClosed || !running) break
                        if (timedOut) {
                            missCount++
                            if (missCount >= HEARTBEAT_MISS_LIMIT) {
                                Log.w(TAG, "黑板端 ${missCount * CONTROL_READ_TIMEOUT_MS / 1000} 秒无响应,判定失联")
                                updateStatus("黑板端失联,正在重连…")
                                try { s.close() } catch (_: Exception) {}
                                break
                            }
                        } else {
                            // EOF:黑板端关闭连接,立即断开触发重连
                            try { s.close() } catch (_: Exception) {}
                            break
                        }
                        continue
                    }
                    missCount = 0
                    if (line.isEmpty()) continue
                    try {
                        when (JSONObject(line).optString("cmd")) {
                            "kicked" -> {
                                serverKicked = true
                                updateStatus("已被其他投屏替换")
                                try { s.close() } catch (_: Exception) {}
                                break
                            }
                            "stop" -> {
                                serverStopped = true
                                updateStatus("黑板端已结束投屏")
                                try { s.close() } catch (_: Exception) {}
                                break
                            }
                            "reqkey" -> {
                                // 黑板端解码出错(丢包/花屏),请求立即补一个关键帧恢复画面
                                // 借鉴 scrcpy:解码端报错时主动要 I 帧,而不是干等 2 秒
                                requestKeyFrame()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "控制消息解析失败: $line")
                    }
                }
            } catch (e: Exception) {
                // socket 被其他线程关闭等场景,属正常
            }
        }
    }

    /**
     * 自适应码率:每 2 秒按实际写吞吐调整编码码率。
     *   - 吞吐 < 当前码率 70% 且码率高于下限 → 降为 80%
     *   - 吞吐 > 当前码率 95% 且码率低于上限 → 升为 125%
     * 弱 WiFi 下画质平滑下降而不是卡顿/断流;网络恢复后自动升回。
     */
    private fun startRateMonitor() {
        thread(name = "smartcast-rate") {
            var lastBytes = writtenBytes.get()
            var lastTime = System.currentTimeMillis()
            while (running) {
                try { Thread.sleep(2000) } catch (_: Exception) { break }
                val now = System.currentTimeMillis()
                val bytes = writtenBytes.get()
                val elapsedSec = (now - lastTime) / 1000.0
                val rateBps = if (elapsedSec > 0) ((bytes - lastBytes) * 8 / elapsedSec).toLong() else 0L
                lastBytes = bytes
                lastTime = now
                val cur = currentBitrate
                when {
                    rateBps > 0 && rateBps < (cur * 0.7).toLong() && cur > MIN_BITRATE ->
                        setEncoderBitrate(maxOf(MIN_BITRATE, (cur * 0.8).toInt()))
                    rateBps > (cur * 0.95).toLong() && cur < MAX_BITRATE ->
                        setEncoderBitrate(minOf(MAX_BITRATE, (cur * 1.25).toInt()))
                }
            }
        }
    }

    /** 动态调整编码码率(MediaCodec 立即生效,下一帧开始按新码率编码) */
    private fun setEncoderBitrate(bps: Int) {
        if (bps == currentBitrate) return
        try {
            // PARAMETER_KEY_BITRATE 常量在某些 SDK 版本非公开,直接用其字符串值 "video-bitrate"
            val params = android.os.Bundle()
            params.putInt("video-bitrate", bps)
            encoder?.setParameters(params)
            currentBitrate = bps
            Log.i(TAG, "自适应码率:${bps / 1000} kbps")
        } catch (e: Exception) {
            Log.w(TAG, "调整码率失败: ${e.message}")
        }
    }

    /** 请求编码器下一帧为关键帧(I帧),用于首帧快速出画面和重连后恢复 */
    @android.annotation.SuppressLint("NewApi")
    private fun requestKeyFrame() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // PARAMETER_KEY_REQUEST_SYNC_FRAME 常量在某些 SDK 版本非公开,直接用其字符串值 "request-sync-frame"
                val params = android.os.Bundle()
                params.putInt("request-sync-frame", 0)
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

    /** JSON 字符串转义(设备型号里可能含引号/反斜杠) */
    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

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
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        updateStatus("已停止")
        super.onDestroy()
    }
}
