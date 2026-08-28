package io.hooktrans.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hooktrans.core.Const
import io.hooktrans.core.Logs

/**
 * Android 11 hides packages from each other unless they declare `<queries>`. A hooked app
 * therefore cannot see — let alone bind to — the module's translation service, and the
 * hooked app's manifest is not ours to change.
 *
 * The fix is one narrow exemption in the package-visibility filter, for exactly one package:
 * this module's own. Everything else keeps filtering normally. Without it the module still
 * works, but each hooked app has to translate over its own network connection and cannot
 * share the on-device model or the persistent cache.
 *
 * The filter class and its signature have moved several times across releases, so this
 * matches by method name and inspects the arguments instead of pinning a signature.
 */
object SystemServerHooks {

    private val CANDIDATE_CLASSES = listOf(
        "com.android.server.pm.AppsFilterImpl",
        "com.android.server.pm.AppsFilterBase",
        "com.android.server.pm.AppsFilter",
        "com.android.server.pm.ComputerEngine",
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        var patched = 0
        CANDIDATE_CLASSES.forEach { name ->
            val clazz = runCatching { XposedHelpers.findClass(name, lpparam.classLoader) }.getOrNull()
                ?: return@forEach
            clazz.declaredMethods
                .filter { it.name == "shouldFilterApplication" && it.returnType == java.lang.Boolean.TYPE }
                .forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, unfilterHook)
                        patched++
                    }
                }
        }
        if (patched > 0) Logs.i("package visibility exemption installed ($patched entry points)")
        else Logs.d("no package-visibility filter found; apps will use in-process translation")
    }

    private val unfilterHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.result != true) return
            try {
                if (targetsModule(param.args)) param.result = false
            } catch (t: Throwable) {
                // Leave the original verdict alone; never make the system less restrictive
                // because of an error in here.
            }
        }
    }

    /**
     * Resolved package-name accessor per argument class, including the misses.
     *
     * This matters more than it looks. `shouldFilterApplication` is one of the hottest methods
     * in system_server — it runs for every package-visibility decision the whole device makes
     * — and a reflective lookup that *fails* costs a thrown exception each time. Probing four
     * arguments per call and throwing for the three that have no such method would put a
     * constant exception load on the core of the package manager. Each class is therefore
     * resolved exactly once, and `NONE` remembers the classes that have no accessor at all.
     */
    private val accessors = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Method>()
    private val fields = java.util.concurrent.ConcurrentHashMap<Class<*>, java.lang.reflect.Field>()
    private val hopeless = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()
    )

    /**
     * The target package setting is at a different argument index on every release, so probe
     * each argument for something that can name itself.
     */
    private fun targetsModule(args: Array<Any?>): Boolean {
        for (arg in args) {
            if (arg == null) continue
            // Fast paths first: most arguments are ints (uids, user ids) or already strings.
            if (arg is Int) continue
            if (arg is String) {
                if (arg == Const.PKG) return true
                continue
            }
            val name = nameOf(arg) ?: continue
            if (name == Const.PKG) return true
        }
        return false
    }

    private fun nameOf(obj: Any): String? {
        val cls = obj.javaClass
        if (hopeless.contains(cls)) return null

        accessors[cls]?.let { m -> return runCatching { m.invoke(obj) as? String }.getOrNull() }
        fields[cls]?.let { f -> return runCatching { f.get(obj) as? String }.getOrNull() }

        for (name in ACCESSOR_NAMES) {
            val m = runCatching { cls.getMethod(name) }.getOrNull() ?: continue
            if (m.returnType != String::class.java || m.parameterTypes.isNotEmpty()) continue
            runCatching { m.isAccessible = true }
            accessors[cls] = m
            return runCatching { m.invoke(obj) as? String }.getOrNull()
        }
        val f = runCatching { cls.getDeclaredField("name") }.getOrNull()
        if (f != null && f.type == String::class.java) {
            runCatching { f.isAccessible = true }
            fields[cls] = f
            return runCatching { f.get(obj) as? String }.getOrNull()
        }
        hopeless.add(cls)
        return null
    }

    private val ACCESSOR_NAMES = arrayOf("getPackageName", "getName")
}
