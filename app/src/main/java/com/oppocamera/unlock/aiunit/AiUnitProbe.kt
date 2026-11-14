package com.oppocamera.unlock.aiunit

import android.content.Context
import android.util.Log
import dalvik.system.DexClassLoader
import java.io.File

/**
 * 离线验证 AIUnit 的构图接口能不能用。
 *
 * linggan 的做法（dexdump 出来的结论）：
 *   AiCompositionBridge.compose(Context, HardwareBuffer, float) -> String
 *   内部 toBitmap + 拼 JSON + 异步请求，结果同步返回。
 *
 * 它把真正的连接器放在 assets/feiyu_aiunit_bridge.dex：
 *   com.oplus.aiunit.core.base.FrameDetector   <init>(Context, String)
 *   com.trae.feiyu.cloud.FeiyuFrameDetector    <init>(Context, String)
 *   com.trae.feiyu.cloud.FeiyuConnector        <init>(Context, FrameDetector, String)
 *
 * 这里只做「把 dex 解出来 + 把类加载起来 + 打印构造器」，不连云端、不碰相机。
 * 类能建出来，下一步才谈喂帧。
 */
object AiUnitProbe {

    private const val TAG = "AiUnitProbe"
    private const val ASSET = "aiunit/feiyu_aiunit_bridge.dex"

    data class Result(val ok: Boolean, val message: String)

    fun load(ctx: Context): Result = runCatching {
        val dir = File(ctx.filesDir, "aiunit").apply { mkdirs() }
        val dex = File(dir, "feiyu_aiunit_bridge.dex")
        if (!dex.isFile || dex.length() == 0L) {
            ctx.assets.open(ASSET).use { input ->
                dex.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dex.setReadable(true, false)

        val loader = DexClassLoader(
            dex.absolutePath,
            dir.absolutePath,
            null,
            javaClass.classLoader
        )

        val names = listOf(
            "com.oplus.aiunit.core.base.FrameDetector",
            "com.trae.feiyu.cloud.FeiyuFrameDetector",
            "com.trae.feiyu.cloud.FeiyuConnector"
        )
        val found = names.mapNotNull { name ->
            val cls = runCatching { loader.loadClass(name) }.getOrNull() ?: return@mapNotNull null
            val ctors = cls.declaredConstructors.joinToString("; ") { c ->
                "(" + c.parameterTypes.joinToString { it.simpleName } + ")"
            }
            "$name ctors=$ctors"
        }

        if (found.isEmpty()) Result(false, "dex 里三个类都加载不到")
        else {
            found.forEach { Log.i(TAG, it) }
            Result(true, "插件 dex 可加载：\n" + found.joinToString("\n"))
        }
    }.getOrElse { Result(false, "加载失败：${it.message}") }
}