package com.oppocamera.unlock.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oppocamera.unlock.Link
import com.oppocamera.unlock.Shell
import com.oppocamera.unlock.pui.PuiFloatingNavBar
import com.oppocamera.unlock.pui.PuiIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 两页：首页 / 设置。底栏是 PUI 的悬浮胶囊，配色跟随系统壁纸（莫奈）。
 */
@Composable
fun Root() {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var root by remember { mutableStateOf(Shell.rootGranted) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) {
        if (root == null) root = withContext(Dispatchers.IO) { Shell.checkRoot() }
        while (true) {
            now = System.currentTimeMillis()
            delay(1500)
        }
    }

    fun restart() {
        if (busy) return
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Shell.restartCamera() }
            busy = false
            if (r.ok) {
                Toast.makeText(ctx, "已重启相机", Toast.LENGTH_SHORT).show()
            } else {
                Shell.openAppInfo(ctx)
                Toast.makeText(ctx, "没有 root，已打开相机应用信息页", Toast.LENGTH_LONG).show()
            }
            now = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().padding(bottom = 92.dp + navBottom)) {
            when (tab) {
                0 -> HomePage(
                    connected = Link.service != null,
                    scoped = Link.scope.contains(Link.TARGET),
                    root = root == true,
                    busy = busy,
                    onRestart = ::restart
                )
                else -> SettingsPage(
                    connected = Link.service != null,
                    scoped = Link.scope.contains(Link.TARGET),
                    root = root == true
                )
            }
        }

        PuiFloatingNavBar(
            items = listOf("首页", "设置"),
            selectedIndex = tab,
            onSelect = { tab = it },
            icons = listOf(PuiIcon.Home, PuiIcon.Element),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBottom + 18.dp)
        )
    }
}
