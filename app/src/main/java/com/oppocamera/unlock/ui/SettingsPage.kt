package com.oppocamera.unlock.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oppocamera.unlock.Const
import com.oppocamera.unlock.Shell
import com.oppocamera.unlock.pui.PuiGroup
import com.oppocamera.unlock.pui.PuiIcon
import com.oppocamera.unlock.pui.PuiListRow
import com.oppocamera.unlock.pui.PuiPageTitle
import com.oppocamera.unlock.pui.PuiSectionHeader
import com.oppocamera.unlock.pui.rememberStatusBarHeight

/** 设置页：快捷入口 + 说明。 */
@Composable
fun SettingsPage(connected: Boolean, scoped: Boolean, root: Boolean) {
    val ctx = LocalContext.current
    val top = rememberStatusBarHeight()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 16.dp, bottom = 24.dp)
    ) {
        item {
            PuiPageTitle("设置")
            Spacer(Modifier.height(14.dp))
        }

        item {
            PuiSectionHeader("快捷入口")
        }

        item {
            PuiGroup(null) {
                PuiListRow(
                    icon = PuiIcon.Lock,
                    title = "打开 LSPosed",
                    subtitle = "检查模块是否启用、作用域是否勾选",
                    showChevron = true,
                    showDivider = false,
                    onClick = { Shell.openLsposed(ctx) }
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
                    subtitle = if (connected) "LSPosed 服务已连接" else "未连接",
                    trailingText = if (connected) "已启用" else "未启用"
                )
                PuiListRow(
                    icon = PuiIcon.Lock,
                    title = "作用域",
                    subtitle = "com.oplus.camera",
                    trailingText = if (scoped) "已勾选" else "缺失"
                )
                PuiListRow(
                    icon = PuiIcon.Lock,
                    title = "Root 权限",
                    subtitle = if (root) "已授权，可一键重启" else "未授权，手动强制停止即可",
                    trailingText = if (root) "已授权" else "未授权",
                    showDivider = false
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("支持作者")
        }

        item {
            PuiGroup(null) {
                PuiListRow(
                    icon = PuiIcon.Star,
                    title = "GitHub",
                    subtitle = "喜欢我的工作就点个 Star",
                    showChevron = true,
                    showDivider = false,
                    onClick = { Shell.openUrl(ctx, Const.GITHUB) }
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("致谢")
        }

        item {
            PuiGroup(null) {
                PuiListRow(
                    icon = PuiIcon.Library,
                    title = "libxposed API",
                    subtitle = "模块框架（API 101）",
                    showChevron = true,
                    onClick = { Shell.openUrl(ctx, Const.LIBXPOSED) }
                )
                PuiListRow(
                    icon = PuiIcon.Palette,
                    title = "PUI 设计系统",
                    subtitle = "界面骨架 · 作者 天伞桜",
                    showChevron = true,
                    onClick = { Shell.openUrl(ctx, Const.PUI) }
                )
                PuiListRow(
                    icon = PuiIcon.Heart,
                    title = "天伞桜",
                    subtitle = "PUI 作者 · coolapk.com/u/540690",
                    showChevron = true,
                    onClick = { Shell.openUrl(ctx, Const.PUI_AUTHOR_COOLAPK) }
                )
                PuiListRow(
                    icon = PuiIcon.Info,
                    title = "灵感构图",
                    subtitle = "com.linggan.goutu · 作者 KaMuaMua2020",
                    showChevron = true,
                    onClick = { Shell.openUrl(ctx, Const.LINGGAN_AUTHOR) }
                )
                PuiListRow(
                    icon = PuiIcon.User,
                    title = "KaMuaMua2020",
                    subtitle = "灵感构图作者 · coolapk.com/u/26800889",
                    showChevron = true,
                    showDivider = false,
                    onClick = { Shell.openUrl(ctx, Const.LINGGAN_AUTHOR_COOLAPK) }
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("系统")
        }

        item {
            PuiGroup(null) {
                PuiListRow(
                    icon = PuiIcon.Info,
                    title = "关于",
                    subtitle = Const.VERSION,
                    trailingText = "检查更新",
                    showChevron = true,
                    showDivider = false,
                    onClick = { Shell.openUrl(ctx, Const.RELEASES) }
                )
            }
        }
    }
}