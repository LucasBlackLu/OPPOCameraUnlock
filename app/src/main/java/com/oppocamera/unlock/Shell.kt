package com.oppocamera.unlock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.concurrent.TimeUnit

object Shell {

    private const val CAMERA = "com.oplus.camera"
    private const val CAMERA_ACTIVITY = "com.oplus.camera/.Camera"

    data class Result(val ok: Boolean, val out: String)

    @Volatile
    var rootGranted: Boolean? = null
        private set

    fun run(cmd: String, timeoutSec: Long = 15): Result = runInternal(cmd, timeoutSec, checkErrorText = true)

    /**
     * 只认退出码，不看输出内容。
     * 读日志/读文件必须用这个：日志里本来就会出现 "Error" 字样，
     * 用 [run] 会把成功的结果判成失败（导出日志就是这么失败的）。
     */
    fun runRaw(cmd: String, timeoutSec: Long = 30): Result =
        runInternal(cmd, timeoutSec, checkErrorText = false)

    private fun runInternal(cmd: String, timeoutSec: Long, checkErrorText: Boolean): Result = runCatching {
        // 应用进程的环境里没有 /system/bin 的 PATH：不补的话 `cat` / `wc` / `am` 都会
        // 变成 "command not found"，而我们又把 stderr 丢了 → 看上去就是「读不到」。
        val full = "export PATH=/system/bin:/system/xbin:/vendor/bin:/product/bin:\$PATH; $cmd"
        val p = ProcessBuilder("su", "-c", full).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            return Result(false, "su 超时")
        }
        val bad = p.exitValue() != 0 || (checkErrorText && out.contains("Error", ignoreCase = true))
        Result(!bad, out)
    }.getOrElse { Result(false, it.message ?: "su 不可用") }

    fun checkRoot(): Boolean {
        val r = run("id")
        val ok = r.ok && r.out.contains("uid=0")
        rootGranted = ok
        return ok
    }

    fun restartCamera(): Result {
        val stop = run("am force-stop $CAMERA")
        if (!stop.ok) return stop
        val start = run("am start -n $CAMERA_ACTIVITY")
        return if (start.ok) Result(true, "已重启相机") else start
    }

    fun openCamera(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(CAMERA)
        if (intent != null) start(ctx, intent) else start(ctx, Intent().setClassName(CAMERA, ".Camera"))
    }

    fun openAppInfo(ctx: Context) = start(
        ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$CAMERA"))
    )

    fun openLsposed(ctx: Context) = start(
        ctx,
        ctx.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:org.lsposed.manager"))
    )

    private fun start(ctx: Context, intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    /** 浏览器打开链接。 */
    fun openUrl(ctx: android.content.Context, url: String) {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // ---------------------------------------------------------------- 日志

    private const val ADAPT_DIR = "/data/user/0/com.oplus.camera/files/camunlock"
    private const val ADAPT_LOG = "$ADAPT_DIR/adapt.log"

    /** adapt.log 现在有多少行（拿不到就返回 -1）。 */
    fun adaptLogLines(): Int {
        val r = runRaw("wc -l < $ADAPT_LOG 2>/dev/null")
        return r.out.trim().toIntOrNull() ?: -1
    }

    /** 模块发布出来的那份（相机启动时写的，文件管理器直接能看）。 */
    private const val PUBLISHED_DIR = "/sdcard/Download/CamUnlock"
    private const val PUBLISHED_LOG = "$PUBLISHED_DIR/camunlock-adapt.log"

    /**
     * 「导出日志」：模块在**相机启动时**已经把 adapt.log 发布到 `Download/CamUnlock/` 了
     * （它跑在相机进程里，有写权限；而设置应用 su 到 ksu 域既写不了 /sdcard，
     * 也没有「所有文件访问」权限 —— 那条路已经证明走不通）。
     *
     * 这里做三件事：
     *  1. 报告公开路径（+ 文件大小）
     *  2. 尝试用 MediaStore 找到它的 content:// URI，供「分享」用
     *  3. 能读到内容时，再复制一份带时间戳的副本到应用私有目录，方便留存
     */
    fun exportAdaptLog(ctx: Context): Result {
        // 文件在 Download/CamUnlock/（模块在相机启动时写的），应用自己没有读 /sdcard 的权限，
        // 所以走 MediaStore 查 content:// URI —— 相机写完会 scanFile 建索引。
        val uri = runCatching {
            ctx.contentResolver.query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("camunlock-adapt.log"),
                "${android.provider.MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()?.let {
            android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, it
            )
        }

        val path = "$PUBLISHED_LOG"
        if (uri != null) {
            share(ctx, uri)
            return Result(true, path)
        }
        // 查不到（还没扫到索引）也别假装失败：告诉用户文件在哪，可直接用文件管理器取
        val exists = runCatching { java.io.File(path).exists() }.getOrDefault(false)
        return if (exists) Result(true, "$path（尚未进媒体索引，直接用文件管理器取）")
        else Result(false, "打开一次相机后，日志会自动出现在 $PUBLISHED_DIR/")
    }

    /** 分享某个 content:// 文件（没找到 URI 就只给路径）。 */
    private fun share(ctx: Context, uri: android.net.Uri) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(Intent.createChooser(send, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 清空日志（相机还开着也能清，下次写会重新建）。 */
    fun clearAdaptLog(): Result = runRaw(": > $ADAPT_LOG; wc -l < $ADAPT_LOG 2>/dev/null")
    
}