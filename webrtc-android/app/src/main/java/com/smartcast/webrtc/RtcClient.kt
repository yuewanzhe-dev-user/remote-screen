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
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.net.URI
import java.util.concurrent.Executors

/**
 * WebRTC 投屏客户端。
 *
 * 职责:
 *   1. 连信令服务器(WebSocket),加入房间
 *   2. 收到浏览器连接(joined 且 peerOnline=true)时,createOffer 发给浏览器
 *   3. 用 ScreenCapturerAndroid 抓屏,封装成 VideoTrack
 *   4. 浏览器回 answer → setRemoteDescription
 *   5. ICE candidate 双向交换
 *
 * 线程模型:
 *   - 信令(WebSocket)跑在 Java-WebSocket 自己的线程
 *   - WebRTC 的 SDP/ICE 回调跑在 PeerConnection 的内部线程
 *   - 主线程只用来更新 UI(通过 onStatus 回调)
 *   用一个单线程执行器把这些串起来,避免并发问题
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
        private const val VIDEO_TRACK_ID = "smartcast-video"
        private const val WIDTH = 1280   // 编码分辨率上限(平衡画质/性能)
        private const val HEIGHT = 720
        private const val FPS = 20       // 投屏不需要高帧率,20 够用且省带宽
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var wsClient: WebSocketClient? = null
    @Volatile private var running = false

    /**
     * 启动:连信令 → 初始化 WebRTC → 等浏览器配对。
     * 必须在主线程调用(内部会切到 executor)。
     */
    fun start() {
        running = true
        connectSignaling()
    }

    /** 停止并释放所有资源 */
    fun stop() {
        running = false
        executor.execute {
            try { videoCapturer?.stopCapture() } catch (_: Exception) {}
            try { videoTrack?.dispose() } catch (_: Exception) {}
            try { videoSource?.dispose() } catch (_: Exception) {}
            try { surfaceHelper?.dispose() } catch (_: Exception) {}
            try { peerConnection?.dispose() } catch (_: Exception) {}
            try { factory?.dispose() } catch (_: Exception) {}
            try { eglBase?.dispose() } catch (_: Exception) {}
            try { wsClient?.close() } catch (_: Exception) {}
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
                // WebSocket 连上后,初始化 WebRTC(只初始化一次,等浏览器来就 offer)
                initWebRTC()
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
        wsClient?.connectionTimeout = 5000
        wsClient?.connect()
    }

    private fun handleSignalingMessage(raw: String) {
        try {
            // 用 org.json(避免引 gson),信令消息都是简单 JSON
            val json = org.json.JSONObject(raw)
            val type = json.optString("type")
            when (type) {
                "joined" -> {
                    // 服务器告知已加入房间,peerOnline=true 表示浏览器已在线
                    val peerOnline = json.optBoolean("peerOnline")
                    Log.i(TAG, "已加入房间,浏览器在线:$peerOnline")
                    if (peerOnline) {
                        // 浏览器在等我们的 offer,立即发起
                        createOffer()
                    } else {
                        updateStatus("已连信令,等待浏览器打开…")
                    }
                }
                "answer" -> {
                    // 浏览器回的 answer
                    val sdp = json.optJSONObject("data")
                    if (sdp != null) {
                        val desc = SessionDescription(
                            SessionDescription.Type.ANSWER,
                            sdp.optString("sdp")
                        )
                        executor.execute { setRemoteAnswer(desc) }
                    }
                }
                "ice" -> {
                    val cand = json.optJSONObject("data")
                    if (cand != null) {
                        val candidate = IceCandidate(
                            cand.optString("sdpMid"),
                            cand.optInt("sdpMLineIndex"),
                            cand.optString("candidate")
                        )
                        executor.execute { peerConnection?.addIceCandidate(candidate) }
                    }
                }
                "peer-left" -> {
                    Log.i(TAG, "浏览器离开了")
                    updateStatus("浏览器已断开,等待重新打开…")
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

    private fun initWebRTC() {
        executor.execute {
            if (factory != null) return@execute // 已初始化
            try {
                // 1. 初始化 PeerConnectionFactory(全局一次)
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )

                eglBase = EglBase.create()

                // 2. 创建 Factory,带 H.264 编解码器
                factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
                    )
                    .setVideoDecoderFactory(
                        DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
                    )
                    .createPeerConnectionFactory()

                // 3. 创建 PeerConnection(局域网不用 STUN/TURN)
                val iceServers = emptyList<PeerConnection.IceServer>()
                val config = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    // 局域网 host candidate 即可,关掉额外的 ICE 候选收集加速
                    iceTransportsType = PeerConnection.IceTransportsType.ALL
                }
                peerConnection = factory!!.createPeerConnection(config, pcObserver)

                // 4. 创建抓屏 + VideoTrack,加到 PeerConnection
                createVideoTrack()

                Log.i(TAG, "WebRTC 初始化完成")
            } catch (e: Exception) {
                Log.e(TAG, "WebRTC 初始化失败: ${e.message}", e)
                updateStatus("初始化失败:${e.message}")
            }
        }
    }

    private fun createVideoTrack() {
        // ScreenCapturerAndroid:封装 MediaProjection,自动抓屏
        videoCapturer = ScreenCapturerAndroid(
            mediaProjectionPermissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection 被停止")
                    updateStatus("投屏被系统停止")
                }
            }
        )

        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        // isScreencast=true:让编码器走屏幕共享模式(高分辨率优先,而非摄像头的高帧率优先)
        videoSource = factory!!.createVideoSource(/* isScreencast = */ true)
        videoCapturer!!.initialize(surfaceHelper, context, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(WIDTH, HEIGHT, FPS)

        videoTrack = factory!!.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack!!.setEnabled(true)
        peerConnection!!.addTrack(videoTrack)
        Log.i(TAG, "VideoTrack 已创建并加入 PeerConnection")
    }

    private fun createOffer() {
        executor.execute {
            if (peerConnection == null) {
                Log.w(TAG, "PeerConnection 未就绪,跳过 createOffer")
                return@execute
            }
            // 只发送视频,不接收
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            peerConnection!!.createOffer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peerConnection!!.setLocalDescription(SimpleSdpObserver(), desc)
                    // 发 offer 给浏览器
                    val json = org.json.JSONObject()
                    json.put("type", "offer")
                    val data = org.json.JSONObject()
                    data.put("type", desc.type.canonicalForm())
                    data.put("sdp", desc.description)
                    json.put("data", data)
                    sendToSignaling(json)
                    Log.i(TAG, "已发送 offer")
                    updateStatus("已发起连接,等待浏览器响应…")
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
                override fun onFailure(error: String?) {
                    Log.e(TAG, "createOffer 失败: $error")
                    updateStatus("发起连接失败:$error")
                }
            }, constraints)
        }
    }

    private fun setRemoteAnswer(desc: SessionDescription) {
        peerConnection?.setRemoteDescription(SimpleSdpObserver(), desc)
        Log.i(TAG, "已设置远端 answer,投屏建立中")
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            // 把本地 ICE 候选发给浏览器
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
                PeerConnection.IceConnectionState.CONNECTED -> updateStatus("投屏中")
                PeerConnection.IceConnectionState.DISCONNECTED -> updateStatus("连接断开,尝试恢复…")
                PeerConnection.IceConnectionState.FAILED -> updateStatus("连接失败")
                else -> {}
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
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

    private class SimpleSdpObserver : org.webrtc.SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) {}
    }
}
