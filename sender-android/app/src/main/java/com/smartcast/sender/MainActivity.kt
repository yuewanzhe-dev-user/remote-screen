package com.smartcast.sender

import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面。
 *
 * 工作模式:
 *   1. 自动搜索局域网内的黑板(mDNS),搜到后展示列表,点一下就投屏(零配置)
 *   2. 如果自动发现没找到,保留手输 IP 兜底(并记住上次输入)
 *
 * 状态显示:连接中 / 投屏中 / 断线重连中 / 已停止,实时反馈给用户。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var lvBoards: ListView
    private lateinit var tvBoardsHint: TextView

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private lateinit var prefs: SharedPreferences
    private var discovery: BoardDiscovery? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCastService(result.resultCode, result.data!!)
        } else {
            updateUi(casting = false, "未授权,无法投屏")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("smartcast", MODE_PRIVATE)

        etIp = findViewById(R.id.etBoardIp)
        etPort = findViewById(R.id.etBoardPort)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        lvBoards = findViewById(R.id.lvBoards)
        tvBoardsHint = findViewById(R.id.tvBoardsHint)

        // 恢复上次输入的 IP
        etIp.setText(prefs.getString("host", ""))
        etPort.setText(prefs.getInt("port", 8888).toString())

        // 手动投屏按钮
        btnStart.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull() ?: 8888
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入黑板 IP,或等待自动发现", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 保存配置
            prefs.edit().putString("host", ip).putInt("port", port).apply()
            ScreenCastService.targetHost = ip
            ScreenCastService.targetPort = port
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        // 停止按钮
        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            updateUi(casting = false, "已停止")
        }

        // 开始自动发现黑板
        startBoardDiscovery()
    }

    /** 启动 mDNS 搜索,搜到的黑板列出来给用户点选 */
    private fun startBoardDiscovery() {
        tvBoardsHint.text = "正在搜索本教室黑板…"
        discovery = BoardDiscovery(this)
        discovery?.startDiscovery { boards ->
            mainHandler.post {
                tvBoardsHint.text =
                    if (boards.isEmpty()) "未发现黑板,可手输 IP"
                    else "发现 ${boards.size} 块黑板,点击投屏:"
                val names = boards.map { "${it.name} (${it.host})" }
                lvBoards.adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_1, names
                )
                lvBoards.setOnItemClickListener { _, _, position, _ ->
                    val board = boards[position]
                    // 点选的黑板自动填入并投屏
                    etIp.setText(board.host)
                    etPort.setText(board.port.toString())
                    prefs.edit()
                        .putString("host", board.host)
                        .putInt("port", board.port)
                        .apply()
                    ScreenCastService.targetHost = board.host
                    ScreenCastService.targetPort = board.port
                    captureLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
            }
        }
    }

    private fun startCastService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCastService::class.java).apply {
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCastService.EXTRA_HOST, ScreenCastService.targetHost)
            putExtra(ScreenCastService.EXTRA_PORT, ScreenCastService.targetPort)
        }
        startForegroundService(intent)
        updateUi(casting = true, "投屏启动中")
    }

    private fun updateUi(casting: Boolean, status: String) {
        btnStart.isEnabled = !casting
        btnStop.isEnabled = casting
        tvStatus.text = "状态:$status"
    }

    override fun onResume() {
        super.onResume()
        // 订阅服务的状态变化,实时显示
        ScreenCastService.onStatusChange = { text ->
            mainHandler.post { tvStatus.text = "状态:$text" }
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
