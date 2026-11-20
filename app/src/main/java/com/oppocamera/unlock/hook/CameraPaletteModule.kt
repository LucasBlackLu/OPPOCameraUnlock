package com.oppocamera.unlock.hook

import android.app.Application
import android.hardware.HardwareBuffer
import android.util.Log
import com.oppocamera.unlock.aicomp.AiBridge
import com.oppocamera.unlock.aicomp.GyroTracker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.io.File
import java.lang.reflect.Modifier
import java.util.zip.ZipFile

/**
 * OPPO 相机解锁（libxposed API 101）。
 *
 * 两个功能：
 *
 *  1. 调色盘：相机的 LUT 应该在 /odm/etc/camera/basictone/palette，这台机器没有，
 *     强开 feature 会在 libCombineLut 里 std::bad_alloc。做法是把提取包整包部署到
 *     相机自己的 files 目录，再把 CombineLutHelper.init 的路径参数换过去。
 *
 *  2. 灵感构图（AI 构图）：相机本机带这套能力，但 feature 默认关着。
 *     这里把 CameraConfig / FeatureConfigureAdapter 里对应的 key 统统放行。
 */
class CameraPaletteModule : XposedModule() {

    companion object {
        private const val TAG = "CamUnlock"
        private const val PKG = "com.oplus.camera"
        private const val CONFIG = "com.oplus.camera.configure.CameraConfig"
        private const val ADAPTER = "com.oplus.ocs.camera.config.FeatureConfigureAdapter"
        private const val COMBINE = "com.oplus.ocs.camera.CombineLutHelper"
        private const val COMBINE_SDK =
            "com.oplus.ocs.camera.ipusdk.processunit.combinelut.CombineLutProcessUnitSDK"

        private const val LUT_SIZE = 49152L
        private const val ODM = "/odm/etc/camera/basictone"

        /**
         * 提取包版本：assets/basictone 的 palette/ 换成了 Reno 17 Pro 的
         * /odm/etc/camera/basictone/palette（18 个 SC16，其中 8 个与 X10 那版不同）。
         * 改这个字符串会让相机侧的缓存整包重铺。
         */
        private const val EXTRACT = "reno17p-1.0.0"
        private const val ASSET_ROOT = "assets/basictone/"

        /** 调色盘 */
        private const val KEY_PALETTE = "com.oplus.feature.color.palette.support"

        /** unit.sdk 里管滤镜分组表的类（琥珀/清透就在这里被判定） */
        private const val FILTER_GROUPS =
            "com.oplus.ocs.camera.ipusdk.processunit.filter.list.FilterGroupManager"

        /** unit.sdk 里解析 camera_unit_config 的类（mode_type_list 由它读） */
        private const val JSON_PARSER = "com.oplus.ocs.camera.configure.JsonParser"

        /** POP 模式的模式名（资源 camera_mode_retro_camera 的中文值就是 "POP"） */
        private const val POP_MODE = "retro_camera_mode"

        /** 模式 id ↔ tag 的映射类（master / retroCamera / commonVideo …都在这张表里） */
        private const val MODE_MAP_CLS = "com.oplus.camera.v6"

        /** 工具类：isSupportPalette(mode) 决定哪些模式有调色盘入口 */
        private const val COMMON_UTIL = "com.oplus.camera.common.utils.CommonUtil"

        /** unit.sdk 里读加密配置的入口（getConfigString 用 JNI 解密） */
        private const val UPDATE_HELPER =
            "com.oplus.ocs.camera.consumer.apsAdapter.update.UpdateHelper"

        /** 临时诊断：探测模式条构建类（默认关；要查 POP 入口时改 true） */
        private const val TRACE_MODE_BUILDERS = false

        /** 同时含 masterVideo + retroCamera 常量的候选类 */
        private val MODE_PROBE_CLASSES = listOf(
            "com.oplus.camera.o6",
            "ha.q0",
            "com.oplus.camera.CameraManager"
        )

        /** 滤镜组对象（addFrontAndBack 在这里，用来卡 鲜活/节日/庆典） */
        private const val FILTER_GROUP =
            "com.oplus.ocs.camera.ipusdk.processunit.filter.list.FilterGroup"

        /** 调色盘额外滤镜的开关 key（FilterGroupManager 里逐个滤镜组用它们判定） */
        private val FILTER_KEYS = listOf(
            "com.oplus.feature.qingtou.hupo.filter.support",          // 琥珀 / 清透
            "com.oplus.filter.travel.photo.support",                   // 旅拍
            "com.oplus.tol.style.filter.support",                      // TOL 风格
            "com.oplus.feature.800t.filter.support",                   // 800T
            "com.oplus.feature.800t.filter.portrait.support",
            "com.oplus.feature.fewer.users.filter.support",            // 小众
            "com.oplus.feature.flash.filter.support",                  // 闪光
            "com.oplus.feature.flash.filter.portrait.support",
            "com.oplus.feature.flash.warm.filter.support",
            "com.oplus.feature.portra.filter.support",                 // 人像滤镜
            "com.oplus.feature.tx400.filter.support",                  // TX400
            "com.oplus.feature.os15.new.filter.support",               // OS15 新滤镜
            "com.oplus.feature.blackandwhite.portra.positive.filter.update.support",
            "com.oplus.support.grand.tour.filter",                     // 哈苏大师
            "com.oplus.street.grand.tour.filter.type.support",
            "com.oplus.feature.soft.light.filter.support",             // 柔光
            "com.oplus.capture.sdr.all.route"
        )

        /**
         * FilterGroupManager 里的静态布尔门控字段。只放「xxxSupport」这一类，
         * 不碰 sbIsExport / sbIsBrandOplusR / sGotFilter / sbCaptureSdrAllRoute
         * —— 那些是运行状态，强改会走进别的分支。
         */
        private val FILTER_FLAG_FIELDS = listOf(
            "sbBlackandwhitePortraPositiveFilterSupport",
            "sbIsSupportTravelPhotoFilter",
            "sbIsSupportTolFilter"
        )

        /** 带开关的标志字段：字段名 → 开关名（开关关着就不设 true） */
        private val FILTER_FLAG_SWITCH = mapOf(
            "sbIsSupportFestivalFilter" to "festival",
            "sbIsSupportLivelyFilter" to "lively",
            "sbIsSupportJzkMovieFilter" to "director"
        )

        /** 导演联名滤镜（姜文 / 贾樟柯）—— 默认关，app 里开关打开才放行 */
        private val FILTER_SKIP = listOf("Director", "DirectorRUS", "Jzk", "jzk", "Jiangwen", "jangwen")

        /** 只看开关的 key：key → 开关名（开关关着就不强制 true） */
        private val SWITCH_KEYS = mapOf(
            "com.oplus.director.filter.support" to "director",
            "com.oplus.director.filter.rus" to "director",
            "com.oplus.support.jzk.movie.filter" to "director",
            "com.oplus.filter.festival.support" to "festival",
            "com.oplus.filter.lively.support" to "lively"
        )

        /**
         * 用系统属性做的开关（默认关）：persist.camunlock.filter.<name> = 1
         *   lively   → 鲜活（vibe.rgba.bin）
         *   festival → 节日（festival.rgba.bin）
         *   ceremony → 庆典（ceremony.rgba.bin）
         * 这三支的 LUT 在本机和 Reno 固件里都不存在，先按开关控制。
         */
        private val SWITCH_LUT = mapOf(
            "lively" to "vibe.rgba.bin",
            "festival" to "festival.rgba.bin",
            "ceremony" to "ceremony.rgba.bin"
        )

        /** 灵感构图（AI 构图）相关的几个 key，名字在不同版本里不一样，全给。 */
        private val AI_KEYS = listOf(
            "com.oplus.camera.feature.ai_composition",
            "com.oplus.feature.ai_composition",
            "com.oplus.feature.aicomposition.support",
            "com.oplus.feature.aicomposition.realscene.support",
            "com.oplus.feature.aicomposition.inspiration.support",
            "ai_composition",
            "aicomposition"
        )

        /**
         * 复古相机（= POP 模式）与鱼眼相关 key。
         * 7.016 装上来之后这些代码都齐了，缺的只是机型的项目配置。
         * 鱼眼是 retro 里的一个开关：filter.fisheye.enable / fisheye.enable。
         */
        private val RETRO_KEYS = listOf(
            "com.oplus.feature.retro.camera.support",
            "com.oplus.feature.retro.camera.livephoto.default.open",
            "com.oplus.camera.feature.retro_camera",
            "com.oplus.camera.retro.filter.fisheye.enable",
            "com.oplus.camera.retro.fisheye.enable",
            "com.oplus.camera.retro.filter.soft.enable",
            "com.oplus.retro.camera.max.zoom.value"
        )

        private val FORCED = AI_KEYS + KEY_PALETTE + RETRO_KEYS

        /**
         * 补进特性表的只有一个：相机里 feature 名的命名空间是 com.oplus.camera.feature.*，
         * 别的写法塞进去只会变成没人认识的条目。
         */
        private val ADAPTER_EXTRA = listOf("com.oplus.camera.feature.ai_composition")

        /** 临时：打印构图入口的方法调用，确认相机走没走这套 API。 */
        private const val TRACE_COMPOSITION = false

        /** 临时：跟踪「灵感推荐」这条链路的鉴权/网络，看它卡在哪一步。 */
        private const val TRACE_INSPIRATION = true

        private val INSPIRATION_CLASSES = listOf(
            "com.oplus.camera.feature.aicomposition.inspiration.account.AICompositionAccountManager",
            "com.oplus.camera.feature.aicomposition.inspiration.network.core.InspirationNetworkException",
            "pb.b",   // InspirationAuthHeaderProvider（混淆名，本版 OplusCamera 7.006.121）
            "pb.b\$a"
        )

        /** 构图入口，不同版本类名不一样，两个都试。 */
        private val COMPOSITION_ENTRIES = listOf(
            "com.oplus.ocs.camera.OplusAICompositionHelper",
            "com.oplus.camera.aicomposition.OplusAIComposition"
        )

        /**
         * 预览取帧类的混淆名（dex 里就这么短）。版本对照：
         *   7.006.121 → wb.r / rb.t（方法 f，11 参）
         *   7.016.29  → tb.q（方法 f，11 参）
         *   7.013.60  → tb.u / tb.d（同样 11 参，含 2~3 个 HardwareBuffer）
         * 所以不认方法名，只认「11 个参数 + 至少 2 个 HardwareBuffer」。
         */
        private val FRAME_CLASSES = listOf("tb.u", "tb.q", "tb.d", "wb.r", "rb.t")
    }

    private val lock = Any()
    private val logged = HashSet<String>()
    private val switchCache = HashMap<String, Boolean>()
    @Volatile private var adapterLogged = false
    @Volatile private var trackLogged = false
    @Volatile private var decodedOnce = false

    @Volatile private var deployed = false
    @Volatile private var settingPath: String? = null
    @Volatile private var palettePath: String? = null
    @Volatile private var odtPath: String? = null

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != PKG) return
        val cl = param.defaultClassLoader

        runCatching { hookConfig(cl) }.onFailure { log("hookConfig: $it") }
        runCatching { hookAdapter(cl) }.onFailure { log("hookAdapter: $it") }
        runCatching { hookInit(cl, COMBINE) }.onFailure { log("hookInit: $it") }
        runCatching { hookInit(cl, COMBINE_SDK) }.onFailure { log("hookInitSdk: $it") }
        runCatching { hookPaletteColors(cl) }.onFailure { log("hookPaletteColors: $it") }
        runCatching { hookFilterGroups(cl) }.onFailure { log("hookFilterGroups: $it") }
        runCatching { hookModeList(cl) }.onFailure { log("hookModeList: $it") }
        runCatching { hookModeSwap(cl) }.onFailure { log("hookModeSwap: $it") }
        runCatching { hookPaletteGate(cl) }.onFailure { log("hookPaletteGate: $it") }
        runCatching { hookConfigDecrypt(cl) }.onFailure { log("hookConfigDecrypt: $it") }
        if (TRACE_MODE_BUILDERS) runCatching { probeModeBuilders(cl) }.onFailure { log("probeModeBuilders: $it") }
        runCatching { dumpModeIds(cl) }.onFailure { log("dumpModeIds: $it") }
        runCatching { hookComposition(cl) }.onFailure { log("hookComposition: $it") }
        if (TRACE_INSPIRATION) runCatching { traceInspiration(cl) }.onFailure { log("traceInspiration: $it") }

        log("ready · api=${getApiVersion()} · pid=${android.os.Process.myPid()}")
    }

    // ------------------------------------------------------------ feature key

    private fun hookConfig(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass(CONFIG) }.getOrNull()
        if (cls == null) {
            log("WARN: $CONFIG not found")
            return
        }
        var n = 0
        cls.declaredMethods.forEach { m ->
            if (m.name != "d" || m.parameterTypes.size != 3) return@forEach
            if (m.parameterTypes[0] != String::class.java) return@forEach
            if (m.returnType != Boolean::class.javaPrimitiveType) return@forEach
            runCatching {
                hook(m).intercept { chain ->
                    val key = chain.getArg(0) as? String ?: return@intercept chain.proceed()
                    val lower = key.lowercase()
                    if (key == KEY_PALETTE ||
                        lower.contains("aicomposition") || lower.contains("ai_composition")
                    ) true else chain.proceed()
                }
                n++
            }.onFailure { log("hook CameraConfig.d: $it") }
        }
        log("CameraConfig.d hooked=$n")
    }

    /**
     * 特性表：FeatureConfigureAdapter 负责告诉界面哪些 feature 可用。
     * 它返回的是集合/映射，这里在出口把缺的 feature 名补进去。
     */
    private fun hookAdapter(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass(ADAPTER) }.getOrNull()
        if (cls == null) {
            log("WARN: $ADAPTER not found")
            return
        }
        var n = 0
        cls.declaredMethods.forEach { m ->
            val rt = m.returnType
            val isCollection = Collection::class.java.isAssignableFrom(rt)
            val isMap = Map::class.java.isAssignableFrom(rt)
            if (!isCollection && !isMap) return@forEach
            runCatching {
                hook(m).intercept { chain ->
                    val result = chain.proceed()
                    if (result != null) {
                        // 元素是 String 的集合才补；别的类型（FeatureKey 之类）一律不碰
                        runCatching { patchFeatureContainer(result) }
                            .onSuccess { added ->
                                if (added.isNotEmpty()) log("adapter +$added #${m.name}")
                            }
                    }
                    if (!adapterLogged && result != null) {
                        adapterLogged = true
                        val sample = runCatching {
                            (result as? Collection<*>)?.take(60)?.joinToString { it?.toString().orEmpty() }
                                ?: (result as? Map<*, *>)?.keys?.take(3)?.joinToString { it?.toString().orEmpty() }
                                ?: result.javaClass.simpleName
                        }.getOrDefault("?")
                        log("adapter sample: $sample")
                        runCatching {
                            val cls = (result as? Collection<*>)?.firstOrNull()?.javaClass
                            if (cls != null) {
                                log(
                                    "FeatureKey cls=${cls.name} enum=${cls.isEnum} " +
                                        "ctors=${cls.declaredConstructors.joinToString { c -> c.parameterTypes.joinToString { it.simpleName } }}"
                                )
                            }
                        }
                    }
                    result
                }
                n++
            }.onFailure { log("hook adapter#${m.name}: $it") }
        }
        log("FeatureConfigureAdapter hooked=$n")
    }

    /**
     * 只往元素是 String 的集合里补 feature 名；返回补进去的名字。
     *
     * 只碰 Collection，不碰 Map：`getConflictMap` 那种 map 的 key 是
     * `com.oplus.ocs.camera.config.FeatureKey`，塞 String 进去会让相机在
     * `FeatureConflictDecision.f()` 里 ClassCastException 闪退（踩过）。
     */
    private fun patchFeatureContainer(container: Any): List<String> {
        val added = mutableListOf<String>()
        runCatching {
            if (container !is MutableCollection<*>) return added
            // 空集合类型不明，一律不碰；元素不是 String 的也不碰
            val first = container.firstOrNull() ?: return added
            if (first !is String) return added
            @Suppress("UNCHECKED_CAST")
            val list = container as MutableCollection<Any?>
            ADAPTER_EXTRA.filterNot { name -> list.any { it == name } }.forEach {
                list.add(it)
                added += it
            }
        }
        return added
    }
    // ------------------------------------------------------------ combine lut

    private fun hookInit(cl: ClassLoader, className: String) {
        val cls = runCatching { cl.loadClass(className) }.getOrNull()
        if (cls == null) {
            log("WARN: $className not found")
            return
        }
        var n = 0
        cls.declaredMethods.forEach { m ->
            if (m.name != "init" || m.parameterTypes.size != 6) return@forEach
            runCatching {
                hook(m).intercept { chain ->
                    val arg5 = chain.getArg(5) as? String
                    if (arg5 == null || !arg5.contains("palette")) {
                        return@intercept chain.proceed()
                    }
                    if (!ensureDeployed()) {
                        return@intercept chain.proceed()
                    }
                    val setting = settingPath ?: return@intercept chain.proceed()
                    val palette = palettePath ?: return@intercept chain.proceed()
                    val odt = odtPath

                    val args = chain.args.toMutableList()
                    args[2] = setting
                    args[5] = palette

                    var result = chain.proceed(args.toTypedArray())
                    var code = (result as? Number)?.toInt() ?: 0
                    if (code != 0 && odt != null) {
                        args[1] = odt
                        result = chain.proceed(args.toTypedArray())
                        code = (result as? Number)?.toInt() ?: 0
                    }
                    logOnce("palette init code=$code")
                    result
                }
                n++
            }.onFailure { log("hook $className.init: $it") }
        }
        log("$className init/6 hooked=$n")
    }

    // ------------------------------------------------------------ mode list（POP）

    /**
     * unit.sdk 里 `com.oplus.ocs.camera.configure.JsonParser.parseSupportedMode()` 读
     * `camera_unit_config` 的 `mode_type_list`，返回 Map<摄像头 id, List<模式名>>。
     * 相机模式条就是从这里来的。
     *
     * 做法：先把它解析出来的东西打一条日志（诊断），再把 `retro_camera_mode`（= POP）
     * 塞进每个摄像头的模式列表最前面。开关：persist.camunlock.pop，默认开。
     */
    private fun hookModeList(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass(JSON_PARSER) }.getOrNull()
        if (cls == null) {
            log("WARN: $JSON_PARSER not found")
            return
        }
        val m = runCatching { cls.getDeclaredMethod("parseSupportedMode") }.getOrNull()
        if (m == null) {
            log("WARN: parseSupportedMode not found")
            return
        }
        hook(m).intercept { chain ->
            val r = chain.proceed()
            val map = r as? Map<*, *> ?: return@intercept r
            runCatching {
                logOnce("mode_type_list: " + map.entries.joinToString("; ") { e ->
                    e.key.toString() + "=" + (e.value as? Collection<*>)?.joinToString(",", "[", "]")
                })
            }
            if (!popEnabled()) return@intercept r
            runCatching {
                val out = LinkedHashMap<Any?, Any?>()
                map.forEach { (k, v) ->
                    val list = (v as? Collection<*>)?.map { it?.toString() }?.toMutableList()
                    if (list != null) {
                        list.remove(POP_MODE)
                        list.add(0, POP_MODE)
                        out[k] = list
                    } else out[k] = v
                }
                logOnce("mode_type_list: 已注入 $POP_MODE")
                return@intercept out
            }
            r
        }
        log("JsonParser.parseSupportedMode hooked（POP 注入）")
    }

    private fun popEnabled(): Boolean = synchronized(switchCache) {
        switchCache.getOrPut("pop") {
            runCatching {
                val c = Class.forName("android.os.SystemProperties")
                val g = c.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
                g.invoke(null, "persist.camunlock.pop", true) as Boolean
            }.getOrDefault(true)
        }
    }

    /**
     * 模式条那 5 个格子是 APK 里另一张表挑出来的（不在 mode_type_list 里），
     * 而 `com.oplus.camera.v6` 是模式 id ↔ tag 的映射表（"master" / "retroCamera" / …）。
     *
     * 兜底方案：把大师那格换成 POP —— 在映射里做双向替换，
     * master ⇄ retro_camera_mode / retroCamera。开关 persist.camunlock.pop.swap，默认开。
     */
    private fun hookModeSwap(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass(MODE_MAP_CLS) }.getOrNull()
        if (cls == null) {
            log("WARN: $MODE_MAP_CLS not found")
            return
        }
        var n = 0
        cls.declaredMethods.forEach { m ->
            if (!Modifier.isStatic(m.modifiers)) return@forEach
            if (m.parameterTypes.size != 1 || m.parameterTypes[0] != String::class.java) return@forEach
            if (m.returnType != String::class.java) return@forEach
            runCatching {
                hook(m).intercept { chain ->
                    val arg = chain.getArg(0) as? String
                    val mapped = swapMode(arg)
                    if (mapped != null) {
                        logOnce("mode swap: $arg -> $mapped (${m.name})")
                        mapped
                    } else chain.proceed()
                }
                n++
            }.onFailure { log("hook ${m.name}: $it") }
        }
        log("$MODE_MAP_CLS String 映射 hooked=$n")
    }

    /** master ⇄ POP 的双向替换；开关关着返回 null（走原实现） */
    private fun swapMode(arg: String?): String? {
        if (arg == null) return null
        if (!popSwapEnabled()) return null
        return when (arg) {
            "master", "master_mode", "master_video_mode", "masterVideo" -> {
                if (arg.endsWith("_mode")) "retro_camera_mode" else "retroCamera"
            }
            "retro_camera_mode", "retroCamera" -> {
                if (arg.endsWith("_mode")) "master_video_mode" else "masterVideo"
            }
            else -> null
        }
    }

    private fun popSwapEnabled(): Boolean = synchronized(switchCache) {
        switchCache.getOrPut("popswap") {
            runCatching {
                val c = Class.forName("android.os.SystemProperties")
                val g = c.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
                g.invoke(null, "persist.camunlock.pop.swap", true) as Boolean
            }.getOrDefault(true)
        }
    }

    /**
     * 调色盘入口的模式白名单。
     *
     * `com.oplus.camera.common.utils.CommonUtil.isSupportPalette(String mode)` 原文：
     *
     *     if (!CameraConfig.d("com.oplus.feature.color.palette.support", false, false)) return false;
     *     return "common".equals(mode) || "portrait".equals(mode) || "night".equals(mode);
     *
     * 大师（master）不在名单里，所以大师模式没有调色盘入口。这里直接返回 true。
     * 开关：persist.camunlock.palette.allmode，默认开。
     */
    private fun hookPaletteGate(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass(COMMON_UTIL) }.getOrNull()
        if (cls == null) {
            log("WARN: $COMMON_UTIL not found")
            return
        }
        val m = runCatching { cls.getDeclaredMethod("isSupportPalette", String::class.java) }.getOrNull()
        if (m == null) {
            log("WARN: isSupportPalette not found")
            return
        }
        hook(m).intercept { chain ->
            val mode = chain.getArg(0) as? String
            val on = synchronized(switchCache) {
                switchCache.getOrPut("paletteAll") {
                    runCatching {
                        val c = Class.forName("android.os.SystemProperties")
                        val g = c.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
                        g.invoke(null, "persist.camunlock.palette.allmode", true) as Boolean
                    }.getOrDefault(true)
                }
            }
            if (!on) return@intercept chain.proceed()
            logOnce("isSupportPalette(" + mode + ") -> true")
            true
        }
        log("CommonUtil.isSupportPalette hooked（所有模式放行调色盘）")
    }

    /**
     * `/odm/etc/camera/config/oplus_camera_config` 是加密二进制（文件头 0x01 0x01）。
     *
     * 解密链在 unit.sdk 里：
     *   ApsUtils.getConfigData(Context)  → 拼 CONFIG_JSON_PATH + "oplus_camera_config"
     *   UpdateHelper.getValidConfigData  → 读文件字节
     *   UpdateHelper.getConfigString(byte[], OplusStringJNI) → **JNI 解密成字符串**
     *
     * 所以不用啃 .so：hook 最后这一步，把解密结果落到相机私有目录（root 可读）。
     * 下一步就能读懂它的结构，再让这个 hook 直接返回改过的 JSON。
     */
    private fun hookConfigDecrypt(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass(UPDATE_HELPER) }.getOrNull()
        if (cls == null) {
            log("WARN: $UPDATE_HELPER not found")
            return
        }
        val jniCls = runCatching {
            cl.loadClass("com.oplus.ocs.camera.consumer.apsAdapter.config.OplusStringJNI")
        }.getOrNull()
        if (jniCls == null) {
            log("WARN: OplusStringJNI not found")
            return
        }
        val m = runCatching {
            cls.getDeclaredMethod("getConfigString", ByteArray::class.java, jniCls)
        }.getOrNull()
        if (m == null) {
            log("WARN: getConfigString not found")
            return
        }
        hook(m).intercept { chain ->
            val r = chain.proceed() as? String
            if (r != null && r.length > 200) {
                // 每次调用都落盘（用长度+hash 命名），加密的 oplus_camera_config 也在里面
                runCatching {
                    val app = currentApplication()
                    val dir = File(app!!.filesDir, "decoded")
                    dir.mkdirs()
                    val tag = Integer.toHexString(r.hashCode())
                    val f = File(dir, "cfg_" + r.length + "_" + tag + ".json")
                    if (!f.exists()) {
                        f.writeText(r)
                        log("decoded config -> " + f.name + " (" + r.length + " chars)")
                    }
                }
            }
            r
        }
        log("UpdateHelper.getConfigString hooked（解密结果落盘）")
    }

    /**
     * 诊断用：找出「模式条那 5 格」是哪个类拼出来的。
     *
     * 已知同时含 masterVideo / retroCamera 常量的类里，挑最像的 3 个，
     * 把它们「返回 List / String / *Mode」的方法全都 hook 一遍，打出 入参 → 返回值。
     * 谁返回的是 5 个模式名，谁就是目标。
     */
    private fun probeModeBuilders(cl: ClassLoader) {
        MODE_PROBE_CLASSES.forEach { name ->
            val cls = runCatching { cl.loadClass(name) }.getOrNull()
            if (cls == null) {
                log("probe: $name 不存在")
                return@forEach
            }
            var n = 0
            cls.declaredMethods.forEach { m ->
                if (m.parameterCount > 2) return@forEach
                val rt: Class<*> = m.returnType
                val ok: Boolean = List::class.java.isAssignableFrom(rt) ||
                        rt == String::class.java ||
                        rt.name.endsWith("BaseMode") ||
                        rt.simpleName.contains("Mode")
                if (!ok) return@forEach
                runCatching {
                    hook(m).intercept { chain ->
                        val r: Any? = chain.proceed()
                        runCatching {
                            val a = chain.args.joinToString(",") { it?.toString()?.take(20) ?: "null" }
                            logOnce("PROBE " + cls.simpleName + "." + m.name + "(" + a + ") -> " + (r?.toString()?.take(90) ?: "null"))
                        }
                        r
                    }
                    n++
                }
            }
            log("probe: $name 挂了 $n 个方法")
        }
    }

    /**
     * 查「模式 tag → mode_id」。
     *
     * `mode_data.db` 的 `mode_info.mode_id` 就是模式条排序用的 id；
     * 相机自己用 `zp.j.k(String):I` 把 tag 转成 id（`CameraManager$r` 里就是
     * `Ac()` 拿当前 tag → `zp.j.k()` → `zp.j.e()` 去查 DB）。
     *
     * 这里直接把关心的 tag 全查一遍打到日志里，顺便 hook 一下 k()，
     * 看看真实运行时会用哪些 tag。
     */
    private fun dumpModeIds(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass("zp.j") }.getOrNull()
        if (cls == null) {
            log("WARN: zp.j not found")
            return
        }
        // 注意：不能在这里主动 invoke k()/e()（会卡住相机启动线程），
        // 只 hook 它们，等相机自己调用时把 tag/资源 id/DB id 打出来。
        runCatching { cls.getDeclaredMethod("k", String::class.java) }.getOrNull()?.let { k ->
            runCatching {
                hook(k).intercept { chain ->
                    val a = chain.getArg(0) as? String
                    val v = chain.proceed()
                    log("zp.j.k(" + a + ") -> " + v)
                    v
                }
            }.onFailure { log("hook zp.j.k: $it") }
        }
        runCatching { cls.getDeclaredMethod("e", Integer.TYPE) }.getOrNull()?.let { e ->
            runCatching {
                hook(e).intercept { chain ->
                    val a = chain.getArg(0)
                    val v = chain.proceed()
                    log("zp.j.e(" + a + ") -> " + v)
                    v
                }
            }.onFailure { log("hook zp.j.e: $it") }
        }
        log("zp.j.k / e 已挂（只记录，不主动调用）")

        // 延迟 6 秒再主动查（启动线程里直接 invoke 会卡住）
        runCatching {
            val th = Thread {
                try { Thread.sleep(6000) } catch (_: Throwable) {}
                runCatching {
                    val tags = listOf("retroCamera", "masterVideo", "commonVideo", "common",
                        "portrait", "professional", "more", "night", "highPixel", "aiScenery")
                    val e = runCatching { cls.getDeclaredMethod("e", Integer.TYPE) }.getOrNull()
                    val k = cls.getDeclaredMethod("k", String::class.java)
                    tags.forEach { t ->
                        runCatching {
                            val kv = k.invoke(null, t)
                            val ev = e?.invoke(null, kv)
                            log("LATE modeId(" + t + ") k=" + kv + " e=" + ev)
                        }
                    }
                }
            }
            th.isDaemon = true
            th.start()
        }.onFailure { log("late probe: $it") }
    }

    // ------------------------------------------------------------ filter groups

    /**
     * 调色盘的滤镜组门控。
     *
     * unit.sdk 的 FilterGroupManager 用三层判定决定每组放哪些滤镜：
     *   1. 静态字段 sbXxxSupport（clinit 里按项目配置赋值）→ 反射改字段
     *   2. isXxx() 静态判定（isSupportQingtouHupoFilter 等）→ hook 成 true
     *   3. getConfigBooleanValue(key) → key 加进 FILTER_KEYS 强制 true
     *
     * 琥珀/清透的实现（dex 反编译）：
     *   isSupportQingtouHupoFilter() = getConfigBooleanValue("com.oplus.feature.qingtou.hupo.filter.support")
     *   initPaletteExtraFilterGroup(group) { if (...) { add("qing_tou.bin"); add("hu_po.bin") } }
     */
    private fun hookFilterGroups(cl: ClassLoader) {
        val cls = runCatching { cl.loadClass(FILTER_GROUPS) }.getOrNull()
        if (cls == null) {
            log("WARN: $FILTER_GROUPS not found")
            return
        }

        // 1) 所有 isXxx() 静态判定放行；导演联名（姜文/贾樟柯）跳过
        runCatching {
            var n = 0
            cls.declaredMethods.forEach { m ->
                if (!Modifier.isStatic(m.modifiers)) return@forEach
                if (m.parameterTypes.isNotEmpty()) return@forEach
                if (m.returnType != Boolean::class.javaPrimitiveType) return@forEach
                if (!m.name.startsWith("is")) return@forEach
                // 导演联名（姜文/贾樟柯）只在开关打开时放行
                if (FILTER_SKIP.any { m.name.contains(it, ignoreCase = true) } && !switchOn("director")) return@forEach
                if (m.name.contains("Festival", true) && !switchOn("festival")) return@forEach
                if (m.name.contains("Lively", true) && !switchOn("lively")) return@forEach
                hook(m).intercept {
                    logOnce(m.name + "() -> true")
                    true
                }
                n++
            }
            log("FilterGroupManager is*() hooked=$n")
        }.onFailure { log("hook is*(): $it") }

        // 2) 进任何 init*FilterGroup 之前把 sbXxxSupport 补成 true，并顺便打一组数量
        runCatching {
            var n = 0
            cls.declaredMethods.forEach { m ->
                if (!Modifier.isStatic(m.modifiers)) return@forEach
                if (!m.name.startsWith("init") || !m.name.endsWith("FilterGroup")) return@forEach
                hook(m).intercept { chain ->
                    forceFilterFlags(cls)
                    val r = chain.proceed()
                    runCatching {
                        val arg = chain.args.firstOrNull()
                        if (arg != null) logOnce(m.name + " -> " + describeGroup(arg))
                    }
                    r
                }
                n++
            }
            log("init*FilterGroup hooked=$n")
        }.onFailure { log("hook init*FilterGroup: $it") }

        // 3) 兜底：内部走 getConfigBooleanValue(key) 的一律放行
        runCatching {
            var n = 0
            cls.declaredMethods.forEach { m ->
                if (m.name != "getConfigBooleanValue" || m.parameterTypes.size != 1) return@forEach
                if (m.parameterTypes[0] != String::class.java) return@forEach
                hook(m).intercept { chain ->
                    val k = chain.getArg(0) as? String
                    if (k != null && k in FILTER_KEYS) {
                        logOnce("force $k -> true")
                        true
                    } else if (k != null && SWITCH_KEYS.containsKey(k) && switchOn(SWITCH_KEYS.getValue(k))) {
                        logOnce("force $k -> true (switch " + SWITCH_KEYS.getValue(k) + ")")
                        true
                    } else chain.proceed()
                }
                n++
            }
            log("FilterGroupManager.getConfigBooleanValue hooked=$n")
        }.onFailure { log("hook FilterGroupManager.getConfigBooleanValue: $it") }

        // 4) 鲜活/节日/庆典：initFilterGroup 里是无条件 addFrontAndBack 的，用户要求开关控制
        runCatching {
            val fg = cl.loadClass(FILTER_GROUP)
            var n = 0
            fg.declaredMethods.forEach { m ->
                if (m.name != "addFrontAndBack" || m.parameterTypes.size != 2) return@forEach
                hook(m).intercept { chain ->
                    val lut = chain.args.firstOrNull {
                        it is String && SWITCH_LUT.containsValue(it)
                    } as? String
                    val sw = lut?.let { l -> SWITCH_LUT.entries.firstOrNull { it.value == l }?.key }
                    if (sw != null && !switchOn(sw)) {
                        logOnce("skip " + lut + "（" + sw + " 开关关着）")
                        null
                    } else chain.proceed()
                }
                n++
            }
            log("FilterGroup.addFrontAndBack hooked=$n")
        }.onFailure { log("hook addFrontAndBack: $it") }
    }

    /** 把 FILTER_FLAG_FIELDS 里的静态布尔字段设为 true（幂等，失败不抛） */
    private fun forceFilterFlags(cls: Class<*>) {
        FILTER_FLAG_FIELDS.forEach { name -> setFlag(cls, name) }
        FILTER_FLAG_SWITCH.forEach { (field, sw) -> if (switchOn(sw)) setFlag(cls, field) }
    }

    private fun setFlag(cls: Class<*>, name: String) {
        runCatching {
            val f = cls.getDeclaredField(name)
            f.isAccessible = true
            if (!f.getBoolean(null)) {
                f.setBoolean(null, true)
                logOnce("flag $name -> true")
            }
        }
    }

    /** 读开关：persist.camunlock.filter.<name>，默认 false */
    private fun switchOn(name: String): Boolean = synchronized(switchCache) {
        switchCache.getOrPut(name) {
            runCatching {
                val c = Class.forName("android.os.SystemProperties")
                val m = c.getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
                m.invoke(null, "persist.camunlock.filter." + name, false) as Boolean
            }.getOrDefault(false).also { logOnce("switch $name = $it") }
        }
    }

    /** 诊断用：把一个 FilterGroup 里列表的长度描述出来 */
    private fun describeGroup(group: Any): String {
        var c: Class<*>? = group.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!List::class.java.isAssignableFrom(f.type)) continue
                runCatching {
                    f.isAccessible = true
                    val v = f.get(group) as? List<*>
                    if (v != null) return "size=" + v.size
                }
            }
            c = c.superclass
        }
        return "size=?"
    }

    // ------------------------------------------------------------ palette colors

    /**
     * 调色盘面板的 Seekbar 会调 PaletteSeekbarColors.get()，
     * 而它依赖的 PaletteSeekbarColorTable / ipusdk 类在这台机器上不存在，
     * 一 inflate 就 NoClassDefFoundError。
     *
     * 这里在 get(String) 出口直接返回一个 stub Recipe（或 null），不再进原方法。
     */
    private fun hookPaletteColors(cl: ClassLoader) {
        // 换过 Reno17p 的 unit.sdk 之后真实取色表类都在了（注意包名是 ipusdk 不是 ipuapi），
        // 有依赖就不打桩，让真实现算颜色；否则格子会被 Recipe.empty() 画成全黑。
        val dep = runCatching {
            cl.loadClass("com.oplus.ocs.camera.ipusdk.processunit.filter.palette.PaletteSeekbarColorTable")
        }.getOrNull()
        if (dep != null) {
            log("palette colors: PaletteSeekbarColorTable 存在，跳过打桩（用真实现）")
            return
        }
        val classes = listOf(
            "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteColors",
            "com.oplus.ocs.camera.ipuapi.process.filter.palette.PaletteSeekbarColors"
        )
        classes.forEach { className ->
            val cls = runCatching { cl.loadClass(className) }.getOrNull()
            if (cls == null) {
                log("WARN: $className not found")
                return@forEach
            }
            var n = 0
            var trackers = 0
            cls.declaredMethods.forEach { m ->
                if (!Modifier.isStatic(m.modifiers)) return@forEach
                if (m.parameterTypes.size != 1 || m.parameterTypes[0] != String::class.java) return@forEach
                if (!m.returnType.name.endsWith("Recipe")) return@forEach
                runCatching {
                    hook(m).intercept {
                        val stub = makeRecipe(m.returnType)
                        logOnce("stub ${m.returnType.simpleName}.get -> ${if (stub == null) "null" else "recipe"}")
                        stub
                    }
                    n++
                }.onFailure { log("hook $className.${m.name}: $it") }
            }
            log("$className get/Recipe hooked=$n")
        }
    }

    /** 造一个最小可用的 Recipe：优先 empty()，其次 int[] 构造。失败返回 null。 */
    private fun makeRecipe(rt: Class<*>): Any? {
        val colors = intArrayOf(0xFF3A6EA5.toInt(), 0xFF808080.toInt(), 0xFFE8C48A.toInt())
        return runCatching {
            runCatching { rt.getMethod("empty").invoke(null) }.getOrNull()
                ?: runCatching { rt.getConstructor(IntArray::class.java).newInstance(colors) }.getOrNull()
        }.getOrNull()
    }

    // ------------------------------------------------------------ 构图调用观察
    /** 只读观察：相机自己有没有走 native 构图这条路。 */
    private fun watchComposition(cl: ClassLoader) {
        val cls = runCatching {
            cl.loadClass("com.oplus.camera.aicomposition.OplusAIComposition")
        }.getOrNull() ?: run { logOnce("watch: OplusAIComposition 不存在"); return }

        val watchNames = setOf(
            "initTracker", "initializeTracker", "nativeInitComposition",
            "nativeRunComposition", "nativeRunCompositionStreaming", "nativeRelease"
        )
        var n = 0
        cls.declaredMethods.forEach { m ->
            if (m.name !in watchNames) return@forEach
            runCatching {
                hook(m).intercept { chain ->
                    runCatching {
                        val args = chain.args.joinToString(",") { a ->
                            when (a) {
                                null -> "null"
                                is android.hardware.HardwareBuffer -> "HB(${a.width}x${a.height})"
                                is Long, is Int, is Float -> a.toString()
                                is Array<*> -> "arr${a.size}"
                                else -> a.javaClass.simpleName
                            }
                        }
                        logOnce("comp ${m.name}($args)")
                    }
                    val r = chain.proceed()
                    if (m.name.startsWith("nativeRun")) {
                        logOnce("comp ${m.name} -> ${r?.javaClass?.simpleName ?: "void"}")
                    }
                    r
                }
                n++
            }.onFailure { log("watch ${m.name}: $it") }
        }
        logOnce("watchComposition hooked=$n")
    }
    // ------------------------------------------------------------ 取帧探测
    /**
     * 找构图取帧的入口：把相机里跟 HardwareBuffer / AIUnit 沾边的方法签名先打出来，
     * 只读不动，确认真正的取帧点之后再接桥。
     */
    private fun probeFrameApi(cl: ClassLoader) {
        listOf(
            "com.oplus.camera.common.utils.HardwareBufferUtils",
            "com.oplus.ocs.camera.OplusAICompositionHelper",
            "com.oplus.camera.aicomposition.OplusAIComposition"
        ).forEach { name ->
            val cls = runCatching { cl.loadClass(name) }.getOrNull()
            if (cls == null) {
                logOnce("probe: $name 不存在")
                return@forEach
            }
            cls.declaredMethods.take(12).forEach { m ->
                logOnce("probe ${cls.simpleName}#${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        }
        // 顺带把自带插件 dex 的框架类摸一下，确认能加载
        val extra = java.io.File(currentApplication()?.filesDir, "aiunit/feiyu_aiunit_bridge.dex")
        logOnce("probe aiunit dex: ${if (extra.isFile) "ready ${extra.length()}" else "未部署"}")
    }
    // ------------------------------------------------------------ 灵感构图取帧桥

    /**
     * 灵感构图的"出图"这一段。
     *
     * 相机本来只在 feature 打开时才把预览帧送进构图服务；这台机器上 feature 关了，
     * 于是走不成。这里自己接：
     *
     *   取帧类（wb.r / rb.t）的帧方法 → 记一份预览帧
     *   runCompositionStreaming(HardwareBuffer, Callback) 返回后 → 云端构图 → 回调投递
     *
     * 投递走回调的 onEvent(int, String)：1 和 2 两个事件，linggan 那边实测就是这个。
     */
    private fun hookComposition(cl: ClassLoader) {
        val apk = moduleApkPath()
        AiBridge.attach(cl, apk)
        log("构图桥: apk=$apk")
        hookFrameSource(cl)

        var entries = 0
        COMPOSITION_ENTRIES.forEach { name ->
            val cls = runCatching { cl.loadClass(name) }.getOrNull()
            if (cls == null) {
                log("构图入口缺失: $name")
                return@forEach
            }
            var n = 0
            var trackers = 0
            cls.declaredMethods.forEach { m ->
                if (m.name == "runCompositionStreaming" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == HardwareBuffer::class.java
                ) {
                    runCatching {
                        hook(m).intercept { chain ->
                            val original = chain.proceed()
                            val delivered = runCatching { deliver(chain.args) }
                                .onFailure { log("deliver 异常 $it") }
                                .getOrDefault(false)
                            if (delivered) true else original
                        }
                        n++
                    }.onFailure { log("挂流式构图失败: $it") }
                }
                if (m.name == "reset" && m.parameterTypes.isEmpty()) {
                    runCatching {
                        hook(m).intercept { chain ->
                            AiBridge.resetDelivery()
                            trackLogged = false
                            chain.proceed()
                        }
                    }.onFailure { log("挂 reset 失败: $it") }
                }
                // 构图跟踪：相机自带 native 跟踪器一直判"画面变化较大，已退出本次构图"，
                // 这里把"参考构图现在在画面哪儿"换成陀螺仪积分，直接回传。
                if (m.name == "initializeTracker" && m.parameterTypes.size == 8) {
                    runCatching {
                        hook(m).intercept { chain ->
                            chain.proceed()
                            runCatching { onTrackerInit(chain.args) }
                            true
                        }
                        trackers++
                    }.onFailure { log("挂 initializeTracker 失败: $it") }
                }
                if (m.name == "track" && m.parameterTypes.size == 5) {
                    runCatching {
                        hook(m).intercept { chain ->
                            chain.proceed()
                            onTrack(chain.args)
                        }
                        trackers++
                    }.onFailure { log("挂 track 失败: $it") }
                }
            }
            if (n > 0) {
                entries += n
                log("已挂 $name 流式构图 x$n")
            }
            if (trackers > 0) log("已挂 $name 构图跟踪 x$trackers")
        }
        if (entries == 0) log("没有可用的构图入口") else log("灵感构图已接入云端")
        if (TRACE_COMPOSITION) traceComposition(cl)
    }

    /**
     * 临时诊断：把构图入口类的方法调用都记一遍（每个方法第一条），
     * 用来确认相机在"灵感构图"界面到底走没走这套 API。定位完就把 TRACE_COMPOSITION 关掉。
     */
    private fun traceComposition(cl: ClassLoader) {
        COMPOSITION_ENTRIES.forEach { name ->
            val cls = runCatching { cl.loadClass(name) }.getOrNull() ?: return@forEach
            cls.declaredMethods.forEach { m ->
                runCatching {
                    hook(m).intercept { chain ->
                        val args = chain.args.joinToString(",") { a ->
                            when (a) {
                                null -> "null"
                                is HardwareBuffer -> "HB(${a.width}x${a.height})"
                                is Number, is Boolean -> a.toString()
                                is Array<*> -> "arr${a.size}"
                                else -> a.javaClass.simpleName
                            }
                        }
                        logOnce("call ${cls.simpleName}#${m.name}($args)")
                        chain.proceed()
                    }
                }
            }
        }
        logOnce("构图调用跟踪已挂")
    }

    /** 临时诊断：「灵感推荐」的鉴权/网络方法调用，连带异常一起记。 */
    private fun traceInspiration(cl: ClassLoader) {
        INSPIRATION_CLASSES.forEach { name ->
            val cls = runCatching { cl.loadClass(name) }.getOrNull() ?: run {
                log("灵感: 类不存在 $name")
                return@forEach
            }
            cls.declaredMethods.forEach { m ->
                runCatching {
                    hook(m).intercept { chain ->
                        try {
                            if (m.name == "<init>") {
                                AiBridge.log("灵感 ${cls.simpleName}<init> 参数 " + chain.args.joinToString(" | "))
                            }
                            val r = chain.proceed()
                            AiBridge.logOnce("灵感 ${cls.simpleName}#${m.name} -> " + r)
                            if (m.name == "getHttpCode") {
                                val e = runCatching { chain.thisObject }.getOrNull()
                                val msg = runCatching { (e as Throwable).message }.getOrNull()
                                val cause = runCatching { (e as Throwable).cause?.toString() }.getOrNull()
                                AiBridge.log("灵感 异常消息: $msg · cause=$cause")
                            }
                            r
                        } catch (t: Throwable) {
                            AiBridge.log("灵感 ${cls.simpleName}#${m.name} 抛 ${t::class.java.simpleName}: ${t.message}")
                            throw t
                        }
                    }
                }.onFailure { log("挂灵感 ${cls.simpleName}#${m.name} 失败: $it") }
            }
        }
        log("灵感链路跟踪已挂")
    }

    /** 取帧类：预览帧在相机线程上路过这里，顺手记一份。 */
    private fun hookFrameSource(cl: ClassLoader) {
        // 不能"第一个存在的类就是对的"：TB 包里 tb.u / tb.q / tb.d 在不同版本里都存在，
        // 但只有其中一个带「11 参 + ≥2 HardwareBuffer」的帧方法。逐个试，谁有方法用谁。
        var n = 0
        for (name in FRAME_CLASSES) {
            if (n > 0) break
            val cls = runCatching { Class.forName(name, false, cl) }.getOrNull() ?: continue
            log("取帧候选类: ${cls.name}")
            cls.declaredMethods.forEach { m ->
                if (n > 0) return@forEach
                if (m.parameterCount != 11) return@forEach
                if (m.parameterTypes.count { it == HardwareBuffer::class.java } < 2) return@forEach
                runCatching {
                    hook(m).intercept { chain ->
                        runCatching {
                            val picked = AiBridge.pickFrame(chain.args)
                            AiBridge.logOnce("取帧 " + AiBridge.describeFrames(chain.args) + " -> " + AiBridge.describe(picked))
                            AiBridge.keepFrame(picked)
                        }
                        chain.proceed()
                    }
                    n++
                    log("取帧类命中: ${cls.name} / 方法: ${m.name}")
                }.onFailure { log("取帧方法挂钩失败: $it") }
            }
        }
        if (n == 0) log("取帧类未命中: $FRAME_CLASSES")
        log("取帧方法 hooked=$n")
    }

    /**
     * 构图跟踪初始化：起陀螺仪，并把这一帧当作原点（`anchor`）。
     * 返回 true 告诉相机"跟踪可用"，别走 native 那套判"画面变化较大"的逻辑。
     */
    private fun onTrackerInit(args: List<Any?>) {
        val app = currentApplication() ?: return
        GyroTracker.start(app)
        val hb = args.getOrNull(0) as? HardwareBuffer
        val w = hb?.width?.toFloat() ?: 768f
        val h = hb?.height?.toFloat() ?: 1024f
        GyroTracker.anchor(w, h)
    }

    /**
     * 每帧回传：参考构图的中心点 + 陀螺仪偏移，格式 {cx, cy, 0, 3}。
     * 相机拿这个值画引导点/判断"对准了没有"。
     */
    private fun onTrack(args: List<Any?>): FloatArray {
        val hb = args.getOrNull(0) as? HardwareBuffer
        val w = hb?.width?.toFloat() ?: 768f
        val h = hb?.height?.toFloat() ?: 1024f
        val off = GyroTracker.offsetPixels()
        val cx = 384f * w / 768f + off[0]
        val cy = 512f * h / 1024f + off[1]
        synchronized(logged) {
            if (!trackLogged) {
                trackLogged = true
                log("跟踪回传 (${cx.toInt()},${cy.toInt()})")
            }
        }
        return floatArrayOf(cx, cy, 0f, 3f)
    }

    /** 云端构图结果 → 相机的推荐列表。返回 true 表示这一次已经由我们投递。 */
    private fun deliver(args: List<Any?>?): Boolean {
        if (args == null || args.size < 2) return false
        if (args[0] !is HardwareBuffer) return false
        val callback = args[1] ?: return false
        if (AiBridge.isDelivered()) return false

        // 用取帧类抓到的那份：它是 RGBA_8888，能直接转 Bitmap。
        // 构图调用自己带的帧常是 RGB_888 / 私有格式，转不出画面。
        AiBridge.logOnce("构图帧(参数) " + AiBridge.describe(AiBridge.pickFrame(args)))
        val frame = AiBridge.takeFrame() ?: AiBridge.pickFrame(args) ?: run {
            logOnce("deliver: 没有可用帧")
            return false
        }
        val w = runCatching { frame.width }.getOrDefault(0)
        val h = runCatching { frame.height }.getOrDefault(0)
        if (w <= 0 || h <= 0) {
            logOnce("deliver: 帧已释放")
            return false
        }
        val app = currentApplication() ?: run {
            logOnce("deliver: 拿不到 application")
            return false
        }
        val cloud = AiBridge.compose(app, frame, 1.0f) ?: run {
            logOnce("deliver: 云端无结果")
            return false
        }
        AiBridge.logOnce("云端已回，开始翻译 (${w}x${h})")
        val cameraJson = AiBridge.toCameraJson(cloud, w, h) ?: run {
            logOnce("deliver: 翻译失败 $cloud")
            return false
        }
        AiBridge.logOnce("翻译完成，投递");

        AiBridge.logOnce("回调类型 ${callback.javaClass.name}")
        val ok = runCatching {
            val onEvent = findOnEvent(callback.javaClass) ?: run {
                AiBridge.logOnce(
                    "回调没有 onEvent: ${callback.javaClass.name} -> " +
                        callback.javaClass.methods.joinToString("; ") {
                            "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})"
                        }
                )
                return@runCatching false
            }
            AiBridge.logOnce("找到 ${onEvent.name}(${onEvent.parameterTypes.joinToString { it.simpleName }})，投 1")
            onEvent.invoke(callback, 1, cameraJson)
            AiBridge.logOnce("投 2")
            onEvent.invoke(callback, 2, cameraJson)
            AiBridge.logOnce("投 2 完成")
            true
        }.getOrElse {
            logOnce("回调失败 $it")
            false
        }
        if (ok) {
            AiBridge.markDelivered()
            logOnce("已送入推荐 $cameraJson")
        }
        return ok
    }

    /** 回调的 onEvent(int, String)。接口声明的也算，所以沿类/接口都翻一遍。 */
    private fun findOnEvent(cls: Class<*>): java.lang.reflect.Method? {
        val queue = ArrayDeque<Class<*>>()
        val seen = HashSet<Class<*>>()
        queue.add(cls)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            c.methods.forEach { m ->
                if (m.name == "onEvent" &&
                    m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    m.parameterTypes[1] == String::class.java
                ) return m
            }
            c.interfaces.forEach { queue.add(it) }
            c.superclass?.let { queue.add(it) }
        }
        return null
    }

    private fun moduleApkPath(): String? = runCatching { getModuleApplicationInfo().sourceDir }.getOrNull()

    // ------------------------------------------------------------ deploy

    private fun ensureDeployed(): Boolean {
        if (deployed) return true
        synchronized(lock) {
            if (deployed) return true
            val app = currentApplication()
            if (app == null) {
                log("WARN: currentApplication null, cannot deploy")
                return false
            }
            return runCatching {
                val base = File(app.filesDir, "campalette/basictone")
                val setting = File(base, "setting")
                val palette = File(base, "palette")
                val odt = File(base, "odt")
                val stamp = File(base, ".extract")

                val cached = stamp.isFile && runCatching { stamp.readText().trim() }.getOrNull() == EXTRACT
                if (cached && File(palette, "HDR/SC16Lut8").length() == LUT_SIZE) {
                    return activate(base, setting, palette, odt, "cached")
                }

                base.deleteRecursively()
                listOf(setting, odt).forEach { it.mkdirs() }

                // 1) 提取包里的配置文件（setting/*.ini、palette/HDR|SDR/SC16Lut0..8）原样铺开
                val info = getModuleApplicationInfo()
                var extracted = 0
                ZipFile(info.sourceDir).use { zip ->
                    zip.entries().toList()
                        .filter { !it.isDirectory && it.name.startsWith(ASSET_ROOT) }
                        .forEach { entry ->
                            val dst = File(base, entry.name.substring(ASSET_ROOT.length))
                            dst.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                dst.outputStream().use { output -> input.copyTo(output) }
                            }
                            extracted++
                        }
                }

                // 2) ODT 是 12 MB 的二进制，机器上已经有同一份，直接拷现成的
                copyDir(File("$ODM/odt"), odt)
                val pre = File(odt, "ODT_Pre")
                val photo = File(odt, "ODT_Photo")
                if (!pre.isFile && photo.isFile) photo.copyTo(pre, overwrite = true)

                val hdr = File(palette, "HDR/SC16Lut8")
                val sdr = File(palette, "SDR/SC16Lut8")
                if (hdr.length() != LUT_SIZE || sdr.length() != LUT_SIZE) {
                    log("deploy aborted: HDR=${hdr.length()} SDR=${sdr.length()} files=$extracted")
                    return false
                }

                stamp.writeText(EXTRACT)
                activate(base, setting, palette, odt, "extracted=$extracted")
            }.getOrElse {
                log("deploy failed: $it")
                false
            }
        }
    }

    private fun activate(
        base: File,
        setting: File,
        palette: File,
        odt: File,
        how: String
    ): Boolean {
        settingPath = setting.absolutePath
        palettePath = palette.absolutePath
        odtPath = odt.absolutePath
        deployed = true
        val files = base.walkTopDown().count { it.isFile }
        log("deployed ($how) -> ${base.absolutePath} files=$files")
        return true
    }

    private fun copyDir(src: File, dst: File) {
        val files = src.listFiles() ?: return
        files.forEach { f ->
            if (f.isFile) {
                runCatching { f.copyTo(File(dst, f.name), overwrite = true) }
            }
        }
    }

    private fun currentApplication(): Application? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        at.getMethod("currentApplication").invoke(thread) as? Application
    }.getOrNull()

    private fun logOnce(msg: String) {
        synchronized(logged) {
            if (logged.size < 200 && logged.add(msg)) log(msg)
        }
    }

    private fun log(msg: String) = log(Log.INFO, TAG, msg)
}
