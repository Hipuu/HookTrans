package io.hooktrans.core

/**
 * The module hooks [isActive] inside its own process and forces it to return true. If the
 * Xposed framework is missing or the module is disabled, the stock implementation runs and
 * returns false. This is the standard "am I actually loaded?" probe.
 */
object ModuleStatus {

    @JvmStatic
    fun isActive(): Boolean = false

    /** Replaced by the self hook with the framework name reported by LSPosed. */
    @JvmStatic
    fun frameworkName(): String = "none"
}
