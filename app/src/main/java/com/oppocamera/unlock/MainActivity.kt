package com.oppocamera.unlock

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.oppocamera.unlock.pui.PuiTheme
import com.oppocamera.unlock.ui.Root

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 调试/自动化入口：`am start -n com.oppocamera.unlock/.MainActivity -e export 1`
        // 直接把 adapt.log 导出来（不弹界面也能用），结果同时进 logcat（tag CamUnlockExport）。
        handleExportIntent(intent?.getStringExtra("export"))

        setContent {
            // 跟着系统壁纸取色（莫奈），图标也一样用单色层
            PuiTheme(dynamicAccent = true) {
                Root()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleExportIntent(intent.getStringExtra("export"))
    }

    private fun handleExportIntent(flag: String?) {
        if (flag.isNullOrEmpty()) return
        Thread {
            val r = Shell.exportAdaptLog(this)
            Log.i("CamUnlockExport", "ok=${r.ok} path=${r.out}")
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (r.ok) "已导出：${r.out}" else "导出失败：${r.out}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
