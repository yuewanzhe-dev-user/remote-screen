package com.smartcast.webrtc

import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面:输入信令服务器地址 + 房间号 → 请求投屏授权 → 启动服务。
 *
 * 房间号 = 黑板编号,和浏览器端 ?room=XXX 保持一致。
 * 服务器地址 = 跑信令服务器的机器(如黑板的 IP:8080,或独立服务器)。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etServer: EditText
    private lateinit var etRoom: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // 系统投屏授权回调
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

        // 恢复上次输入
        etServer.setText(prefs.getString("server", ""))
        etRoom.setText(prefs.getString("room", ""))

        btnStart.setOnClickListener {
            val server = etServer.text.toString().trim()
            val room = etRoom.text.toString().trim()
            if (server.isEmpty() || room.isEmpty()) {
                Toast.makeText(this, "请填写服务器地址和房间号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 保存配置
            prefs.edit().putString("server", server).putString("room", room).apply()
            // 拉起系统投屏授权
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            updateUi(false, "已停止")
        }
    }

    private fun startCastService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCastService::class.java).apply {
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCastService.EXTRA_SERVER, etServer.text.toString().trim())
            putExtra(ScreenCastService.EXTRA_ROOM, etRoom.text.toString().trim())
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
        // 订阅服务状态变化
        ScreenCastService.onStatusChange = { status ->
            mainHandler.post {
                tvStatus.text = "状态:$status"
                // 投屏中状态切换按钮可用性
                val casting = !listOf("未连接", "已停止", "未授权,无法投屏", "启动失败").contains(status)
                        && status.startsWith("已停止").not()
                btnStart.isEnabled = !casting
                btnStop.isEnabled = casting
            }
        }
        tvStatus.text = "状态:${ScreenCastService.statusText}"
    }

    override fun onPause() {
        super.onPause()
        ScreenCastService.onStatusChange = null
    }
}
