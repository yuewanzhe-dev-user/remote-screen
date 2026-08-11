package com.smartcast.webrtc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 投屏前台服务。
 *
 * 为什么必须用前台服务:
 *   - Android 10+ 起,MediaProjection 必须在前台服务(mediaProjection 类型)里运行,
 *     否则系统直接 kill 进程
 *   - 前台服务 + 持久通知,让学生清楚知道"正在投屏"
 *
 * 保活:
 *   - WakeLock(PARTIAL_WAKE_LOCK):CPU 不睡
 *   - WifiLock(HIGH_PERF):WiFi 不进省电模式,防止熄屏后断流
 *   - 这两个锁组合是投屏/直播类 app 的标准做法
 */
class ScreenCastService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER = "server" // 信令服务器 host:port
        const val EXTRA_ROOM = "room"     // 房间号
        const val EXTRA_TOKEN = "token"   // 可选,默认空
        const val EXTRA_QUALITY = "quality" // 720 / 1080 / native

        @Volatile var statusText: String = "未连接"
        @Volatile var onStatusChange: ((String) -> Unit)? = null

        private const val TAG = "ScreenCastService"
        private const val CHANNEL_ID = "smartcast_webrtc_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var rtcClient: RtcClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent = intent?.getParcelableExtra(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
        val server = intent.getStringExtra(EXTRA_SERVER) ?: return START_NOT_STICKY
        val room = intent.getStringExtra(EXTRA_ROOM) ?: return START_NOT_STICKY
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
        val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "balanced"

        startForeground()

        // 获取保活锁
        acquireLocks()

        // 先停掉旧客户端,避免重复点投屏叠多个连接
        try { rtcClient?.stop() } catch (_: Exception) {}
        rtcClient = null

        // 启动 RtcClient(在主线程构造,内部自己切线程)
        try {
            statusText = "启动中…"
            rtcClient = RtcClient(this, data, server, room, token, quality) { status ->
                statusText = status
                onStatusChange?.invoke(status)
                // 被顶替/教师断开后自动结束前台服务
                if (status.contains("已被其他") || status.contains("投屏被系统停止") ||
                    status.contains("教师已断开")
                ) {
                    stopSelf()
                }
            }
            rtcClient?.start()
        } catch (e: Exception) {
            Log.e(TAG, "启动投屏失败: ${e.message}", e)
            statusText = "启动失败:${e.message}"
            stopSelf()
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

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smartcast:webrtc").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifi = getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "smartcast:webrtc"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取保活锁失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { rtcClient?.stop() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        statusText = "已停止"
        onStatusChange?.invoke("已停止")
        super.onDestroy()
    }
}
