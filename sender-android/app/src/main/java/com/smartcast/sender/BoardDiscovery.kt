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
        val port: Int,
        val lastSeen: Long // 最后一次收到该黑板广播的时间,用于过期清理
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

                // 主动发一次查询,让黑板立刻回应(不用等它的下一次广播)。
                // 注意:查询必须从这个监听 socket 发,黑板才会单播回应到 19999 端口;
                // 之前用临时端口发,回应会丢到已关闭的端口上,导致主动查询永远收不到。
                try {
                    val query = """{"cmd":"QUERY"}""".toByteArray()
                    socket!!.send(
                        DatagramPacket(query, query.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT)
                    )
                    Log.i(TAG, "已发送查询广播")
                } catch (e: Exception) {
                    Log.w(TAG, "发送查询失败: ${e.message}")
                }

                // 黑板离线清理:黑板每 2 秒广播一次,连续 6 秒没收到就认为已下线
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
            // 只处理带 name 字段的包(黑板宣告)。
            // 不带 name 只有 cmd 的包是其他 pad 发的查询,忽略,避免互相误识别成黑板
            if (!json.has("name")) {
                return
            }
            val name = json.optString("name", "未知黑板")
            val port = json.optInt("port", 8888)
            // host 优先用 JSON 里的,兜底用来源 IP
            val host = json.optString("ip").ifEmpty { fromIp }

            // 黑板可能有多个网卡(如 WiFi + 设了静态IP的有线网卡),
            // 每个网卡都会广播一次。这里做筛选:
            //   - 如果收到多个同名黑板,优先保留和自己同网段的
            //   - 不同网段的(如不可达的有线网卡地址)仅在没别的选择时才用
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val board = Board(name, host, port, now)
                val existing = foundBoards[name]
                if (existing != null) {
                    val mySameSegment = sameSegment(host)
                    val existingSameSegment = sameSegment(existing.host)
                    when {
                        // 新的和已知的都同网段,保留先到的(稳定),但要刷新 lastSeen
                        mySameSegment && existingSameSegment -> foundBoards[name] = existing.copy(lastSeen = now)
                        // 新的同网段,旧的不同网段,用新的覆盖
                        mySameSegment && !existingSameSegment -> foundBoards[name] = board
                        // 新的不同网段,旧的同网段,保留旧的,但要刷新 lastSeen
                        !mySameSegment && existingSameSegment -> foundBoards[name] = existing.copy(lastSeen = now)
                        // 两个都不同网段,用新的覆盖(随便选一个)
                        else -> foundBoards[name] = board
                    }
                } else {
                    foundBoards[name] = board
                }
            }
            Log.i(TAG, "发现黑板: $name → $host:$port (同网段:${sameSegment(host)})")
            notifyFound()
        } catch (e: Exception) {
            Log.w(TAG, "解析广播包失败: $text")
        }
    }

    /** 判断给定 IP 是否和 pad 本机在同一网段(前 3 段相同,适用于 /24 小网) */
    private fun sameSegment(ip: String): Boolean {
        return try {
            val myPrefix = getMyIpPrefix() ?: return false
            val targetPrefix = ip.substringBeforeLast(".")
            myPrefix == targetPrefix
        } catch (e: Exception) {
            false
        }
    }

    /** 获取 pad 本机 IP 的前 3 段(如 192.168.124) */
    private fun getMyIpPrefix(): String? {
        return try {
            val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = java.util.Collections.list(ni.inetAddresses)
                for (addr in addrs) {
                    val hostAddr = addr.hostAddress ?: continue
                    val pure = hostAddr.substringBefore("%")
                    // 只看 IPv4(含点)
                    if (pure.contains(".") && !pure.startsWith("127.") && !pure.startsWith("169.254.")) {
                        return pure.substringBeforeLast(".")
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 判断某个 IP 是否是本机地址(用于过滤自己发的广播回环包) */
    private fun isOwnAddress(ip: String): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return false
            // Enumeration 不能用 for-in,用 Collections.list 转成 List
            val list = java.util.Collections.list(interfaces)
            for (ni in list) {
                val addrs = java.util.Collections.list(ni.inetAddresses)
                for (addr in addrs) {
                    val hostAddr = addr.hostAddress ?: continue
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

    fun stopDiscovery() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { listenerThread?.interrupt() } catch (_: Exception) {}
    }
}
