package com.oppocamera.unlock.hook

import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 适配层：目标解析 + 自检日志（做法参考 OmniCam-Muse 的 Symbols / Log.guard / Diagnostics）。
 *
 * 为什么要有这一层：
 *  - 相机和 unit.sdk 的类名、混淆名每个版本都不一样，散落在各处的 runCatching 里，
 *    到时候根本分不清「是这台机器没这东西」还是「我们名字写错了」；
 *  - 这里把每个 hook 目标登记成 **id + 一句话说明 + 候选清单**，命中记 HIT（哪个类.方法），
 *    没命中记 MISS（试过哪些候选），相机启动结束打一张「适配自检表」；
 *  - 目标是可选的：没命中只是那一个功能不生效，其它照旧（对应他们的 OptionalHook / Log.guard）；
 *  - 适配新机型/新相机版本时，只需要改本文件里的候选清单和 [DEFAULT_TARGETS]，
 *    不用碰各个 hook 的实现。
 *
 * 开关（系统属性，改完重启相机进程生效）：
 *   `persist.camunlock.log`       1 = 写日志（默认 1）
 *   `persist.camunlock.log.dump`  1 = 额外打滤镜组内容、文件清单等细节（默认 0）
 *
 * 日志同时进 logcat（tag `CamUnlock`）和文件
 * `/data/user/0/com.oplus.camera/files/camunlock/adapt.log`（root 可直接读，方便导出）。
 */
object Adapt {

    private const val TAG = "CamUnlock"
    private const val DIR = "camunlock"
    private const val LOG_NAME = "adapt.log"
    private const val LOG_MAX = 512 * 1024L

    /** 单个目标的解析结果。 */
    private class Target(val id: String, val desc: String) {
        var where: String? = null
        var miss: String? = null
        var tries: Int = 0

        val ok: Boolean get() = where != null
    }

    @Volatile private var loader: ClassLoader? = null
    @Volatile private var startedAt = 0L
    @Volatile private var module: String? = null
    @Volatile private var deviceLogged = false

    private val targets = LinkedHashMap<String, Target>()
    private val lock = Any()

    /** 拿不到 filesDir 时先把日志行攒在这里 */
    private val pending = ArrayDeque<String>()

    // ---------------------------------------------------------------- 生命周期

    /** 相机进程被 hook 时调用一次：记录模块信息、设备画像、挂载文件状态。 */
    fun install(cl: ClassLoader, version: String) {
        loader = cl
        module = version
        startedAt = System.currentTimeMillis()
        synchronized(lock) { targets.clear() }

        info("===== CamUnlock 适配自检 开始 =====")
        info("模块：$version · 相机进程 pid=${android.os.Process.myPid()}")
        info("设备（初判）：$device")
        files()
    }

    /** 相机启动收尾：打一张自检表。 */
    fun summary() {
        val all = synchronized(lock) { targets.values.toList() }
        val hit = all.filter { it.ok }
        val miss = all.filter { !it.ok }
        // 相机版本 / 模块版本在 install() 那一刻还拿不到（Application 还没建），
        // 到收尾时再打一次，适配时先看这几行。
        info("设备：$device")
        info("模块：${module ?: "?"}")
        info("适配自检：HIT ${hit.size} / MISS ${miss.size}，耗时 ${System.currentTimeMillis() - startedAt} ms")
        hit.forEach { info("  HIT  ${it.id.padEnd(18)} → ${it.where}") }
        miss.forEach { warn("  MISS ${it.id.padEnd(18)} → ${it.miss}（${it.desc}）") }
        info("===== CamUnlock 适配自检 结束 =====")
        publish()
    }

    /**
     * 把 adapt.log 再发布一份到 `Download/CamUnlock/`（**单独的文件夹**，文件管理器直接能拿）。
     *
     * 为什么是模块做而不是设置应用做：设置应用读相机私有日志要 root，
     * 而这台机器 KernelSU 的 root 域 `u:r:ksu:s0` 对 /sdcard 没有写权限、
     * 应用自己也没有「所有文件访问」，所以「应用 su cp 到 sdcard」这条路必然失败。
     * 模块跑在相机进程里，本身就有写自己目录/外部存储的权限。
     *
     * 先试直接写文件，不行再走 MediaStore（scoped storage 正路）。
     */
    fun publish() {
        val app = currentApplication() ?: return
        val dir = logDir() ?: return
        val file = File(dir, LOG_NAME)
        if (!file.isFile) return
        val name = "camunlock-adapt.log"

        val direct = runCatching {
            val target = File("/sdcard/Download/CamUnlock")
            if (!target.isDirectory && !target.mkdirs()) return@runCatching false
            val out = File(target, name)
            file.copyTo(out, overwrite = true)
            // 让 MediaStore 收一条索引：别的应用（设置应用）才能查到 content:// URI 去分享
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    app, arrayOf(out.absolutePath), arrayOf("text/plain"), null
                )
            }
            true
        }.getOrDefault(false)
        if (direct) {
            info("日志已发布：/sdcard/Download/CamUnlock/$name")
            return
        }

        val published = runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/CamUnlock")
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { out -> out.write(file.readBytes()) }
            true
        }.getOrDefault(false)
        if (published) info("日志已发布（MediaStore）：Download/CamUnlock/$name")
        else warn("日志发布失败：/sdcard/Download/CamUnlock 写不进去")
    }

    // ---------------------------------------------------------------- 目标解析

    /**
     * 按候选类名找一个类。返回 null 表示这台机器上没有（只影响用到它的功能）。
     * 命中第一个能加载的候选，并记下命中的是哪一个。
     */
    fun cls(id: String, desc: String, vararg candidates: String): Class<*>? {
        val target = register(id, desc)
        val cl = loader
        if (cl == null) {
            target.miss = "没有 classloader"
            return null
        }
        for (name in candidates) {
            target.tries++
            val found = runCatching { Class.forName(name, false, cl) }.getOrNull()
            if (found != null) {
                target.where = name
                target.miss = null
                return found
            }
        }
        // 多候选的用法里（一堆类逐个试）可能前面已经命中过，别把 HIT 降级成 MISS。
        if (target.where == null) {
            target.miss = "候选都不存在：" + candidates.joinToString(" | ")
        }
        return null
    }

    /** 在类里按指纹挑一个方法；[desc] 是给日志看的一句话（适配时用来对照）。 */
    fun method(id: String, desc: String, type: Class<*>?, pick: (Method) -> Boolean): Method? {
        val target = register(id, desc)
        if (type == null) {
            target.miss = "类没找到"
            return null
        }
        target.tries++
        val found = type.declaredMethods.firstOrNull(pick)
        if (found == null) {
            target.where = null
            target.miss = "${type.name} 里没匹配到（$desc）"
            return null
        }
        target.where = "${type.name}#${found.name}(" +
            found.parameterTypes.joinToString(",") { it.simpleName } + ")"
        target.miss = null
        return found
    }

    /** 目标已经由别的途径拿到（例如候选列表里逐个试），直接登记命中情况。 */
    fun hit(id: String, desc: String, where: String) {
        val target = register(id, desc)
        target.tries++
        target.where = where
        target.miss = null
    }

    fun miss(id: String, desc: String, why: String) {
        val target = register(id, desc)
        target.tries++
        target.where = null
        target.miss = why
    }

    private fun register(id: String, desc: String): Target = synchronized(lock) {
        targets.getOrPut(id) { Target(id, desc) }
    }

    // ---------------------------------------------------------------- 设备画像 / 文件

    /**
     * 设备画像 + 模块版本，等 Application 真的建好之后再打一次。
     * （onPackageLoaded 阶段 ActivityThread.currentApplication() 还是 null，
     *  相机版本读不到；而 ensureDeployed 里一定有 Application，那里调这个。）
     */
    fun deviceOnce(app: android.app.Application) {
        if (deviceLogged) return
        deviceLogged = true
        val pm = app.packageManager
        val cam = runCatching { pm.getPackageInfo("com.oplus.camera", 0).versionName }.getOrNull()
        val mod = runCatching { pm.getPackageInfo("com.oppocamera.unlock", 0).versionName }.getOrNull()
        info("设备（最终）：$device")
        info("模块（最终）：com.oppocamera.unlock $mod")

        // 启动几秒后把日志发布到 Download/CamUnlock/（那时 Application 在，且日志已经攒了一些）
        runCatching {
            val t = Thread {
                runCatching { Thread.sleep(5000) }
                runCatching { publish() }
            }
            t.isDaemon = true
            t.start()
        }
    }

    /** 机型 + 系统 + 相机版本，适配新机型时先看这一行。 */
    val device: String
        get() {
            val model = prop("ro.product.model")
            val market = prop("ro.vendor.oplus.market.name").ifEmpty { prop("ro.product.marketname") }
            val os = prop("ro.build.display.id")
            val sdk = prop("ro.build.version.sdk")
            val cam = cameraVersion()
            return "model=$model market=$market · ColorOS=$os (API $sdk) · 相机=$cam"
        }

    /**
     * 模块该绑的文件在不在（只读分区被 bind 之后才「在」）。
     * 这一组最能快速判断「模块是不是装好/挂上了」，适配时先看这几行。
     */
    fun files() {
        val wanted = listOf(
            "/odm/etc/camera/meishe_lut/hu_po.bin" to "胶片 LUT：琥珀",
            "/odm/etc/camera/meishe_lut/qing_tou.bin" to "胶片 LUT：清透",
            "/product/framework/com.oplus.camera.unit.sdk.jar" to "unit.sdk（滤镜清单）",
            "/odm/lib64/libmsnativefilter.so" to "Meishe 引擎（Custom LUT 参数）",
            "/odm/lib64/libBasicTonePhoto.so" to "BasicTone（调色盘 palette 符号）"
        )
        wanted.forEach { (path, name) ->
            val f = File(path)
            val state = if (!f.exists()) "缺失" else "${f.length()} B"
            val line = "文件 $name：$path → $state"
            if (f.exists()) detail(line) else warn(line)
        }
    }

    // ---------------------------------------------------------------- 开关

    private fun flag(key: String, def: Boolean): Boolean = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
        m.invoke(null, key, def) as Boolean
    }.getOrDefault(def)

    /** 写日志（默认开）。 */
    fun logEnabled(): Boolean = flag("persist.camunlock.log", true)

    /** 细节日志：滤镜组内容、候选逐个结果等（默认关）。 */
    fun dumpEnabled(): Boolean = flag("persist.camunlock.log.dump", false)

    /** 滤镜去重（默认开）：OEM 表里同一组会重复加同一条滤镜（如「和光」）。 */
    fun dedupEnabled(): Boolean = flag("persist.camunlock.filter.dedup", true)

    /** UI 换图（默认开）：用模块 assets/ui 里的新图替换相机自己的资源。 */
    fun uiSkinEnabled(): Boolean = flag("persist.camunlock.ui", true)

    /** 读一个系统属性开关；默认关。 */
    fun propOn(key: String, def: Boolean = false): Boolean = flag(key, def)

    // ---------------------------------------------------------------- 当前模式

    /** 相机当前模式 tag（由 `CommonUtil.isSupportPalette(mode)` 的调用维护）。 */
    @Volatile private var mode: String? = null

    fun setMode(tag: String?) {
        if (!tag.isNullOrEmpty()) mode = tag
    }

    /**
     * 大师 / 专业模式。这个模式下我们**不做 basictone 注入**：
     * 实测（原厂 7.006.121 相机、同画面 A/B）注入后大师预览饱和度 0.026 / 亮度 87，
     * 不注入是 0.054 / 148 —— 也就是说注入会把大师的 tone 打平、发灰。
     * 想恢复「大师里也能调色盘」的旧行为：setprop persist.camunlock.palette.master 1。
     */
    fun isMasterMode(): Boolean {
        val m = mode?.lowercase() ?: return false
        return m.contains("professional") || m.contains("master")
    }

    // ---------------------------------------------------------------- 日志

    fun info(msg: String) = emit("I", msg)

    fun warn(msg: String) = emit("W", msg)

    /** 只在 dump 开关打开时输出。 */
    fun detail(msg: String) {
        if (dumpEnabled()) emit("D", msg)
    }

    /**
     * 把 `R.string.camera_filter_xxx` 解析成相机里的显示名（中文），
     * 只为了日志里看得懂 —— 解析不了就原样返回。
     */
    fun string(resName: String?): String {
        if (resName == null) return "-"
        if (!resName.startsWith("R.string.")) return resName
        val name = resName.removePrefix("R.string.")
        val app = currentApplication() ?: return name
        return runCatching {
            val id = app.resources.getIdentifier(name, "string", "com.oplus.camera")
            if (id == 0) name else app.getString(id)
        }.getOrDefault(name)
    }

    private fun emit(level: String, msg: String) {
        if (!logEnabled()) return
        if (level == "W") Log.w(TAG, msg) else Log.i(TAG, msg)
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date()) +
            " " + android.os.Process.myPid() + " " + level + " " + msg + "\n"
        synchronized(pending) {
            // 进程刚起来时还没有 Application，filesDir 拿不到，先把行攒着，
            // 之后哪次能拿到就一起写下去（否则开头那段自检信息会丢）。
            val dir = logDir()
            if (dir == null) {
                pending.addLast(line)
                while (pending.size > 400) pending.removeFirst()
                return
            }
            runCatching {
                if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("mkdir $dir")
                val file = File(dir, LOG_NAME)
                if (file.length() > LOG_MAX) {
                    runCatching { file.renameTo(File(dir, "adapt.old.log")) }
                }
                val block = StringBuilder()
                while (pending.isNotEmpty()) block.append(pending.removeFirst())
                block.append(line)
                file.appendText(block.toString())
            }
        }
    }

    /** 相机自己的私有目录，root / 导出工具可读。 */
    private fun logDir(): File? = currentApplication()?.let { File(it.filesDir, DIR) }

    private fun currentApplication(): android.app.Application? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("currentApplication").invoke(thread) as? android.app.Application
    }.getOrNull()

    private fun prop(key: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")

    private fun cameraVersion(): String = runCatching {
        val app = currentApplication() ?: return@runCatching ""
        app.packageManager.getPackageInfo("com.oplus.camera", 0).versionName ?: ""
    }.getOrDefault("")
}
