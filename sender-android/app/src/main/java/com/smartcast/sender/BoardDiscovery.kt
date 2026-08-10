package com.smartcast.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * 黑板自动发现(mDNS / DNS-SD)。
 *
 * 黑板端会在局域网内广播自身服务,pad 用本类自动搜索,无需手输 IP。
 *
 * 服务类型约定:_smartboard._tcp.
 * 实例名 = 教室名/黑板名(如"三年二班黑板"),pad 端可展示列表让用户选,
 * 或只有一个黑板时自动连接。
 *
 * 原理:AirPlay、Chromecast 等零配置设备都走这套。黑板 IP 怎么变都能自动跟上。
 */
class BoardDiscovery(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_smartboard._tcp."
        private const val TAG = "BoardDiscovery"
    }

    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var listener: ((List<Board>) -> Unit)? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val foundBoards = mutableMapOf<String, Board>()

    data class Board(
        val name: String,        // 实例名,如"三年二班黑板"
        val host: String,        // IP 地址
        val port: Int            // 端口
    )

    /**
     * 开始搜索黑板。每发现/更新一个黑板就回调一次完整列表。
     * @param onFound 收到黑板列表(去重)的回调
     */
    fun startDiscovery(onFound: (List<Board>) -> Unit) {
        listener = onFound
        foundBoards.clear()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "启动发现失败: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "停止发现失败: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "开始搜索黑板 ($serviceType)")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i(TAG, "停止搜索黑板")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                Log.i(TAG, "发现服务: ${info.serviceName}")
                // 解析出 IP 和端口
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        val board = Board(info.serviceName, host, info.port)
                        foundBoards[info.serviceName] = board
                        Log.i(TAG, "解析成功: ${board.name} → $host:${board.port}")
                        listener?.invoke(foundBoards.values.toList())
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "解析失败: ${info.serviceName} ($errorCode)")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                Log.i(TAG, "服务消失: ${info.serviceName}")
                foundBoards.remove(info.serviceName)
                listener?.invoke(foundBoards.values.toList())
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /** 停止搜索 */
    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "停止发现异常: ${e.message}")
        }
        discoveryListener = null
    }
}
