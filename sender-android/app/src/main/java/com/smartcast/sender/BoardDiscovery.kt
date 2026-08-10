package com.smartcast.sender

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * 黑板自动发现(UDP 广播方案)。
 *
 * 为什么不用 mDNS:很多校园 WiFi AP 不转发 mDNS 多播包(224.0.0.251),
 * 导致 pad 搜不到黑板。改用 UDP 广播(255.255.255.255),几乎所有 AP 都转发。
 *
 * 工作方式:
 *   1. 被动监听:后台监听 UDP 19999,黑板每 2 秒广播一次,收到即发现
 *   2. 主动查询:启动时发一次 {"cmd":"QUERY"} 广播,黑板会单播回应
 *
 * 黑板广播的消息格式(JSON):
 *   {"name":"智慧黑板","port":8888,"ip":"192.168.x.x"}
 */
class BoardDiscovery(private val context: Context) {

    companion object {
        const val DISCOVERY_PORT = 19999
        private const val TAG = "BoardDiscovery"
    }

    data class Board(
        val name: String,
        val host: String,
        val port: Int
    )

    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var listenerThread: Thread? = null
    private var onFound: ((List<Board>) -> Unit)? = null
    // 发现到的黑板,name 去重
    private val foundBoards = mutableMapOf<String, Board>()
    private val lock = Any()

    /**
     * 开始发现黑板。首次会主动发一次查询,之后持续监听黑板的周期广播。
     */
    fun startDiscovery(onFound: (List<Board>) -> Unit) {
        this.onFound = onFound
        running = true
        listenerThread = thread(name = "board-discovery") {
            try {
                // 拿 WiFi 多播锁(广播也需要)
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val multicastLock = wifi.createMulticastLock("smartcast").apply {
                    setReferenceCounted(false)
                    acquire()
                }

                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 0 // 一直阻塞读
                }
                Log.i(TAG, "开始监听 UDP $DISCOVERY_PORT")

                // 主动发一次查询,让黑板立刻回应(不用等它的下一次广播)
                sendQuery()

                val buf = ByteArray(1024)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket?.receive(packet) ?: break
                    } catch (e: SocketException) {
                        if (running) Log.w(TAG, "socket 异常: ${e.message}")
                        break
                    } catch (e: Exception) {
                        continue
                    }
                    val fromIp = packet.address.hostAddress ?: continue
                    // 过滤掉自己发的包:pad 自己发出的查询广播会回环到自己,
                    // 不过滤的话会把自己当成黑板解析,出现"未知黑板"假项
                    if (isOwnAddress(fromIp)) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    handlePacket(text, fromIp)
                }
                try { multicastLock.release() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "发现服务异常: ${e.message}", e)
            }
        }
    }

    private fun handlePacket(text: String, fromIp: String) {
        try {
            val json = JSONObject(text)
            val name = json.optString("name", "未知黑板")
            val port = json.optInt("port", 8888)
            // host 优先用 JSON 里的,兜底用来源 IP
            val host = json.optString("ip").ifEmpty { fromIp }
            val board = Board(name, host, port)
            synchronized(lock) {
                foundBoards[name] = board
            }
            Log.i(TAG, "发现黑板: $name → $host:$port")
            notifyFound()
        } catch (e: Exception) {
            Log.w(TAG, "解析广播包失败: $text")
        }
    }

    /** 判断某个 IP 是否是本机地址(用于过滤自己发的广播回环包) */
    private fun isOwnAddress(ip: String): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            interfaces?.forEach { ni ->
                ni.inetAddresses?.forEach { addr ->
                    val hostAddr = addr.hostAddress ?: return@forEach
                    // 去掉 IPv6 的 scope id(如 fe80::1%wlan0)
                    val pure = hostAddr.substringBefore("%")
                    if (pure == ip) return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun notifyFound() {
        val list = synchronized(lock) { foundBoards.values.toList() }
        onFound?.invoke(list)
    }

    /** 主动发一次查询广播,让黑板立即回应 */
    private fun sendQuery() {
        try {
            thread(name = "board-query") {
                val s = DatagramSocket().apply { broadcast = true }
                val data = """{"cmd":"QUERY"}""".toByteArray()
                val packet = DatagramPacket(
                    data, data.size,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT
                )
                s.send(packet)
                s.close()
                Log.i(TAG, "已发送查询广播")
            }
        } catch (e: Exception) {
            Log.w(TAG, "发送查询失败: ${e.message}")
        }
    }

    fun stopDiscovery() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { listenerThread?.interrupt() } catch (_: Exception) {}
    }
}
