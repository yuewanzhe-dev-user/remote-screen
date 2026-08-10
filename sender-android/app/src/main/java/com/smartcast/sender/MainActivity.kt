package com.smartcast.sender

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Step1 主界面:输入黑板 IP,点"开始投屏",授权后启动前台服务推流。
 *
 * 学生/老师打开 app → 输黑板 IP(Step2 改 mDNS 自动发现)→ 点投屏 → 弹一次系统授权
 * → 之后服务持续把屏幕推给黑板。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // 系统投屏授权回调:用户同意后拿到 resultCode/data,传给服务
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCastService(result.resultCode, result.data!!)
            updateUi(casting = true, "投屏中")
        } else {
            updateUi(casting = false, "未授权,无法投屏")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etIp = findViewById(R.id.etBoardIp)
        etPort = findViewById(R.id.etBoardPort)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        btnStart.setOnClickListener {
            val ip = etIp.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull() ?: 8888
            // 暂存目标地址,服务启动时读取
            ScreenCastService.targetHost = ip
            ScreenCastService.targetPort = port
            // 拉起系统投屏授权弹窗(首次必弹,可勾"不再提示")
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            updateUi(casting = false, "已停止")
        }
    }

    private fun startCastService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCastService::class.java).apply {
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
        }
        startForegroundService(intent)
    }

    private fun updateUi(casting: Boolean, status: String) {
        btnStart.isEnabled = !casting
        btnStop.isEnabled = casting
        tvStatus.text = "状态:$status"
    }
}
