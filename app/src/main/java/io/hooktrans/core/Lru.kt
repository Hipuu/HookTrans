package io.hooktrans.core

/** Small synchronized LRU. Used in every hooked process, so it must stay allocation-cheap. */
class Lru<K : Any, V : Any>(private val maxEntries: Int) {

    private val map = object : LinkedHashMap<K, V>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun contains(key: K): Boolean = map.containsKey(key)

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}
