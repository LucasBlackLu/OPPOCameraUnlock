package com.oppocamera.unlock.aicomp

import android.content.Context
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.util.Log
import dalvik.system.InMemoryDexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * 构图取帧 + 云端推图。
 *
 * 相机的「灵感构图」本身分两段：
 *
 *   1. feature / 配置位开关（CameraConfig 里的 aicomposition 那一串）—— 打开后入口出来；
 *   2. 真正出图：预览帧 → OPPO 相机自带的 AIUnit 云单元 `cloud_ai_shot_suggestions`
 *      → 返回 {zoom, center, reason} → 相机 UI 侧的推荐列表。
 *
 * 第 2 段在官方相机里叫「推荐构图」，走的是 OPPO 自己的云服务；本机这套能力是齐的，
 * 只是 feature 关着时不会有帧进去。这里做的就是把帧喂进那个云单元，再把结果翻译成
 * 相机推荐列表认的 JSON（outputNum / crops / srcCrop / compositionDesc）。
 *
 * AIUnit 的接入方式（连接器、slot、动作回调）靠反射 + 一份 1.5 KB 的桥接 dex：
 *
 *   assets/aiunit/feiyu_aiunit_bridge.dex
 *     com.trae.feiyu.cloud.FeiyuFrameDetector  extends com.oplus.aiunit.core.base.FrameDetector
 *     com.trae.feiyu.cloud.FeiyuConnector      extends com.oplus.aiunit.core.a
 *
 * 这份 dex 里只有"声明"，真正的实现在相机里（com.oplus.aiunit.*），所以不能直接编译进模块，
 * 必须在相机进程里用相机自己的 ClassLoader 当 parent 动态加载。
 */
object AiBridge {

    private const val TAG = "CamAicomp"

    /** AIUnit 云单元名（相机里现成的）。 */
    private const val UNIT = "cloud_ai_shot_suggestions"

    /** 连接器动作名，只影响日志/toString。 */
    private const val ACTOR = "FeiyuCloudShot"

    private const val STUB_ENTRY = "assets/aiunit/feiyu_aiunit_bridge.dex"

    private const val REQ_PREFIX = "{\"aiCapData\":{\"zoom\":"
    private const val REQ_MID = ",\"maxZoom\":6.0,\"sensor\":[0,0,0],\"lang\":\""

    // ---------------------------------------------------------------- 通道

    @Volatile private var tried = false
    @Volatile private var detector: Any? = null
    @Volatile private var connector: Any? = null
    @Volatile private var actionInterface: Class<*>? = null
    @Volatile private var createInput: Method? = null
    @Volatile private var createOutput: Method? = null
    @Volatile private var runAction: Method? = null
    @Volatile private var setFrame: Method? = null
    @Volatile private var inputJson: Field? = null
    @Volatile private var outputJson: Field? = null
    @Volatile private var process: Method? = null

    /** 相机进程的 ClassLoader（拿 com.oplus.aiunit.* / HardwareBufferUtils 用）。 */
    @Volatile private var appLoader: ClassLoader? = null

    /** 模块自己的 APK 路径（桥接 dex 在它的 assets 里）。 */
    @Volatile private var moduleApk: String? = null

    /** 预览帧：由取帧类（wb.r / rb.t）的帧方法写，构图调用读。 */
    private val frameHolder = ThreadLocal<HardwareBuffer?>()

    /** 一次构图只投递一次，构图 reset 时复位。 */
    @Volatile private var delivered = false

    private val logged = HashSet<String>()

    fun attach(cameraLoader: ClassLoader, apkPath: String?) {
        appLoader = cameraLoader
        moduleApk = apkPath
        log("attach · apk=${apkPath ?: "null"}")
    }

    /**
     * 一次构图只投一次：投完等 [DELIVERY_WINDOW_MS] 才允许重新推荐。
     *
     * 相机每十几秒就会再调一次 runCompositionStreaming，如果每次都重投，它会不停
     * 按新裁剪重新变焦，观感上就是"画面一直在跳"。真正想换一张，点相机的「重新构图」，
     * 隔一会儿自然就过窗口了。
     */
    private const val DELIVERY_WINDOW_MS = 30_000L

    @Volatile private var deliveredAt = 0L

    fun resetDelivery() {
        delivered = false
        deliveredAt = 0L
    }

    fun isDelivered(): Boolean =
        delivered && System.currentTimeMillis() - deliveredAt <= DELIVERY_WINDOW_MS

    fun markDelivered() {
        delivered = true
        deliveredAt = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- 取帧

    /** 抓预览帧放到当前线程；只有被取帧类的方法调到时才会走这里。 */
    fun keepFrame(hb: HardwareBuffer?) {
        if (hb == null || hb.isClosed) return
        frameHolder.set(hb)
    }

    /** 取走当前线程的预览帧。 */
    fun takeFrame(): HardwareBuffer? {
        val hb = frameHolder.get()
        frameHolder.remove()
        return if (hb == null || hb.isClosed) null else hb
    }

    /**
     * 从方法参数里挑预览帧。
     *
     * 规则照 linggan：参数第 2 个（下标 1）是 HardwareBuffer 就直接用它；
     * 否则在所有参数里找带 GPU 用法位（framebuffer 0x200 / sampled 0x100）的，
     * 取面积最大的一张，都没有就退回第一张。
     */
    fun pickFrame(args: List<Any?>?): HardwareBuffer? {
        args ?: return null
        if (args.size > 1) {
            val second = args[1]
            if (second is HardwareBuffer && !second.isClosed) return second
        }
        var first: HardwareBuffer? = null
        var best: HardwareBuffer? = null
        for (a in args) {
            if (a !is HardwareBuffer || a.isClosed) continue
            if (first == null) first = a
            val usage = a.usage
            val gpu = (usage and 0x200L) != 0L || (usage and 0x100L) != 0L
            if (!gpu) continue
            if (best == null || a.width.toLong() * a.height > best.width.toLong() * best.height) {
                best = a
            }
        }
        return best ?: first
    }

    /** 把参数里所有 HardwareBuffer 描述出来，用于诊断选帧。 */
    fun describeFrames(args: List<Any?>?): String = (args ?: emptyList()).mapIndexedNotNull { i, a ->
        if (a is HardwareBuffer) "[$i]${describe(a)}" else null
    }.joinToString(" ")

    // ---------------------------------------------------------------- 构图

    /**
     * 一步到位：帧 → 云端 JSON（相机原始返回，未翻译）。
     * 超时 12 秒，失败返回 null，不影响相机自己的流程。
     */
    fun compose(ctx: Context?, hb: HardwareBuffer?, zoom: Float): String? {
        if (ctx == null || hb == null) return null
        appLoader = ctx.classLoader
        if (hb.isClosed) return null
        if (!ensure()) return null

        val bmp = toBitmap(hb) ?: run {
            logOnce("帧转换失败 ${describe(hb)}")
            return null
        }
        val request = buildRequest(zoom)
        val result = AtomicReference<String?>(null)
        val done = CountDownLatch(1)

        val worker = Thread({
            try {
                result.set(invoke(request, bmp))
            } catch (t: Throwable) {
                log("调用失败 $t")
            } finally {
                done.countDown()
            }
        }, "FeiyuAICompose")
        worker.isDaemon = true
        worker.start()

        try {
            done.await(12, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // 忽略：超时/中断都按"没结果"处理
        }
        runCatching { bmp.recycle() }

        val json = result.get()
        if (json != null) log("云端 $json")
        return json
    }

    private fun buildRequest(zoom: Float): String =
        REQ_PREFIX + zoom + REQ_MID + java.util.Locale.getDefault().toLanguageTag() + "\"}}"

    /** 通过 AIUnit 连接器跑一次动作；结果从输出 slot 的 String 字段里读。 */
    private fun invoke(request: String, bmp: Bitmap): String? {
        val ai = actionInterface ?: return null
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "run" -> runActionOnce(request, bmp)
                "failure" -> null
                "toString" -> ACTOR
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args != null && args.isNotEmpty() && proxy === args[0]
                else -> null
            }
        }
        val cl = appLoader ?: javaClass.classLoader
        val action = Proxy.newProxyInstance(cl, arrayOf(ai), handler)
        val ret = runAction?.invoke(connector, action)
        return ret as? String
    }

    private fun runActionOnce(request: String, bmp: Bitmap): Any? = runCatching {
        val det = detector ?: return@runCatching null
        val input = createInput?.invoke(det) ?: return@runCatching null
        val output = createOutput?.invoke(det) ?: return@runCatching null

        // setFrame(Bitmap, Boolean) 返回 0 才算接受这一帧
        val code = setFrame?.invoke(input, bmp, java.lang.Boolean.TRUE)
        if (code !is Int || code != 0) {
            log("setFrame code=$code")
            return@runCatching null
        }
        inputJson?.set(input, request)
        val errorCode = process?.invoke(det, input, output)
        logOnce("process -> $errorCode")
        outputJson?.get(output)
    }.getOrElse {
        log("run 异常 $it")
        null
    }

    // ---------------------------------------------------------------- 初始化

    private fun ensure(): Boolean = synchronized(this) {
        if (tried) return detector != null
        tried = true
        try {
            val parent = appLoader ?: javaClass.classLoader
            val bytes = readStub() ?: run {
                log("桥接 dex 缺失")
                return false
            }
            val loader = InMemoryDexClassLoader(ByteBuffer.wrap(bytes), parent)

            val detCls = Class.forName("com.trae.feiyu.cloud.FeiyuFrameDetector", true, loader)
            val connCls = Class.forName("com.trae.feiyu.cloud.FeiyuConnector", true, loader)

            detector = detCls
                .getConstructor(Context::class.java, String::class.java)
                .newInstance(context(), UNIT)

            val base = detCls.superclass
            connector = connCls
                .getConstructor(Context::class.java, base, String::class.java)
                .newInstance(context(), detector, ACTOR)

            createInput = base.getMethod("createInputSlot")
            createOutput = base.getMethod("createOutputSlot")

            val connBase = connCls.superclass
            actionInterface = connBase.declaredClasses.firstOrNull { it.isInterface }
            val action = actionInterface ?: run {
                log("找不到动作接口")
                detector = null
                return false
            }
            runAction = connBase.getMethod("c", action)

            val input = createInput!!.invoke(detector)
            val output = createOutput!!.invoke(detector)
            setFrame = findFrameSetter(input!!.javaClass)
            inputJson = findStringField(input.javaClass)
            outputJson = findStringField(output!!.javaClass)
            process = findProcess(base, input.javaClass, output.javaClass)

            if (setFrame != null && inputJson != null && outputJson != null && process != null) {
                log("通道已就绪 (unit=$UNIT)")
            } else {
                log("接口不匹配 setFrame=$setFrame in=$inputJson out=$outputJson process=$process")
                log("a 的方法: " + connBase.methods.joinToString("; ") {
                    "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}):${it.returnType.simpleName}"
                })
                log("a 的内部类: " + connBase.declaredClasses.joinToString("; ") { c ->
                    "${c.simpleName}[${if (c.isInterface) "i" else "c"}]( " +
                        c.methods.joinToString(",") { it.name } + " )"
                })
                log("FrameDetector 的方法: " + base.methods.joinToString("; ") {
                    "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}):${it.returnType.simpleName}"
                })
                log("slot 方法 in=" + input!!.javaClass.methods.joinToString(",") { it.name } +
                    " out=" + output!!.javaClass.methods.joinToString(",") { it.name })
                detector = null
            }
        } catch (t: Throwable) {
            log("初始化失败 ${t} · cause=${(t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause}")
            detector = null
        }
        detector != null
    }

    private fun context(): Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        // 必须用相机的 application，不能拿 system context：
        // AIUnit 的连接器要读相机自己的 unit 配置，system context 的 ClassLoader 里也没有 com.oplus.aiunit
        (at.getMethod("currentApplication").invoke(thread) as? Context)
            ?: (at.getMethod("getSystemContext").invoke(thread) as? Context)
    }.getOrNull()

    /** 从模块 APK 的 assets 里读桥接 dex。 */
    private fun readStub(): ByteArray? {
        val apk = moduleApk ?: return null
        return runCatching {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(STUB_ENTRY) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrElse {
            log("读桥接 dex 失败 $it")
            null
        }
    }

    /** 找 setFrame(Bitmap, Boolean): int */
    private fun findFrameSetter(cls: Class<*>): Method? {
        for (m in cls.methods) {
            val p = m.parameterTypes
            if (p.size != 2) continue
            if (p[0] != Bitmap::class.java) continue
            if (p[1] != java.lang.Boolean::class.java) continue
            if (m.returnType != Int::class.javaPrimitiveType) continue
            return m
        }
        return null
    }

    /** 找输入/输出 slot 里那个 String 字段（请求 / 返回都塞在这里）。 */
    private fun findStringField(cls: Class<*>): Field? {
        for (f in cls.fields) if (f.type == String::class.java) return f
        for (f in cls.declaredFields) {
            if (f.type != String::class.java) continue
            runCatching { f.isAccessible = true }
            return f
        }
        return null
    }

    /**
     * 找"跑一次"的方法。这个版本的 AIUnit 在 FrameDetector 上是
     * `f(j, k): ErrorCode`（输入 slot、输出 slot 各一个），返回错误码而不是 void。
     */
    private fun findProcess(base: Class<*>, inputCls: Class<*>, outputCls: Class<*>): Method? {
        for (m in base.methods) {
            val p = m.parameterTypes
            if (p.size != 2) continue
            if (!p[0].isAssignableFrom(inputCls)) continue
            if (!p[1].isAssignableFrom(outputCls)) continue
            return m
        }
        return null
    }

    // ---------------------------------------------------------------- 帧 → Bitmap

    /**
     * 帧 → Bitmap。三条路，按代价从低到高试：
     *
     *   1. 相机自带的 HardwareBufferUtils.copyHardwareBufferToBitmap（最快，GL 拷贝）
     *   2. 直接 lockBuffer 拿 ByteBuffer，按 RGB_888 / RGBA_8888 手工铺像素
     *   3. Bitmap.wrapHardwareBuffer（要求 buffer 带 GPU_SAMPLED 用法位，常常不满足）
     */
    private fun toBitmap(hb: HardwareBuffer): Bitmap? {
        val util = hardwareBufferUtils()

        if (util != null) {
            val byUtil = runCatching {
                val m = util.getMethod(
                    "copyHardwareBufferToBitmap",
                    HardwareBuffer::class.java,
                    Bitmap::class.java
                )
                val bmp = Bitmap.createBitmap(hb.width, hb.height, Bitmap.Config.ARGB_8888)
                m.invoke(null, hb, bmp)
                bmp
            }.getOrElse {
                logOnce("util 转换异常 $it")
                null
            }
            if (byUtil != null && !isBlank(byUtil)) return byUtil
        }

        if (util != null) {
            val byLock = toBitmapByLock(util, hb)
            if (byLock != null && !isBlank(byLock)) return byLock
        }

        return runCatching {
            val wrapped = Bitmap.wrapHardwareBuffer(hb, null) ?: return@runCatching null
            val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
            if (isBlank(copy)) null else copy
        }.getOrElse { null }
    }

    /** 用 HardwareBufferUtils.lockBuffer + stride 手工铺一张软件位图。 */
    private fun toBitmapByLock(util: Class<*>, hb: HardwareBuffer): Bitmap? = runCatching {
        val lock = util.getMethod("lockBuffer", HardwareBuffer::class.java)
        val unlock = util.getMethod("unlockBuffer", HardwareBuffer::class.java)
        val stride = runCatching {
            util.getMethod("getHardwareBufferStride", HardwareBuffer::class.java).invoke(null, hb) as Int
        }.getOrDefault(0)

        val base = lock.invoke(null, hb) as? ByteBuffer ?: return@runCatching null
        try {
            val w = hb.width
            val h = hb.height
            val pixelBytes = if (hb.format == 3) 3 else 4   // 3=RGB_888，其余按 4 字节算
            val rowBytes = if (stride > 0) stride else w * pixelBytes
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                var p = y * rowBytes
                for (x in 0 until w) {
                    val r = base.get(p).toInt() and 0xFF
                    val g = base.get(p + 1).toInt() and 0xFF
                    val b = base.get(p + 2).toInt() and 0xFF
                    pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    p += pixelBytes
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            runCatching { unlock.invoke(null, hb) }
        }
    }.getOrElse {
        logOnce("lock 转换失败 $it")
        null
    }

    private fun hardwareBufferUtils(): Class<*>? {
        val loaders = listOfNotNull(appLoader, javaClass.classLoader).distinct()
        loaders.forEach { l ->
            runCatching {
                Class.forName("com.oplus.camera.common.utils.HardwareBufferUtils", false, l)
            }.getOrNull()?.let { cls ->
                logOnce("HardwareBufferUtils: " + cls.methods.joinToString("; ") {
                    "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}):${it.returnType.simpleName}"
                })
                return cls
            }
        }
        return null
    }

    /** 记一次 HardwareBuffer 的关键属性，判断这份帧能不能转 Bitmap。 */
    fun describe(hb: HardwareBuffer?): String = if (hb == null) "null" else
        "HB(${hb.width}x${hb.height} fmt=${hb.format} usage=0x${Integer.toHexString(hb.usage.toInt())})"

    /** 抽样 16×16 个点算平均亮度，< 2 当黑帧（预览没出图时会是全黑）。 */
    private fun isBlank(bmp: Bitmap): Boolean = runCatching {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val stepX = maxOf(1, w / 16)
        val stepY = maxOf(1, h / 16)
        var sum = 0L
        var n = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                sum += (((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)) / 3
                n++
                x += stepX
            }
            y += stepY
        }
        n == 0 || sum / n < 2L
    }.getOrElse { true }

    // ---------------------------------------------------------------- 云端 JSON → 相机 JSON

    /**
     * 相机推荐列表要的格式：
     *
     *   {"outputNum":1,"crops":[{"zoom":1.8,"srcCrop":[l,t,r,b],
     *     "version":"feiyu-cloud","confidenceLevel":100,
     *     "compositionDesc":{"title":"推荐构图","desc1":"...","desc2":"","desc3":""}}]}
     *
     * 云端给的是 {"data":{"zoom":1.8,"center":[x,y],"reason":"..."}}，
     * center 是主体中心点，按 zoom 反算裁剪框（宽高各除以 zoom，以 center 为中心）。
     */
    fun toCameraJson(cloudJson: String?, w: Int, h: Int): String? {
        if (cloudJson == null || w <= 0 || h <= 0) return null
        return runCatching {
            val data = JSONObject(cloudJson).optJSONObject("data") ?: return@runCatching null

            var zoom = data.optDouble("zoom", 1.0)
            if (zoom < 1.0) zoom = 1.0

            val center = data.optJSONArray("center") ?: return@runCatching null
            if (center.length() < 2) return@runCatching null
            val reason = data.optString("reason", "")

            val cx = center.getInt(0)
            val cy = center.getInt(1)

            val halfW = w / (2.0 * zoom)
            val halfH = h / (2.0 * zoom)

            val left = clamp(Math.round(cx - halfW).toInt(), 0, w)
            val top = clamp(Math.round(cy - halfH).toInt(), 0, h)
            val right = clamp(Math.round(cx + halfW).toInt(), 0, w)
            val bottom = clamp(Math.round(cy + halfH).toInt(), 0, h)
            if (right <= left || bottom <= top) return@runCatching null

            val desc = JSONObject()
                .put("title", "推荐构图")
                .put("desc1", reason)
                .put("desc2", "")
                .put("desc3", "")

            val src = JSONArray().put(left).put(top).put(right).put(bottom)

            val crop = JSONObject()
                .put("zoom", zoom)
                .put("srcCrop", src)
                .put("version", "feiyu-cloud")
                .put("confidenceLevel", 100)
                .put("compositionDesc", desc)

            val out = JSONObject()
                .put("outputNum", 1)
                .put("crops", JSONArray().put(crop))
            out.toString()
        }.getOrElse {
            log("翻译失败 $it")
            null
        }
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    // ---------------------------------------------------------------- 日志

    /**
     * 这台机器的 logcat 环形缓冲区只有 256 KB，几秒钟就被刷掉了，
     * 所以同时写一份到相机自己的外部目录（和 linggan 一个套路）。
     */
    private const val LOG_DIR = "/sdcard/Android/data/com.oplus.camera/files"
    private const val LOG_NAME = "aicomp.log"

    fun log(msg: String) {
        Log.i(TAG, msg)
        writeLog(msg)
    }

    fun logOnce(msg: String) {
        synchronized(logged) {
            if (logged.size < 200 && logged.add(msg)) log(msg)
        }
    }

    private fun writeLog(msg: String) {
        runCatching {
            val dir = java.io.File(LOG_DIR)
            if (!dir.isDirectory) dir.mkdirs()
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            java.io.File(dir, LOG_NAME).appendText("$ts [$msg]" + "\n")
        }
    }
}
