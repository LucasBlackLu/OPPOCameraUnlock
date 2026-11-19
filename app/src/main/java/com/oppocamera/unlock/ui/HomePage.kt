package com.oppocamera.unlock.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oppocamera.unlock.Link
import com.oppocamera.unlock.Shell
import com.oppocamera.unlock.pui.PuiButton
import com.oppocamera.unlock.pui.PuiButtonStyle
import com.oppocamera.unlock.pui.PuiGroup
import com.oppocamera.unlock.pui.PuiHeroCard
import com.oppocamera.unlock.pui.PuiIcon
import com.oppocamera.unlock.pui.PuiListRow
import com.oppocamera.unlock.pui.PuiNoticeCard
import com.oppocamera.unlock.pui.PuiPageTitle
import com.oppocamera.unlock.pui.PuiSectionHeader
import com.oppocamera.unlock.pui.rememberStatusBarHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class State(
    val connected: Boolean,
    val scoped: Boolean,
    val root: Boolean,
    val cameraVersion: String?
)

/**
 * 一屏：状态卡 + 两个功能入口 + 运行状态。
 *
 * 功能只有两件事 —— 调色盘（部署 LUT + 重定向路径）、灵感构图（放行 AI 构图那组 feature）。
 */
@Composable
fun HomePage(
    connected: Boolean,
    scoped: Boolean,
    root: Boolean,
    busy: Boolean,
    onRestart: () -> Unit
) {
    val ctx = LocalContext.current
    val top = rememberStatusBarHeight()
    val scope = rememberCoroutineScope()

    val state = State(connected, scoped, root, versionOf(ctx))
    val ready = connected && scoped
    val restart = onRestart

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 16.dp, bottom = 24.dp)
    ) {
        item {
            PuiPageTitle("OPPO 相机解锁")
            Spacer(Modifier.height(14.dp))
        }

        item {
            PuiHeroCard(
                title = if (ready) "已解锁" else "待启用",
                subtitle = when {
                    !state.connected -> "LSPosed 里还没有启用这个模块"
                    !state.scoped -> "作用域里缺少「相机」"
                    else -> "调色盘 · 灵感构图"
                },
                badge = if (ready) "已启用" else "待启用",
                icon = if (ready) PuiIcon.Check else PuiIcon.Warning,
                onClick = onRestart
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
            PuiButton(
                text = if (busy) "正在重启…" else "重启相机",
                onClick = onRestart,
                enabled = !busy,
                icon = PuiIcon.Play,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            PuiButton(
                text = "打开相机",
                onClick = { Shell.openCamera(ctx) },
                icon = PuiIcon.ChevronRight,
                style = PuiButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.root) "已授权 root：强停并重新打开，一步到位"
                else "没有 root 时会打开应用信息页，点「强制停止」",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("功能")
        }

        item {
            PuiGroup(null) {
                PuiListRow(
                    icon = PuiIcon.Palette,
                    title = "调色盘",
                    subtitle = "照片 → 左下角橙色方块 → 二维调色板"
                )
                PuiListRow(
                    icon = PuiIcon.PaletteRound,
                    title = "灵感构图",
                    subtitle = "照片 → 右侧辅助构图 → 灵感构图 / 灵感推荐",
                    showDivider = false
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("运行状态")
        }

        item {
            PuiGroup(null) {
                PuiListRow(
                    icon = PuiIcon.Check,
                    title = "模块",
                    subtitle = if (state.connected) "LSPosed 服务已连接 · API ${Link.service?.apiVersion ?: 101}"
                    else "未连接，先去 LSPosed 启用并勾选相机",
                    trailingText = if (state.connected) "已启用" else "未启用",
                    showChevron = !state.connected,
                    onClick = if (state.connected) null else ({ Shell.openLsposed(ctx) })
                )
                PuiListRow(
                    icon = PuiIcon.Lock,
                    title = "作用域",
                    subtitle = "com.oplus.camera",
                    trailingText = if (state.scoped) "已勾选" else "缺失"
                )
                PuiListRow(
                    icon = PuiIcon.Lock,
                    title = "Root 权限",
                    subtitle = if (state.root) "已授权，可一键重启" else "未授权，手动强制停止即可",
                    trailingText = if (state.root) "已授权" else "未授权"
                )
                PuiListRow(
                    icon = PuiIcon.Phone,
                    title = "相机",
                    subtitle = state.cameraVersion?.let { "版本 $it" } ?: "未安装",
                    showDivider = false
                )
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            PuiNoticeCard(
                title = "怎么用",
                text = "重启相机 → 照片左下角橙色方块打开调色盘；灵感构图在相机的模式/更多里。",
                icon = PuiIcon.Info
            )
        }
    }
}

private fun versionOf(ctx: Context): String? = runCatching {
    ctx.packageManager.getPackageInfo(Link.TARGET, 0).versionName
}.getOrNull()
