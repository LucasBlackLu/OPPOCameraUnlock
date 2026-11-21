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

    fun run(cmd: String, timeoutSec: Long = 15): Result = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            return Result(false, "su 超时")
        }
        Result(p.exitValue() == 0 && !out.contains("Error", ignoreCase = true), out)
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
        val r = run("wc -l < $ADAPT_LOG 2>/dev/null")
        return r.out.trim().toIntOrNull() ?: -1
    }

    /** 把 adapt.log 复制到 /sdcard/Download，方便用文件管理器/分享拿走。 */
    fun exportAdaptLog(): Result {
        val stamp = java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.ROOT)
            .format(java.util.Date())
        val dst = "/sdcard/Download/camunlock-adapt-$stamp.log"
        val r = run("mkdir -p /sdcard/Download && cp $ADAPT_LOG $dst && chmod 644 $dst && echo $dst")
        return Result(r.ok && r.out.contains(".log"), r.out.ifBlank { "复制失败" })
    }

    /** 清空日志（相机还开着也能清，下次写会重新建）。 */
    fun clearAdaptLog(): Result = run(": > $ADAPT_LOG; wc -l < $ADAPT_LOG 2>/dev/null")
    
}