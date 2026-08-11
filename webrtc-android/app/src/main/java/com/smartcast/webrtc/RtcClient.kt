package com.smartcast.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebRTC 投屏客户端。
 *
 * 职责:
 *   1. 连信令服务器(WebSocket),加入房间
 *   2. 浏览器在线时 createOffer
 *   3. ScreenCapturerAndroid 抓屏 → VideoTrack
 *   4. answer / ICE 交换
 *
 * 关键优化点:
 *   - addTrack 必须带 streamId,否则浏览器 e.streams 为空 → 黑屏
 *   - SDP 里把 H264 提到最前(投屏走硬编更稳)
 *   - RtpSender 限码率/帧率,DegradationPreference=MAINTAIN_RESOLUTION
 *   - 浏览器离开后重建 PeerConnection,避免半残 renegotiation
 */
class RtcClient(
    private val context: Context,
    private val mediaProjectionPermissionData: Intent,
    private val serverHost: String, // 形如 192.168.1.100:8080
    private val room: String,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "RtcClient"
        private const val STREAM_ID = "smartcast-stream"
        private const val VIDEO_TRACK_ID = "smartcast-video"
        private const val FPS = 20
        // 投屏码率:局域网 2.5Mbps 够 720p 清晰,再高浪费带宽
        private const val MAX_BITRATE_BPS = 2_500_000
        private const val PREFERRED_CODEC = "H264"
    }

    private val executor = Executors.newSingleThreadExecutor()
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
    @Volatile private var running = false
    /** 防止 createOffer 并发(peer-online + joined 同时触发) */
    private val offering = AtomicBoolean(false)
    private val offerSeq = AtomicInteger(0)

    fun start() {
        running = true
        connectSignaling()
    }

    fun stop() {
        running = false
        executor.execute {
            releasePeerAndCapture()
            try { factory?.dispose() } catch (_: Exception) {}
            factory = null
            try { eglBase?.release() } catch (_: Exception) {}
            eglBase = null
            try { wsClient?.close() } catch (_: Exception) {}
            wsClient = null
            Log.i(TAG, "已释放所有资源")
        }
        executor.shutdown()
    }

    // ===================== 信令 =====================

    private fun connectSignaling() {
        val uri = URI("ws://$serverHost/ws?room=${java.net.URLEncoder.encode(room, "UTF-8")}&role=pad")
        Log.i(TAG, "连接信令服务器: $uri")
        updateStatus("连接信令服务器…")

        wsClient = object : WebSocketClient(uri) {
            override fun onOpen(handshake: ServerHandshake?) {
                Log.i(TAG, "信令 WebSocket 已连接")
                updateStatus("已连信令,等待浏览器打开…")
                initFactoryOnce()
            }

            override fun onMessage(message: String?) {
                handleSignalingMessage(message ?: return)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.w(TAG, "信令断开: code=$code reason=$reason")
                if (running) updateStatus("信令断开,正在重连…")
                if (running) {
                    Thread.sleep(2000)
                    if (running) connectSignaling()
                }
            }

            override fun onError(e: Exception?) {
                Log.e(TAG, "信令错误: ${e?.message}")
            }
        }
        wsClient?.setConnectionLostTimeout(0)
        wsClient?.connect()
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
                    executor.execute { peerConnection?.addIceCandidate(candidate) }
                }
                "peer-left" -> {
                    Log.i(TAG, "浏览器离开了,仅关闭 PeerConnection(保留抓屏,MediaProjection 授权只能用一次)")
                    updateStatus("浏览器已断开,等待重新打开…")
                    executor.execute { releasePeerOnly() }
                }
                "kicked" -> {
                    // 被其他 pad 顶替:停止推流,不再重连
                    Log.w(TAG, "被其他设备顶替投屏")
                    running = false
                    updateStatus("已被其他设备投屏替换")
                    executor.execute {
                        releasePeerAndCapture()
                        try { wsClient?.close() } catch (_: Exception) {}
                        wsClient = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析信令消息失败: $raw")
        }
    }

    private fun sendToSignaling(json: org.json.JSONObject) {
        try {
            wsClient?.send(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "发送信令失败: ${e.message}")
        }
    }

    // ===================== WebRTC =====================

    /** 必须在 executor 线程调用 */
    private fun ensureFactory(): Boolean {
        if (factory != null) return true
        return try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            eglBase = EglBase.create()
            // enableIntelVp8Encoder=true, enableH264HighProfile=true
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

    /** 创建(或重建) PeerConnection;抓屏尽量复用。必须在 executor 线程调用 */
    private fun ensurePeerReady(): Boolean {
        if (!ensureFactory()) return false
        if (peerConnection != null && videoTrack != null) return true

        try {
            val iceServers = emptyList<PeerConnection.IceServer>()
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            }
            peerConnection = factory!!.createPeerConnection(config, pcObserver)

            if (videoTrack != null) {
                // 浏览器重连:抓屏还在跑,只把已有 track 重新挂上
                videoSender = peerConnection!!.addTrack(videoTrack, listOf(STREAM_ID))
                Log.i(TAG, "复用已有 VideoTrack 挂到新 PeerConnection")
            } else {
                createVideoTrack()
            }
            applySenderConstraints()
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
        val maxW = 1280
        val maxH = 720
        var cw = metrics.widthPixels
        var ch = metrics.heightPixels
        if (cw > maxW || ch > maxH) {
            val scale = minOf(maxW.toFloat() / cw, maxH.toFloat() / ch)
            cw = (cw * scale).toInt()
            ch = (ch * scale).toInt()
        }
        // 编码器要求偶数
        if (cw % 2 != 0) cw -= 1
        if (ch % 2 != 0) ch -= 1
        captureWidth = cw
        captureHeight = ch
        Log.i(TAG, "屏幕 ${metrics.widthPixels}x${metrics.heightPixels} → 抓屏 ${cw}x${ch} @${FPS}fps")

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        videoSource = factory!!.createVideoSource(/* isScreencast = */ true)

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
        // 屏幕内容优先保细节(文字/UI),告诉编码器别过度糊化
        try {
            // ContentHint: 0=NONE 1=FLUID 2=DETAILED(部分版本 API)
            videoTrack!!.javaClass.getMethod("setContentHint", String::class.java)
                .invoke(videoTrack, "detail")
        } catch (_: Exception) {
            // 旧版 lib 没有此 API,忽略
        }

        videoSender = peerConnection!!.addTrack(videoTrack, listOf(STREAM_ID))
        Log.i(TAG, "VideoTrack 已加入 (stream=$STREAM_ID)")
    }

    /** 限制发送码率/帧率,投屏保分辨率 */
    private fun applySenderConstraints() {
        val sender = videoSender ?: return
        try {
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                val enc = params.encodings[0]
                enc.maxBitrateBps = MAX_BITRATE_BPS
                enc.maxFramerate = FPS
                // 有的版本支持 scaleResolutionDownBy,保持 1.0 不缩放
                try { enc.scaleResolutionDownBy = 1.0 } catch (_: Exception) {}
            }
            try {
                params.degradationPreference =
                    RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            } catch (_: Exception) {}
            sender.parameters = params
            Log.i(TAG, "RtpSender 约束: maxBitrate=${MAX_BITRATE_BPS / 1000}kbps maxFps=$FPS maintainRes")
        } catch (e: Exception) {
            Log.w(TAG, "设置 RtpSender 参数失败: ${e.message}")
        }
    }

    private fun createOffer() {
        if (!offering.compareAndSet(false, true)) {
            Log.w(TAG, "已有 offer 进行中,跳过")
            return
        }
        val seq = offerSeq.incrementAndGet()
        executor.execute {
            try {
                if (!ensurePeerReady()) {
                    offering.set(false)
                    return@execute
                }
                val pc = peerConnection ?: run {
                    offering.set(false)
                    return@execute
                }

                // 已在 stable 且 ICE 已通时,不重复发 offer(避免无意义 renegotiation)
                val ice = pc.iceConnectionState()
                if (pc.signalingState() == PeerConnection.SignalingState.STABLE &&
                    (ice == PeerConnection.IceConnectionState.CONNECTED ||
                     ice == PeerConnection.IceConnectionState.COMPLETED)
                ) {
                    Log.i(TAG, "已在投屏中,跳过重复 offer#$seq")
                    offering.set(false)
                    return@execute
                }

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                pc.createOffer(object : org.webrtc.SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        // 把 H264 提到 m=video 最前,优先硬编
                        val preferred = preferCodec(desc.description, PREFERRED_CODEC)
                        val local = SessionDescription(desc.type, preferred)

                        val codecs = Regex("""a=rtpmap:(\d+) ([A-Za-z0-9-]+)/""")
                            .findAll(preferred)
                            .map { "${it.groupValues[1]}=${it.groupValues[2]}" }
                            .toList()
                        Log.i(TAG, "📋 offer#$seq codecs(优先序): $codecs")

                        pc.setLocalDescription(object : org.webrtc.SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
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
        val pc = peerConnection
        if (pc == null) {
            Log.w(TAG, "收到 answer 但 PeerConnection 已释放,忽略")
            return
        }
        pc.setRemoteDescription(SimpleSdpObserver("setRemoteAnswer"), desc)
        // answer 到位后再刷一次发送约束(有的实现 encodings 在协商后才完整)
        applySenderConstraints()
        Log.i(TAG, "已设置远端 answer,投屏建立中")
    }

    /** 只关 PeerConnection,保留 MediaProjection 抓屏(授权一次性) */
    private fun releasePeerOnly() {
        offering.set(false)
        videoSender = null
        try { peerConnection?.close() } catch (_: Exception) {}
        try { peerConnection?.dispose() } catch (_: Exception) {}
        peerConnection = null
        Log.i(TAG, "PeerConnection 已关闭,抓屏继续运行")
    }

    private fun releasePeerAndCapture() {
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

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
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
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> updateStatus("投屏中")
                PeerConnection.IceConnectionState.DISCONNECTED -> updateStatus("连接断开,尝试恢复…")
                PeerConnection.IceConnectionState.FAILED -> {
                    updateStatus("连接失败,等待浏览器重连…")
                    // 只关 PC,保留抓屏;下次 peer-online 再建新 PC
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

    private class SimpleSdpObserver(private val tag: String = "") : org.webrtc.SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() { Log.i(TAG, "SdpObserver[$tag] set ok") }
        override fun onCreateFailure(p0: String?) { Log.e(TAG, "SdpObserver[$tag] create fail: $p0") }
        override fun onSetFailure(p0: String?) { Log.e(TAG, "SdpObserver[$tag] set fail: $p0") }
    }
}

/**
 * 把指定 codec 的 payload type 挪到 m= 行最前面,提高协商优先级。
 * 例: m=video 9 ... 96 97 104 → 104 96 97 (H264=104 优先)
 */
private fun preferCodec(sdp: String, codec: String): String {
    val lines = sdp.split("\r\n").toMutableList()
    val mIndex = lines.indexOfFirst { it.startsWith("m=video") }
    if (mIndex < 0) return sdp

    // 收集该 codec 的 pt(可能有多个 profile)
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
    // m=video port proto pt pt pt...
    if (parts.size <= 3) return sdp
    val header = parts.subList(0, 3).toList()
    val payloads = parts.subList(3, parts.size).toMutableList()
    val preferred = pts.filter { payloads.contains(it) }
    payloads.removeAll(preferred.toSet())
    lines[mIndex] = (header + preferred + payloads).joinToString(" ")
    Log.i("RtcClient", "SDP 已优先 $codec → pt=$preferred")
    return lines.joinToString("\r\n")
}
