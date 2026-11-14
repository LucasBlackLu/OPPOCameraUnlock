package com.oppocamera.unlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.oppocamera.unlock.pui.PuiTheme
import com.oppocamera.unlock.ui.Root

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 跟着系统壁纸取色（莫奈），图标也一样用单色层
            PuiTheme(dynamicAccent = true) {
                Root()
            }
        }
    }
}
