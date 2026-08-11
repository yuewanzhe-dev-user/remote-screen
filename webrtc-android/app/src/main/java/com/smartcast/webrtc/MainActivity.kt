package com.smartcast.webrtc

import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray

/**
 * 主界面:自动发现黑板 → 点一下投屏。
 * - 历史投过的黑板置顶
 * - 列表展示教室名(串班区分)
 * - 发现带 token 时自动携带(默认无 token,不影响一键)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etRoom: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var lvBoards: ListView
    private lateinit var tvBoardsHint: TextView
    private lateinit var layoutAdvanced: LinearLayout
    private lateinit var tvAdvancedToggle: TextView
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var discovery: BoardDiscovery? = null
    private var lastBoardNames: List<String>? = null
    private var latestBoards: List<BoardDiscovery.Board> = emptyList()
    private var discoveryStartedAt = 0L
    private val isolationHintRunnable = Runnable { showIsolationHintIfNeeded() }

    private var pendingServer: String = ""
    private var pendingRoom: String = "default"
    private var pendingToken: String = ""
    private var pendingBoardName: String = ""

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCastService(result.resultCode, result.data!!)
        } else {
            updateUi(false, "未授权,无法投屏")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("smartcast_webrtc", MODE_PRIVATE)
        etServer = findViewById(R.id.etServer)
        etRoom = findViewById(R.id.etRoom)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        lvBoards = findViewById(R.id.lvBoards)
        tvBoardsHint = findViewById(R.id.tvBoardsHint)
        layoutAdvanced = findViewById(R.id.layoutAdvanced)
        tvAdvancedToggle = findViewById(R.id.tvAdvancedToggle)

        etServer.setText(prefs.getString("server", ""))
        etRoom.setText(prefs.getString("room", "default"))

        tvAdvancedToggle.setOnClickListener {
            val show = layoutAdvanced.visibility != View.VISIBLE
            layoutAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            tvAdvancedToggle.text = if (show) "高级设置 ▾" else "高级设置 ▸"
        }

        btnStart.setOnClickListener {
            val server = etServer.text.toString().trim()
            val room = etRoom.text.toString().trim().ifEmpty { "default" }
            if (server.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址,或点上方黑板列表", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingServer = server
            pendingRoom = room
            pendingToken = prefs.getString("token", "") ?: ""
            pendingBoardName = prefs.getString("last_board_name", "") ?: ""
            prefs.edit().putString("server", server).putString("room", room).apply()
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            updateUi(false, "已停止")
        }

        startBoardDiscovery()
    }

    private fun startBoardDiscovery() {
        tvBoardsHint.text = "正在搜索本教室黑板…"
        discoveryStartedAt = System.currentTimeMillis()
        mainHandler.removeCallbacks(isolationHintRunnable)
        mainHandler.postDelayed(isolationHintRunnable, 8_000)
        mainHandler.postDelayed(isolationHintRunnable, 20_000)

        discovery = BoardDiscovery(this)
        discovery?.startDiscovery { boards ->
            mainHandler.post {
                val sorted = sortBoards(boards)
                latestBoards = sorted
                val names = sorted.map { formatBoardLine(it) }
                if (names == lastBoardNames) return@post
                lastBoardNames = names
                if (sorted.isNotEmpty()) {
                    mainHandler.removeCallbacks(isolationHintRunnable)
                    tvBoardsHint.text = "发现 ${sorted.size} 块黑板,点击即可投屏(★=本机投过):"
                } else {
                    val waited = System.currentTimeMillis() - discoveryStartedAt
                    tvBoardsHint.text = if (waited >= 8_000) isolationHintText() else "正在搜索本教室黑板…"
                }
                lvBoards.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_1,
                    names
                )
                lvBoards.setOnItemClickListener { _, _, position, _ ->
                    val board = latestBoards.getOrNull(position) ?: return@setOnItemClickListener
                    pendingServer = "${board.host}:${board.port}"
                    pendingRoom = board.room.ifEmpty { "default" }
                    pendingToken = board.token
                    pendingBoardName = board.name
                    etServer.setText(pendingServer)
                    etRoom.setText(pendingRoom)
                    prefs.edit()
                        .putString("server", pendingServer)
                        .putString("room", pendingRoom)
                        .putString("token", pendingToken)
                        .putString("last_board_name", board.name)
                        .apply()
                    captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }
    }

    /** 历史黑板置顶 + 同网段优先 */
    private fun sortBoards(boards: List<BoardDiscovery.Board>): List<BoardDiscovery.Board> {
        val history = loadHistoryNames()
        return boards.sortedWith(
            compareByDescending<BoardDiscovery.Board> { history.indexOf(it.name).let { i -> if (i >= 0) 1000 - i else -1 } }
                .thenBy { it.name }
        )
    }

    private fun formatBoardLine(b: BoardDiscovery.Board): String {
        val hist = loadHistoryNames()
        val star = if (hist.contains(b.name)) "★ " else ""
        // 串班区分:名称在前(大字视觉靠排序+★),IP 在后
        return "$star${b.name}  ·  ${b.host}:${b.port}"
    }

    private fun loadHistoryNames(): List<String> {
        return try {
            val raw = prefs.getString("board_history", "[]") ?: "[]"
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun rememberBoard(name: String) {
        if (name.isBlank()) return
        val list = loadHistoryNames().toMutableList()
        list.remove(name)
        list.add(0, name)
        while (list.size > 8) list.removeAt(list.lastIndex)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("board_history", arr.toString()).apply()
    }

    private fun isolationHintText(): String {
        return "未发现黑板。请确认：\n" +
            "1) pad 与黑板连同一教室 WiFi\n" +
            "2) 黑板上「智慧投屏」exe 已运行\n" +
            "3) 若仍搜不到,多为 WiFi「客户端隔离」,请网管关闭或展开下方高级设置手输 IP"
    }

    private fun showIsolationHintIfNeeded() {
        if (latestBoards.isNotEmpty()) return
        tvBoardsHint.text = isolationHintText()
        if (!btnStop.isEnabled) {
            tvStatus.text = "状态:未发现黑板,检查同网/客户端隔离"
        }
    }

    private fun startCastService(resultCode: Int, data: Intent) {
        val server = pendingServer.ifEmpty { etServer.text.toString().trim() }
        val room = pendingRoom.ifEmpty { etRoom.text.toString().trim().ifEmpty { "default" } }
        val token = pendingToken
        if (server.isEmpty()) {
            Toast.makeText(this, "未选择黑板", Toast.LENGTH_SHORT).show()
            return
        }
        if (pendingBoardName.isNotEmpty()) rememberBoard(pendingBoardName)

        val intent = Intent(this, ScreenCastService::class.java).apply {
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCastService.EXTRA_SERVER, server)
            putExtra(ScreenCastService.EXTRA_ROOM, room)
            putExtra(ScreenCastService.EXTRA_TOKEN, token)
        }
        startForegroundService(intent)
        updateUi(true, "启动中…")
    }

    private fun updateUi(casting: Boolean, status: String) {
        btnStart.isEnabled = !casting
        btnStop.isEnabled = casting
        tvStatus.text = "状态:$status"
    }

    override fun onResume() {
        super.onResume()
        ScreenCastService.onStatusChange = { status ->
            mainHandler.post {
                tvStatus.text = "状态:$status"
                val stopped = status == "未连接" || status == "已停止" ||
                    status.startsWith("已停止") || status.startsWith("未授权") ||
                    status.startsWith("启动失败") || status.contains("已被其他") ||
                    status.contains("教师已断开")
                btnStart.isEnabled = stopped
                btnStop.isEnabled = !stopped
            }
        }
        tvStatus.text = "状态:${ScreenCastService.statusText}"
    }

    override fun onPause() {
        super.onPause()
        ScreenCastService.onStatusChange = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(isolationHintRunnable)
        discovery?.stopDiscovery()
        super.onDestroy()
    }
}
