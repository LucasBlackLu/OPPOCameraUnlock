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

    fun run(cmd: String): Result = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
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
}