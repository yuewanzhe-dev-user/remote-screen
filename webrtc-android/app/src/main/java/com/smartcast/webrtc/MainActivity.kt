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

/**
 * 主界面:自动发现黑板 → 点一下投屏。
 * 高级设置里可手输 IP/房间(搜不到时兜底)。
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

    // 即将投屏的目标(发现点选 或 手输)
    private var pendingServer: String = ""
    private var pendingRoom: String = "default"

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
        discovery = BoardDiscovery(this)
        discovery?.startDiscovery { boards ->
            mainHandler.post {
                latestBoards = boards
                val names = boards.map { "${it.name}  (${it.host}:${it.port})" }
                if (names == lastBoardNames) return@post
                lastBoardNames = names
                tvBoardsHint.text = when {
                    boards.isEmpty() -> "未发现黑板,可展开下方「高级设置」手输"
                    else -> "发现 ${boards.size} 块黑板,点击即可投屏:"
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
                    etServer.setText(pendingServer)
                    etRoom.setText(pendingRoom)
                    prefs.edit()
                        .putString("server", pendingServer)
                        .putString("room", pendingRoom)
                        .apply()
                    captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }
    }

    private fun startCastService(resultCode: Int, data: Intent) {
        val server = pendingServer.ifEmpty { etServer.text.toString().trim() }
        val room = pendingRoom.ifEmpty { etRoom.text.toString().trim().ifEmpty { "default" } }
        if (server.isEmpty()) {
            Toast.makeText(this, "未选择黑板", Toast.LENGTH_SHORT).show()
            return
        }
        // 直接 start;Service.onStartCommand 里会先 stop 旧 RtcClient
        val intent = Intent(this, ScreenCastService::class.java).apply {
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCastService.EXTRA_SERVER, server)
            putExtra(ScreenCastService.EXTRA_ROOM, room)
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
                    status.startsWith("启动失败") || status.contains("已被其他")
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
        discovery?.stopDiscovery()
        super.onDestroy()
    }
}
