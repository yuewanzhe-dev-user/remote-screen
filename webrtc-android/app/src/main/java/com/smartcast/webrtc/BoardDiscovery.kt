package com.smartcast.webrtc

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
 * 黑板自动发现(UDP 广播)。
 *
 * 黑板(signaling-go)每 2 秒广播:
 *   {"name":"三年二班","port":8080,"room":"default","type":"webrtc"}
 * pad 也可发 {"cmd":"QUERY"} 主动查询。
 *
 * 不用 mDNS:校园 WiFi 常不转发多播。
 */
class BoardDiscovery(private val context: Context) {

    companion object {
        const val DISCOVERY_PORT = 19999
        private const val TAG = "BoardDiscovery"
    }

    data class Board(
        val name: String,
        val host: String,
        val port: Int,
        val room: String,
        /** 发现广播里的 token;空=黑板未启用 token,不影响连接 */
        val token: String,
        val lastSeen: Long
    )

    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private var listenerThread: Thread? = null
    private var onFound: ((List<Board>) -> Unit)? = null
    private val foundBoards = mutableMapOf<String, Board>()
    private val lock = Any()

    fun startDiscovery(onFound: (List<Board>) -> Unit) {
        this.onFound = onFound
        running = true
        listenerThread = thread(name = "board-discovery") {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val multicastLock = wifi.createMulticastLock("smartcast-webrtc").apply {
                    setReferenceCounted(false)
                    acquire()
                }

                socket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 0
                }
                Log.i(TAG, "开始监听 UDP $DISCOVERY_PORT")

                try {
                    val query = """{"cmd":"QUERY"}""".toByteArray()
                    socket!!.send(
                        DatagramPacket(query, query.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                    )
                    Log.i(TAG, "已发送查询广播")
                } catch (e: Exception) {
                    Log.w(TAG, "发送查询失败: ${e.message}")
                }

                thread(name = "board-prune") {
                    while (running) {
                        try { Thread.sleep(3000) } catch (_: Exception) { break }
                        val now = System.currentTimeMillis()
                        val changed = synchronized(lock) {
                            val before = foundBoards.size
                            foundBoards.entries.removeAll { now - it.value.lastSeen > 6000 }
                            foundBoards.size != before
                        }
                        if (changed) notifyFound()
                    }
                }

                val buf = ByteArray(1024)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket?.receive(packet) ?: break
                    } catch (e: SocketException) {
                        if (running) Log.w(TAG, "socket 异常: ${e.message}")
                        break
                    } catch (_: Exception) {
                        continue
                    }
                    val fromIp = packet.address.hostAddress ?: continue
                    if (isOwnAddress(fromIp)) continue
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
            if (!json.has("name")) return
            val name = json.optString("name", "未知黑板")
            val port = json.optInt("port", 8080)
            val room = json.optString("room", "default").ifEmpty { "default" }
            val token = json.optString("token", "")
            val host = json.optString("ip").ifEmpty { fromIp }

            synchronized(lock) {
                val now = System.currentTimeMillis()
                val board = Board(name, host, port, room, token, now)
                val existing = foundBoards[name]
                if (existing != null) {
                    val mySame = sameSegment(host)
                    val exSame = sameSegment(existing.host)
                    when {
                        mySame && exSame -> foundBoards[name] = existing.copy(
                            lastSeen = now, token = token.ifEmpty { existing.token }
                        )
                        mySame && !exSame -> foundBoards[name] = board
                        !mySame && exSame -> foundBoards[name] = existing.copy(
                            lastSeen = now, token = token.ifEmpty { existing.token }
                        )
                        else -> foundBoards[name] = board
                    }
                } else {
                    foundBoards[name] = board
                }
            }
            Log.i(TAG, "发现黑板: $name → $host:$port room=$room token=${token.isNotEmpty()}")
            notifyFound()
        } catch (_: Exception) {
            Log.w(TAG, "解析广播包失败: $text")
        }
    }

    private fun sameSegment(ip: String): Boolean {
        return try {
            val myPrefix = getMyIpPrefix() ?: return false
            myPrefix == ip.substringBeforeLast(".")
        } catch (_: Exception) {
            false
        }
    }

    private fun getMyIpPrefix(): String? {
        return try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in java.util.Collections.list(ni.inetAddresses)) {
                    val pure = (addr.hostAddress ?: continue).substringBefore("%")
                    if (pure.contains(".") && !pure.startsWith("127.") && !pure.startsWith("169.254.")) {
                        return pure.substringBeforeLast(".")
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isOwnAddress(ip: String): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
            for (ni in java.util.Collections.list(interfaces)) {
                for (addr in java.util.Collections.list(ni.inetAddresses)) {
                    val pure = (addr.hostAddress ?: continue).substringBefore("%")
                    if (pure == ip) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun notifyFound() {
        val list = synchronized(lock) { foundBoards.values.toList() }
        onFound?.invoke(list)
    }

    fun stopDiscovery() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { listenerThread?.interrupt() } catch (_: Exception) {}
    }
}
