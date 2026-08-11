package com.smartcast.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.URI
import java.util.ArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebRTC 投屏客户端。
 *
 * 阶段2要点:
 *  - kicked/断线立即停轨,禁止被顶后自动重连抢回来
 *  - 信令心跳 + 重连退避(不在 WS 线程 sleep)
 *  - ICE 候选队列(answer 未到先缓存)
 *  - H264 优先 + 码率/帧率约束
 *  - 发送端周期性 stats
 */
class RtcClient(
    private val context: Context,
    private val mediaProjectionPermissionData: Intent,
    private val serverHost: String,
    private val room: String,
    /** 可选;空字符串=不带 token(默认,一键连接) */
    private val token: String = "",
    /**
     * 画质档位:
     *  "720"  → 上限 1280x720
     *  "1080" → 上限 1920x1080(默认,更清晰)
     *  "native" → 尽量原分辨率(可能超硬编能力,风险高)
     */
    private val quality: String = "1080",
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RtcClient"
        private const val STREAM_ID = "smartcast-stream"
        private const val VIDEO_TRACK_ID = "smartcast-video"
        private const val FPS = 24
        private const val PREFERRED_CODEC = "H264"
        private const val HEARTBEAT_MS = 10_000L
        private const val STATS_MS = 2_000L
        /** 投屏关键帧间隔:花屏恢复 + 中途接入更快出清画 */
        private const val KEYFRAME_INTERVAL_MS = 3_000L
    }

    // 按画质档位设码率(局域网投屏可适当高)
    private val minBitrateBps: Int
    private val maxBitrateBps: Int
    private val startBitrateBps: Int
    private val maxCaptureW: Int
    private val maxCaptureH: Int

    init {
        when (quality.lowercase()) {
            "720", "720p" -> {
                maxCaptureW = 1280; maxCaptureH = 720
                minBitrateBps = 800_000; maxBitrateBps = 3_000_000; startBitrateBps = 2_200_000
            }
            "native", "raw", "full" -> {
                maxCaptureW = 2560; maxCaptureH = 1600
                minBitrateBps = 1_200_000; maxBitrateBps = 6_000_000; startBitrateBps = 4_000_000
            }
            else -> { // 1080 默认
                maxCaptureW = 1920; maxCaptureH = 1080
                minBitrateBps = 1_000_000; maxBitrateBps = 5_000_000; startBitrateBps = 3_500_000
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scheduler: ScheduledExecutorService? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSender: RtpSender? = null

    private var captureWidth = 1280
    private var captureHeight = 720

    private var wsClient: WebSocketClient? = null
    /** 当前期望持有的 WS 实例,防止旧连接 onClose 误重连 */
    private var activeWs: WebSocketClient? = null

    @Volatile private var running = false
    /** 被顶替后为 true,禁止一切重连 */
    @Volatile private var kicked = false
    /** 远端 description 是否已设,用于 ICE 排队 */
    @Volatile private var remoteReady = false

    private val offering = AtomicBoolean(false)
    private val offerSeq = AtomicInteger(0)
    private val pendingIce = ArrayList<IceCandidate>()
    private val reconnectAttempt = AtomicInteger(0)

    private var heartbeatTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var statsTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var reconnectRunnable: Runnable? = null

    /** 当前发送码率上限(自适应) */
    @Volatile private var currentMaxBitrate = 0
    private var lastBytesSent = -1L
    private var lastPacketsSent = -1L
    private var lastNackCount = -1L
    private var lastStatsAt = 0L
    private var lastKeyframeAt = 0L
    private var consecutiveGood = 0
    private var consecutiveBad = 0

    fun start() {
        running = true
        kicked = false
        currentMaxBitrate = startBitrateBps
        scheduler = Executors.newSingleThreadScheduledExecutor()
        Log.i(TAG, "画质档位=$quality 上限 ${maxCaptureW}x${maxCaptureH} 码率 ${startBitrateBps / 1000}~${maxBitrateBps / 1000}kbps @${FPS}fps")
        connectSignaling()
    }

    fun stop() {
        running = false
        kicked = false
        mainHandler.removeCallbacksAndMessages(null)
        reconnectRunnable = null
        stopHeartbeat()
        stopStats()
        executor.execute {
            releasePeerAndCapture()
            try { factory?.dispose() } catch (_: Exception) {}
            factory = null
            try { eglBase?.release() } catch (_: Exception) {}
            eglBase = null
            closeWsQuietly()
            Log.i(TAG, "已释放所有资源")
        }
        try { scheduler?.shutdownNow() } catch (_: Exception) {}
        scheduler = null
        executor.shutdown()
    }

    // ===================== 信令 =====================

    private fun connectSignaling() {
        if (!running || kicked) return
        var qs = "room=${java.net.URLEncoder.encode(room, "UTF-8")}&role=pad"
        if (token.isNotEmpty()) {
            qs += "&token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        }
        val uri = URI("ws://$serverHost/ws?$qs")
        Log.i(TAG, "连接信令服务器: $uri")
        updateStatus("连接信令服务器…")

        // 关掉旧 socket,避免多连接
        closeWsQuietly()

        val client = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake?) {
                if (this !== activeWs || !running || kicked) return
                Log.i(TAG, "信令 WebSocket 已连接")
                reconnectAttempt.set(0)
                updateStatus("已连信令,等待浏览器打开…")
                startHeartbeat()
                initFactoryOnce()
            }

            override fun onMessage(message: String?) {
                if (this !== activeWs) return
                handleSignalingMessage(message ?: return)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (this !== activeWs) {
                    Log.i(TAG, "忽略旧 WS onClose code=$code")
                    return
                }
                Log.w(TAG, "信令断开: code=$code reason=$reason remote=$remote kicked=$kicked")
                stopHeartbeat()
                activeWs = null
                wsClient = null

                if (kicked || !running) {
                    Log.i(TAG, "不重连(kicked 或已停止)")
                    return
                }
                // 顶替时服务端会 close;若 kicked 消息丢失,也要避免立刻抢回
                // 用退避重连;若房间已被别人占用会再被踢
                scheduleReconnect()
            }

            override fun onError(e: Exception?) {
                Log.e(TAG, "信令错误: ${e?.message}")
            }
        }
        activeWs = client
        wsClient = client
        // 启用丢失检测(秒);配合应用层 ping
        client.connectionLostTimeout = 20
        client.connect()
    }

    private fun scheduleReconnect() {
        if (!running || kicked) return
        val n = reconnectAttempt.incrementAndGet()
        // 2,4,6... 最大 15 秒
        val delay = minOf(15_000L, 2_000L * n)
        updateStatus("信令断开,${delay / 1000}s 后重连…")
        val r = Runnable {
            if (running && !kicked) connectSignaling()
        }
        reconnectRunnable = r
        mainHandler.postDelayed(r, delay)
    }

    private fun closeWsQuietly() {
        val old = activeWs
        activeWs = null
        wsClient = null
        try { old?.close() } catch (_: Exception) {}
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        val sch = scheduler ?: return
        heartbeatTask = sch.scheduleAtFixedRate({
            if (!running || kicked) return@scheduleAtFixedRate
            try {
                val ws = activeWs
                if (ws != null && ws.isOpen) {
                    ws.send("""{"type":"ping"}""")
                }
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat 失败: ${e.message}")
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopHeartbeat() {
        try { heartbeatTask?.cancel(false) } catch (_: Exception) {}
        heartbeatTask = null
    }

    private fun handleSignalingMessage(raw: String) {
        try {
            val json = org.json.JSONObject(raw)
            when (json.optString("type")) {
                "joined" -> {
                    val peerOnline = json.optBoolean("peerOnline")
                    Log.i(TAG, "已加入房间,浏览器在线:$peerOnline")
                    if (peerOnline) createOffer()
                    else updateStatus("已连信令,等待浏览器打开…")
                }
                "peer-online" -> {
                    Log.i(TAG, "浏览器已上线,发起 offer")
                    createOffer()
                }
                "answer" -> {
                    val sdp = json.optJSONObject("data") ?: return
                    val desc = SessionDescription(
                        SessionDescription.Type.ANSWER,
                        sdp.optString("sdp")
                    )
                    executor.execute { setRemoteAnswer(desc) }
                }
                "ice" -> {
                    val cand = json.optJSONObject("data") ?: return
                    val candidate = IceCandidate(
                        cand.optString("sdpMid"),
                        cand.optInt("sdpMLineIndex"),
                        cand.optString("candidate")
                    )
                    executor.execute { addRemoteIce(candidate) }
                }
                "peer-left" -> {
                    Log.i(TAG, "浏览器离开了,保留抓屏等重连")
                    updateStatus("浏览器已断开,等待重新打开…")
                    executor.execute { releasePeerOnly() }
                }
                "kicked", "stop" -> {
                    val reason = json.optString("reason")
                    if (reason.contains("teacher")) {
                        handleKicked("教师断开", userMessage = "教师已断开投屏")
                    } else {
                        handleKicked("信令通知", userMessage = "已被其他设备投屏替换")
                    }
                }
                "pong", "ping" -> {
                    // 忽略
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析信令消息失败: $raw")
        }
    }

    /** 被顶替/教师断开:立刻停轨+关 PC,禁止重连 */
    private fun handleKicked(from: String, userMessage: String = "已被其他设备投屏替换") {
        Log.w(TAG, "被断开($from),停止推流且禁止重连")
        kicked = true
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        stopHeartbeat()
        stopStats()
        updateStatus(userMessage)
        // 同步尽快停轨,避免旧画面继续推到黑板
        try { videoTrack?.setEnabled(false) } catch (_: Exception) {}
        try { videoSender?.setTrack(null, false) } catch (_: Exception) {}
        executor.execute {
            releasePeerAndCapture()
            closeWsQuietly()
        }
    }

    private fun sendToSignaling(json: org.json.JSONObject) {
        try {
            val ws = activeWs
            if (ws != null && ws.isOpen) ws.send(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "发送信令失败: ${e.message}")
        }
    }

    // ===================== WebRTC =====================

    private fun ensureFactory(): Boolean {
        if (factory != null) return true
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            eglBase = EglBase.create()
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
                )
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
                )
                .createPeerConnectionFactory()
            Log.i(TAG, "PeerConnectionFactory 初始化完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Factory 初始化失败: ${e.message}", e)
            updateStatus("初始化失败:${e.message}")
            false
        }
    }

    private fun initFactoryOnce() {
        executor.execute { ensureFactory() }
    }

    private fun ensurePeerReady(): Boolean {
        if (!ensureFactory()) return false
        if (peerConnection != null && videoTrack != null) return true

        try {
            remoteReady = false
            synchronized(pendingIce) { pendingIce.clear() }

            val config = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            peerConnection = factory!!.createPeerConnection(config, pcObserver)

            if (videoTrack != null) {
                videoTrack!!.setEnabled(true)
                videoSender = peerConnection!!.addTrack(videoTrack, listOf(STREAM_ID))
                Log.i(TAG, "复用已有 VideoTrack")
            } else {
                createVideoTrack()
            }
            applySenderConstraints()
            startStats()
            Log.i(TAG, "PeerConnection 就绪 ${captureWidth}x${captureHeight}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "创建 PeerConnection 失败: ${e.message}", e)
            updateStatus("初始化失败:${e.message}")
            return false
        }
    }

    private fun createVideoTrack() {
        videoCapturer = ScreenCapturerAndroid(
            mediaProjectionPermissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection 被停止")
                    updateStatus("投屏被系统停止")
                }
            }
        )

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val maxW = maxCaptureW
        val maxH = maxCaptureH
        var cw = metrics.widthPixels
        var ch = metrics.heightPixels
        if (cw > maxW || ch > maxH) {
            val scale = minOf(maxW.toFloat() / cw, maxH.toFloat() / ch)
            cw = (cw * scale).toInt()
            ch = (ch * scale).toInt()
        }
        if (cw % 2 != 0) cw -= 1
        if (ch % 2 != 0) ch -= 1
        // 最低也别太小
        if (cw < 640) { cw = 640; if (cw % 2 != 0) cw -= 1 }
        if (ch < 360) { ch = 360; if (ch % 2 != 0) ch -= 1 }
        captureWidth = cw
        captureHeight = ch
        Log.i(TAG, "屏幕 ${metrics.widthPixels}x${metrics.heightPixels} → 抓屏 ${cw}x${ch} @${FPS}fps (quality=$quality)")

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(true)

        val rawObserver = videoSource!!.capturerObserver
        val frameCounter = AtomicInteger(0)
        val wrappedObserver = object : org.webrtc.CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                Log.i(TAG, "🎯 抓屏已启动 success=$success")
                rawObserver.onCapturerStarted(success)
            }
            override fun onCapturerStopped() { rawObserver.onCapturerStopped() }
            override fun onFrameCaptured(frame: org.webrtc.VideoFrame?) {
                val n = frameCounter.incrementAndGet()
                if (n <= 3 || n % 100 == 0) {
                    Log.i(TAG, "🎥 抓到第 $n 帧 ${frame?.rotatedWidth}x${frame?.rotatedHeight}")
                }
                rawObserver.onFrameCaptured(frame)
            }
        }

        videoCapturer!!.initialize(surfaceHelper, context, wrappedObserver)
        videoCapturer!!.startCapture(cw, ch, FPS)

        videoTrack = factory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack!!.setEnabled(true)
        try {
            videoTrack!!.javaClass.getMethod("setContentHint", String::class.java)
                .invoke(videoTrack, "detail")
        } catch (_: Exception) {}

        videoSender = peerConnection!!.addTrack(videoTrack, listOf(STREAM_ID))
        currentMaxBitrate = startBitrateBps
        Log.i(TAG, "VideoTrack 已加入 (stream=$STREAM_ID)")
    }

    private fun applySenderConstraints() {
        applySenderBitrate(currentMaxBitrate.coerceAtLeast(startBitrateBps), log = true)
    }

    private fun applySenderBitrate(bitrateBps: Int, log: Boolean = false) {
        val sender = videoSender ?: return
        val capped = bitrateBps.coerceIn(minBitrateBps, maxBitrateBps)
        try {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                val enc = params.encodings[0]
                enc.maxBitrateBps = capped
                try { enc.minBitrateBps = minBitrateBps } catch (_: Exception) {}
                try { enc.maxFramerate = FPS } catch (_: Exception) {}
                try { enc.scaleResolutionDownBy = 1.0 } catch (_: Exception) {}
            }
            try {
                // 投屏文字优先保分辨率,宁可降帧也不糊化
                params.degradationPreference =
                    RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            } catch (_: Exception) {}
            sender.parameters = params
            currentMaxBitrate = capped
            if (log) {
                Log.i(TAG, "RtpSender 约束: maxBitrate=${capped / 1000}kbps maxFps=$FPS quality=$quality")
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置 RtpSender 参数失败: ${e.message}")
        }
    }

    /** 请求编码器立刻出关键帧(花屏恢复/新会话) */
    private fun requestKeyframe(reason: String) {
        val sender = videoSender ?: return
        val now = System.currentTimeMillis()
        if (now - lastKeyframeAt < 800) return // 防抖
        lastKeyframeAt = now
        try {
            // 新版 WebRTC: RtpSender.generateKeyFrame()
            val m = sender.javaClass.methods.firstOrNull {
                it.name == "generateKeyFrame" && it.parameterTypes.isEmpty()
            }
            if (m != null) {
                m.invoke(sender)
                Log.i(TAG, "🔑 已请求关键帧($reason) via generateKeyFrame")
                return
            }
            // 兼容: 部分版本 generateKeyFrame(List<String> rids)
            val m2 = sender.javaClass.methods.firstOrNull {
                it.name == "generateKeyFrame" && it.parameterTypes.size == 1
            }
            if (m2 != null) {
                m2.invoke(sender, emptyList<String>())
                Log.i(TAG, "🔑 已请求关键帧($reason) via generateKeyFrame(rids)")
                return
            }
            Log.w(TAG, "当前 WebRTC 库无 generateKeyFrame,依赖 PLI/定期 IDR")
        } catch (e: Exception) {
            Log.w(TAG, "请求关键帧失败: ${e.message}")
        }
    }

    private fun createOffer() {
        if (kicked || !running) return
        if (!offering.compareAndSet(false, true)) {
            Log.w(TAG, "已有 offer 进行中,跳过")
            return
        }
        val seq = offerSeq.incrementAndGet()
        executor.execute {
            try {
                if (kicked || !running) {
                    offering.set(false)
                    return@execute
                }
                if (!ensurePeerReady()) {
                    offering.set(false)
                    return@execute
                }
                val pc = peerConnection ?: run {
                    offering.set(false)
                    return@execute
                }

                val ice = pc.iceConnectionState()
                if (pc.signalingState() == PeerConnection.SignalingState.STABLE &&
                    (ice == PeerConnection.IceConnectionState.CONNECTED ||
                        ice == PeerConnection.IceConnectionState.COMPLETED)
                ) {
                    Log.i(TAG, "已在投屏中,跳过重复 offer#$seq")
                    offering.set(false)
                    return@execute
                }

                remoteReady = false
                synchronized(pendingIce) { pendingIce.clear() }

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                pc.createOffer(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        val preferred = preferCodec(desc.description, PREFERRED_CODEC)
                        val local = SessionDescription(desc.type, preferred)
                        val codecs = Regex("""a=rtpmap:(\d+) ([A-Za-z0-9-]+)/""")
                            .findAll(preferred)
                            .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
                            .toList()
                        Log.i(TAG, "📋 offer#$seq codecs: $codecs")

                        pc.setLocalDescription(object : org.webrtc.SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                if (kicked || !running) {
                                    offering.set(false)
                                    return
                                }
                                val json = org.json.JSONObject()
                                json.put("type", "offer")
                                val data = org.json.JSONObject()
                                data.put("type", local.type.canonicalForm())
                                data.put("sdp", local.description)
                                json.put("data", data)
                                sendToSignaling(json)
                                Log.i(TAG, "已发送 offer#$seq")
                                updateStatus("已发起连接,等待浏览器响应…")
                                offering.set(false)
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "setLocalDescription 失败: $error")
                                offering.set(false)
                            }
                        }, local)
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "createOffer 失败: $error")
                        updateStatus("发起连接失败:$error")
                        offering.set(false)
                    }
                }, constraints)
            } catch (e: Exception) {
                Log.e(TAG, "createOffer 异常: ${e.message}", e)
                offering.set(false)
            }
        }
    }

    private fun setRemoteAnswer(desc: SessionDescription) {
        if (kicked) return
        val pc = peerConnection
        if (pc == null) {
            Log.w(TAG, "收到 answer 但 PeerConnection 已释放,忽略")
            return
        }
        pc.setRemoteDescription(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                remoteReady = true
                flushPendingIce()
                currentMaxBitrate = startBitrateBps
                applySenderConstraints()
                // 协商完成马上要一帧关键帧,浏览器更快出第一帧清画
                mainHandler.postDelayed({
                    if (!kicked && running) requestKeyframe("answer-set")
                }, 300)
                Log.i(TAG, "已设置远端 answer")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e(TAG, "setRemoteAnswer 失败: $p0")
            }
        }, desc)
    }

    private fun addRemoteIce(candidate: IceCandidate) {
        if (kicked) return
        val pc = peerConnection
        if (pc == null || !remoteReady) {
            synchronized(pendingIce) { pendingIce.add(candidate) }
            return
        }
        pc.addIceCandidate(candidate)
    }

    private fun flushPendingIce() {
        val pc = peerConnection ?: return
        val batch: List<IceCandidate>
        synchronized(pendingIce) {
            batch = pendingIce.toList()
            pendingIce.clear()
        }
        for (c in batch) {
            try { pc.addIceCandidate(c) } catch (_: Exception) {}
        }
        if (batch.isNotEmpty()) Log.i(TAG, "补发 ${batch.size} 个缓存 ICE")
    }

    private fun releasePeerOnly() {
        offering.set(false)
        remoteReady = false
        stopStats()
        synchronized(pendingIce) { pendingIce.clear() }
        videoSender = null
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
        Log.i(TAG, "PeerConnection 已关闭,抓屏继续运行")
    }

    private fun releasePeerAndCapture() {
        try { videoTrack?.setEnabled(false) } catch (_: Exception) {}
        releasePeerOnly()
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        videoCapturer = null
        try { videoTrack?.dispose() } catch (_: Exception) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Exception) {}
        videoSource = null
        try { surfaceHelper?.dispose() } catch (_: Exception) {}
        surfaceHelper = null
    }

    private fun startStats() {
        stopStats()
        lastBytesSent = -1L
        lastPacketsSent = -1L
        lastNackCount = -1L
        lastStatsAt = 0L
        consecutiveGood = 0
        consecutiveBad = 0
        val sch = scheduler ?: return
        statsTask = sch.scheduleAtFixedRate({
            val pc = peerConnection ?: return@scheduleAtFixedRate
            if (kicked || !running) return@scheduleAtFixedRate
            try {
                pc.getStats { report ->
                    processSendStats(report, pc)
                }
            } catch (e: Exception) {
                Log.w(TAG, "stats 失败: ${e.message}")
            }
        }, STATS_MS, STATS_MS, TimeUnit.MILLISECONDS)
    }

    private fun processSendStats(report: org.webrtc.RTCStatsReport, pc: PeerConnection) {
        var outFps = -1.0
        var bytes = -1L
        var packetsSent = -1L
        var nackCount = -1L
        var framesEncoded = -1L
        var codec = "?"
        var rttMs = -1.0
        var fractionLost = -1.0
        var qualityLimit = ""

        for (stat in report.statsMap.values) {
            when (stat.type) {
                "outbound-rtp" -> {
                    // 只看视频
                    val kind = stat.members["kind"]?.toString()
                    val mediaType = stat.members["mediaType"]?.toString()
                    if (kind == "audio" || mediaType == "audio") continue
                    val fps = stat.members["framesPerSecond"]
                    if (fps is Number) outFps = fps.toDouble()
                    val b = stat.members["bytesSent"]
                    if (b is Number) bytes = b.toLong()
                    val ps = stat.members["packetsSent"]
                    if (ps is Number) packetsSent = ps.toLong()
                    val nack = stat.members["nackCount"]
                    if (nack is Number) nackCount = nack.toLong()
                    val fe = stat.members["framesEncoded"]
                    if (fe is Number) framesEncoded = fe.toLong()
                    val ql = stat.members["qualityLimitationReason"]?.toString()
                    if (!ql.isNullOrEmpty() && ql != "none") qualityLimit = ql
                }
                "remote-inbound-rtp" -> {
                    val kind = stat.members["kind"]?.toString()
                    if (kind == "audio") continue
                    val rtt = stat.members["roundTripTime"]
                    // 单位秒
                    if (rtt is Number) rttMs = rtt.toDouble() * 1000.0
                    val fl = stat.members["fractionLost"]
                    if (fl is Number) fractionLost = fl.toDouble()
                }
                "codec" -> {
                    val mime = stat.members["mimeType"]?.toString()
                    if (mime != null && mime.contains("video", true)) {
                        codec = mime.substringAfter("/")
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        var kbps = -1
        if (bytes >= 0 && lastBytesSent >= 0 && lastStatsAt > 0) {
            val dt = (now - lastStatsAt).coerceAtLeast(1)
            kbps = ((bytes - lastBytesSent) * 8 / dt).toInt() // bytes/ms * 8 = kbps 近似
        }
        if (bytes >= 0) lastBytesSent = bytes
        lastStatsAt = now

        // ---- 弱网自适应码率 ----
        adaptBitrate(fractionLost, rttMs, nackCount, packetsSent, qualityLimit)

        // ---- 定时关键帧 ----
        val ice = pc.iceConnectionState()
        if (ice == PeerConnection.IceConnectionState.CONNECTED ||
            ice == PeerConnection.IceConnectionState.COMPLETED
        ) {
            if (now - lastKeyframeAt >= KEYFRAME_INTERVAL_MS) {
                requestKeyframe("interval")
            }
        }

        if (outFps >= 0 || bytes >= 0) {
            Log.i(
                TAG,
                "📊 send codec=$codec fps=${"%.1f".format(outFps)} ~${kbps}kbps " +
                    "cap=${currentMaxBitrate / 1000}k rtt=${"%.0f".format(rttMs)}ms " +
                    "loss=${"%.2f".format(fractionLost)} nackΔ=$nackCount ql=$qualityLimit " +
                    "${captureWidth}x${captureHeight} enc=$framesEncoded"
            )
        }
        if (lastNackCount < 0 && nackCount >= 0) lastNackCount = nackCount
        if (lastPacketsSent < 0 && packetsSent >= 0) lastPacketsSent = packetsSent
    }

    /**
     * 根据丢包/RTT/NACK/质量限制调整 maxBitrate。
     * 教室 WiFi 抖动时优先保流畅,分辨率尽量维持(MAINTAIN_RESOLUTION)。
     */
    private fun adaptBitrate(
        fractionLost: Double,
        rttMs: Double,
        nackCount: Long,
        packetsSent: Long,
        qualityLimit: String
    ) {
        var nackDelta = 0L
        if (nackCount >= 0 && lastNackCount >= 0) {
            nackDelta = (nackCount - lastNackCount).coerceAtLeast(0)
        }
        if (nackCount >= 0) lastNackCount = nackCount

        var pktDelta = 0L
        if (packetsSent >= 0 && lastPacketsSent >= 0) {
            pktDelta = (packetsSent - lastPacketsSent).coerceAtLeast(0)
        }
        if (packetsSent >= 0) lastPacketsSent = packetsSent

        val lossHigh = fractionLost >= 0.08 || // 8%+
            (pktDelta > 20 && nackDelta > pktDelta / 8) ||
            rttMs > 250 ||
            qualityLimit == "bandwidth"

        val lossLow = (fractionLost in 0.0..0.02 || fractionLost < 0) &&
            rttMs in 0.0..120.0 &&
            nackDelta <= 2 &&
            qualityLimit.isEmpty()

        when {
            lossHigh -> {
                consecutiveBad++
                consecutiveGood = 0
                if (consecutiveBad >= 2) {
                    val next = (currentMaxBitrate * 0.75).toInt()
                        .coerceIn(minBitrateBps, maxBitrateBps)
                    if (next < currentMaxBitrate - 50_000) {
                        Log.w(TAG, "↓ 弱网降码率 ${currentMaxBitrate / 1000}k → ${next / 1000}k " +
                            "(loss=$fractionLost rtt=$rttMs nackΔ=$nackDelta ql=$qualityLimit)")
                        applySenderBitrate(next)
                        // 降码后补关键帧,避免花屏停住
                        requestKeyframe("bitrate-down")
                    }
                    consecutiveBad = 0
                }
            }
            lossLow -> {
                consecutiveGood++
                consecutiveBad = 0
                if (consecutiveGood >= 4 && currentMaxBitrate < maxBitrateBps) {
                    val next = (currentMaxBitrate * 1.15).toInt()
                        .coerceIn(minBitrateBps, maxBitrateBps)
                    if (next > currentMaxBitrate + 50_000) {
                        Log.i(TAG, "↑ 网络恢复升码率 ${currentMaxBitrate / 1000}k → ${next / 1000}k")
                        applySenderBitrate(next)
                    }
                    consecutiveGood = 0
                }
            }
            else -> {
                consecutiveGood = 0
                consecutiveBad = 0
            }
        }
    }

    private fun stopStats() {
        try { statsTask?.cancel(false) } catch (_: Exception) {}
        statsTask = null
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (kicked || !running) return
            val json = org.json.JSONObject()
            json.put("type", "ice")
            val data = org.json.JSONObject()
            data.put("sdpMid", candidate.sdpMid)
            data.put("sdpMLineIndex", candidate.sdpMLineIndex)
            data.put("candidate", candidate.sdp)
            json.put("data", data)
            sendToSignaling(json)
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.i(TAG, "ICE 连接状态: $state")
            if (kicked) return
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    updateStatus("投屏中")
                    // 刚连通时强制关键帧,接收端立刻有完整画面
                    mainHandler.postDelayed({
                        if (!kicked && running) requestKeyframe("ice-connected")
                    }, 200)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> updateStatus("连接断开,尝试恢复…")
                PeerConnection.IceConnectionState.FAILED -> {
                    updateStatus("连接失败,请确认与黑板同一 WiFi(勿开客户端隔离)")
                    executor.execute { releasePeerOnly() }
                }
                else -> {}
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.i(TAG, "Signaling: $state")
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: org.webrtc.MediaStream?) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
        override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
    }

    private fun updateStatus(text: String) {
        Log.i(TAG, "状态:$text")
        onStatus(text)
    }
}

private fun preferCodec(sdp: String, codec: String): String {
    val lines = sdp.split("\r\n").toMutableList()
    val mIndex = lines.indexOfFirst { it.startsWith("m=video") }
    if (mIndex < 0) return sdp

    val pts = mutableListOf<String>()
    val rtpmap = Regex("""^a=rtpmap:(\d+) ${Regex.escape(codec)}/""", RegexOption.IGNORE_CASE)
    for (line in lines) {
        val m = rtpmap.find(line) ?: continue
        pts.add(m.groupValues[1])
    }
    if (pts.isEmpty()) {
        Log.w("RtcClient", "SDP 中未找到 codec=$codec,保持原序")
        return sdp
    }

    val parts = lines[mIndex].split(" ").toMutableList()
    if (parts.size <= 3) return sdp
    val header = parts.subList(0, 3).toList()
    val payloads = parts.subList(3, parts.size).toMutableList()
    val preferred = pts.filter { payloads.contains(it) }
    payloads.removeAll(preferred.toSet())
    lines[mIndex] = (header + preferred + payloads).joinToString(" ")
    Log.i("RtcClient", "SDP 已优先 $codec → pt=$preferred")
    return lines.joinToString("\r\n")
}
