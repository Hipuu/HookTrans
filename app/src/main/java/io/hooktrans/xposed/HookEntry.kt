package io.hooktrans.xposed

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hooktrans.core.Const
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs

/**
 * Module entry point.
 *
 * Everything here is defensive on purpose: this code runs inside other people's processes,
 * so a mistake is their crash, not ours. Every stage is wrapped, and any failure degrades to
 * "this app is simply not translated" rather than to a broken app.
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when {
                lpparam.packageName == Const.PKG -> installSelfHooks(lpparam)

                lpparam.packageName == "android" && lpparam.processName == "android" ->
                    SystemServerHooks.install(lpparam)

                else -> installAppHooks(lpparam)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[HookTrans] fatal in ${lpparam.packageName}: ${t.stackTraceToString()}")
        }
    }

    /** Lets the module's own UI report "active" without guessing. */
    private fun installSelfHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = XposedHelpers.findClass("io.hooktrans.core.ModuleStatus", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(cls, "isActive", XC_MethodReplacement.returnConstant(true))
            XposedHelpers.findAndHookMethod(
                cls, "frameworkName",
                XC_MethodReplacement.returnConstant(frameworkName())
            )
        }
    }

    private fun frameworkName(): String = runCatching {
        val v = XposedBridge.getXposedVersion()
        "Xposed API $v"
    }.getOrDefault("Xposed")

    private fun installAppHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cfg = readConfig()
        if (!cfg.enabled) return
        if (!cfg.isPackageEnabled(lpparam.packageName)) {
            Logs.d("${lpparam.packageName} not in scope")
            return
        }

        Logs.verbose = cfg.logVerbose
        HostBridge.init(cfg, lpparam.packageName)

        // A Context is needed to reach the engine process. Application.attach is the first
        // moment one exists, and it happens before any UI is created.
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java, "attach", Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { HostBridge.onContext(param.thisObject as Context) }
                    }
                }
            )
        }
        // Processes that never build an Application still get a Context here.
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", lpparam.classLoader,
                "callApplicationOnCreate", Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { HostBridge.onContext(param.args[0] as Context) }
                    }
                }
            )
        }

        if (cfg.hookTextViews) safely("TextView") { TextViewHooks.install(cfg) }
        if (cfg.hookWebViews) safely("WebView") { WebViewHooks.install(cfg, lpparam.classLoader) }
        if (cfg.hookCompose) safely("Compose") { ComposeHooks.install(cfg, lpparam.classLoader) }
        if (cfg.hookCanvas) safely("Canvas") { CanvasHooks.install(cfg) }
        if (cfg.hookImages) safely("Images") { ImageHooks.install(cfg) }
        if (cfg.hookResources) safely("Resources") { ResourceHooks.install(cfg) }
        if (cfg.prefetchParsed) safely("Prefetch") { PrefetchHooks.install(cfg, lpparam.classLoader) }

        Logs.i("hooks active in ${lpparam.packageName} -> ${cfg.langFor(lpparam.packageName)}")
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logs.e("$what hooks failed to install", t)
        }
    }

    private fun readConfig(): HookConfig = try {
        val prefs = XSharedPreferences(Const.PKG, Const.PREFS)
        @Suppress("DEPRECATION")
        prefs.makeWorldReadable()
        prefs.reload()
        HookConfig.fromJson(prefs.getString(Const.KEY_CONFIG, null))
    } catch (t: Throwable) {
        Logs.w("could not read module prefs; using defaults", t)
        HookConfig()
    }

    companion object {
        @Volatile
        @JvmStatic
        var modulePath: String? = null
            private set
    }
}
