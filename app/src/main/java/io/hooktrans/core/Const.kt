package io.hooktrans.core

object Const {
    // Hardcoded on purpose: BuildConfig is unavailable inside hooked host-app processes,
    // so this cannot be derived from APPLICATION_ID at runtime.
    const val PKG = "io.hooktrans"
    const val PREFS = "hooktrans"
    const val KEY_CONFIG = "config_json"
    const val SERVICE_CLASS = "io.hooktrans.service.TranslatorService"
    const val ACTION_BIND = "io.hooktrans.BIND_TRANSLATOR"
    const val ACTION_CONFIG_CHANGED = "io.hooktrans.CONFIG_CHANGED"
    const val TAG = "HookTrans"
    const val API_VERSION = 2
}
