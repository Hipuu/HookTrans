package io.hooktrans.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * App-side config storage.
 *
 * The preferences file has to be readable from every hooked process, which normally is not
 * allowed on Nougat and later. LSPosed intercepts MODE_WORLD_READABLE for modules and
 * redirects the file into a location its own XSharedPreferences can reach; if that
 * interception is not in place we fall back to a private file, and the hook side then
 * relies on the binder channel to fetch the config instead.
 */
object Prefs {

    @SuppressLint("WorldReadableFiles")
    fun prefs(ctx: Context): SharedPreferences = try {
        @Suppress("DEPRECATION")
        ctx.getSharedPreferences(Const.PREFS, Context.MODE_WORLD_READABLE)
    } catch (t: Throwable) {
        Logs.w("world readable prefs unavailable (module probably not activated yet)")
        ctx.getSharedPreferences(Const.PREFS, Context.MODE_PRIVATE)
    }

    fun load(ctx: Context): HookConfig =
        HookConfig.fromJson(prefs(ctx).getString(Const.KEY_CONFIG, null))

    fun save(ctx: Context, cfg: HookConfig) {
        prefs(ctx).edit().putString(Const.KEY_CONFIG, cfg.toJson()).commit()
        Logs.verbose = cfg.logVerbose
    }
}
