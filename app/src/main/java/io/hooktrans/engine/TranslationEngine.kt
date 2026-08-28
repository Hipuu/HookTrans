package io.hooktrans.engine

interface TranslationEngine {
    val id: String

    /** True when the engine reaches the network; used to decide the in-host fallback path. */
    val needsNetwork: Boolean

    /**
     * Returns one entry per input, in order. A null entry means "could not translate this
     * one" and the caller must keep the original. Implementations must never throw.
     */
    fun translate(texts: List<String>, src: String, dst: String): List<String?>

    fun close() {}
}
