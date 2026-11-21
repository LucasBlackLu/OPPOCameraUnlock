package com.oppocamera.unlock.ui

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.oppocamera.unlock.pui.PuiSwitchRow
import com.oppocamera.unlock.pui.rememberStatusBarHeight
import kotlin.concurrent.thread

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
                    subtitle = "界面骨架",
                    showChevron = true,
                    showDivider = false,
                    onClick = { Shell.openUrl(ctx, Const.PUI) }
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("滤镜开关")
        }

        item {
            // 用系统属性控制（模块在相机进程里读 persist.camunlock.filter.*）
            // 鲜活：vibe.rgba.bin / 节日：festival.rgba.bin / 庆典：ceremony.rgba.bin
            val switches = listOf(
                Triple("lively", "鲜活", "vibe.rgba.bin"),
                Triple("festival", "节日", "festival.rgba.bin"),
                Triple("ceremony", "庆典", "ceremony.rgba.bin"),
                Triple("director", "导演联名", "姜文 / 贾樟柯")
            )
            var state by remember { mutableStateOf(switches.associate { it.first to false }) }
            LaunchedEffect(Unit) {
                thread {
                    state = switches.associate { (key, _, _) ->
                        key to (Shell.run("getprop persist.camunlock.filter.$key").out.trim() == "1")
                    }
                }
            }
            PuiGroup(null) {
                switches.forEachIndexed { i, (key, name, lut) ->
                    PuiSwitchRow(
                        icon = PuiIcon.Palette,
                        title = name,
                        subtitle = if (state[key] == true) "已开启 · $lut" else "默认关（$lut 未随固件提供）",
                        checked = state[key] == true,
                        enabled = root,
                        showDivider = i != switches.lastIndex,
                        onChange = { on ->
                            thread {
                                Shell.run("setprop persist.camunlock.filter.$key ${if (on) 1 else 0}")
                                state = state + (key to on)
                                Shell.restartCamera()
                            }
                        }
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("界面")
        }

        item {
            // UI 换图：用模块 assets/ui 里的图替换相机的调色盘 / 灵感构图图标（不换 APK）
            var uiOn by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                thread { uiOn = Shell.run("getprop persist.camunlock.ui").out.trim() != "0" }
            }
            PuiGroup(null) {
                PuiSwitchRow(
                    icon = PuiIcon.Image,
                    title = "UI 换图",
                    subtitle = if (uiOn) "用 7.016 的图标 / 入口引导图替换（重启相机生效）" else "已关闭（用相机自带图）",
                    checked = uiOn,
                    enabled = root,
                    showDivider = false,
                    onChange = { on ->
                        thread {
                            Shell.run("setprop persist.camunlock.ui ${if (on) 1 else 0}")
                            uiOn = on
                            Shell.restartCamera()
                        }
                    }
                )
            }
        }

        item {
            Spacer(Modifier.height(22.dp))
            PuiSectionHeader("日志")
        }

        item {
            var logOn by remember { mutableStateOf(true) }
            var dumpOn by remember { mutableStateOf(false) }
            var lines by remember { mutableStateOf(-1) }
            LaunchedEffect(Unit) {
                thread {
                    logOn = Shell.run("getprop persist.camunlock.log").out.trim() != "0"
                    dumpOn = Shell.run("getprop persist.camunlock.log.dump").out.trim() == "1"
                    lines = Shell.adaptLogLines()
                }
            }
            fun toast(msg: String) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
            }
            PuiGroup(null) {
                PuiSwitchRow(
                    icon = PuiIcon.List,
                    title = "写日志",
                    subtitle = if (logOn) {
                        "logcat + 相机私有目录 camunlock/adapt.log"
                    } else {
                        "已关闭：适配自检表也不会写"
                    },
                    checked = logOn,
                    enabled = root,
                    showDivider = true,
                    onChange = { on ->
                        thread {
                            Shell.run("setprop persist.camunlock.log ${if (on) 1 else 0}")
                            logOn = on
                            lines = Shell.adaptLogLines()
                        }
                    }
                )
                PuiSwitchRow(
                    icon = PuiIcon.Filter,
                    title = "细节 dump",
                    subtitle = "滤镜组每个条目、UI 换图明细（行数很多，排完就关）",
                    checked = dumpOn,
                    enabled = root,
                    showDivider = true,
                    onChange = { on ->
                        thread {
                            Shell.run("setprop persist.camunlock.log.dump ${if (on) 1 else 0}")
                            dumpOn = on
                        }
                    }
                )
                PuiListRow(
                    icon = PuiIcon.Download,
                    title = "导出日志",
                    subtitle = "复制到 /sdcard/Download/camunlock-adapt-*.log",
                    trailingText = if (lines >= 0) "$lines 行" else null,
                    showChevron = true,
                    onClick = {
                        thread {
                            val r = Shell.exportAdaptLog()
                            lines = Shell.adaptLogLines()
                            toast(if (r.ok) "已导出：${r.out}" else "导出失败：${r.out}")
                        }
                    }
                )
                PuiListRow(
                    icon = PuiIcon.Trash,
                    title = "清空日志",
                    subtitle = "adapt.log 清零，相机还在跑也没关系",
                    showChevron = true,
                    showDivider = false,
                    onClick = {
                        thread {
                            Shell.clearAdaptLog()
                            lines = Shell.adaptLogLines()
                            toast("已清空")
                        }
                    }
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