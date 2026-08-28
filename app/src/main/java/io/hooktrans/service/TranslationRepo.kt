package io.hooktrans.service

import android.content.Context
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.Lru
import io.hooktrans.core.Prefs
import io.hooktrans.engine.EngineFactory
import io.hooktrans.engine.TranslationEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Memory LRU in front of a SQLite translation memory in front of the engine. Deduplicates
 * in-flight work so a list that shows the same label thirty times issues one request.
 */
class TranslationRepo(ctx: Context) {

    private val appCtx = ctx.applicationContext
    private val db = CacheDb(appCtx)
    private val mem = Lru<String, String>(8_000)
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    /** Cache persistence only. Never in the path of a translation the user is waiting for. */
    private val io: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ht-repo-db").apply { isDaemon = true }
    }

    /**
     * Visible batches, one at a time and in arrival order.
     *
     * Counter-intuitively this is *faster* than the four-thread pool it replaced, because the
     * engine underneath does not actually parallelise: ML Kit runs its translations on one
     * internal executor, so 17 ms per string holds whether the strings arrive in one batch or
     * four. What concurrency did add was queue-jumping — four batches submitted at once put
     * thirty-odd strings into that executor together, and the two-word label that happened to be
     * last waited for all of them. Batches of 1 and 2 were measured at 1.5 s that way while the
     * median sat at 58 ms.
     *
     * Serialising them costs nothing in throughput and makes latency track the queue: a batch
     * that arrives to an idle engine is done in tens of milliseconds, and one that arrives during
     * a burst waits for the text that was already on screen before it.
     */
    private val visible: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ht-repo-live").apply { isDaemon = true }
    }

    /**
     * Speculative work runs here, one batch at a time and in the background cpuset.
     *
     * Separate from [visible] because the two must not compete. Off-screen text arrives in far
     * greater volume than visible text — a single feed response can hold hundreds of strings — so
     * sharing a lane means a visible batch of one waits behind whatever speculative work got
     * there first.
     *
     * The priority goes through [android.os.Process] as well as `Thread.priority`: the Java value
     * alone barely reaches the Linux scheduler, and the point here is to lose the CPU to the
     * foreground rather than merely to queue politely.
     */
    private val slow: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }
            r.run()
        }, "ht-repo-ahead").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /**
     * Visible batches submitted and not yet finished.
     *
     * The engine underneath is one set of translators behind one executor, so lane separation
     * alone does not stop a speculative batch from filling it. Speculative work waits for this to
     * reach zero, which is what keeps the engine available for whatever is on screen.
     */
    private val visibleActive = java.util.concurrent.atomic.AtomicInteger(0)

    private val idle = Object()

    @Volatile
    private var config: HookConfig = Prefs.load(appCtx)

    @Volatile
    private var engine: TranslationEngine = EngineFactory.create(config, appCtx)

    @Volatile
    private var engineSig: String = EngineFactory.signature(config)

    init {
        Logs.verbose = config.logVerbose
    }

    fun config(): HookConfig = config

    @Synchronized
    fun reloadConfig() {
        config = Prefs.load(appCtx)
        Logs.verbose = config.logVerbose
        val sig = EngineFactory.signature(config)
        if (sig != engineSig) {
            runCatching { engine.close() }
            engine = EngineFactory.create(config, appCtx)
            engineSig = sig
            mem.clear()
            Logs.i("engine switched to ${config.engine}")
        }
    }

    fun engine(): TranslationEngine = engine

    private fun key(text: String, dst: String): String =
        "${config.engine}|${config.sourceLang}|$dst|$text"

    /** Cache-only probe. Safe on a binder thread. */
    fun cached(texts: List<String>, dst: String): Array<String?> {
        val out = arrayOfNulls<String>(texts.size)
        val missingKeys = ArrayList<String>()
        val missingIdx = ArrayList<Int>()
        texts.forEachIndexed { i, t ->
            val k = key(t, dst)
            val hit = mem[k]
            if (hit != null) out[i] = hit else {
                missingKeys += k; missingIdx += i
            }
        }
        if (missingKeys.isNotEmpty()) {
            val fromDb = db.get(missingKeys)
            missingIdx.forEachIndexed { n, idx ->
                val k = missingKeys[n]
                fromDb[k]?.let { mem[k] = it; out[idx] = it }
            }
        }
        return out
    }

    /**
     * Resolves everything it can from cache, sends the remainder to the engine, persists the
     * result and hands back one entry per input. Blocking; call from [visible] or [slow].
     */
    fun translateBlocking(texts: List<String>, dst: String): Array<String?> =
        translateBlocking(texts, dst, ahead = false)

    /**
     * Blocking translation that yields to anything on screen, for callers that are already on a
     * background thread of their own and whose result nobody is waiting to see.
     *
     * Recognised image text is the case this exists for. It arrives on the OCR thread, which is
     * neither of the two lanes, so calling straight through would put it into the engine
     * alongside visible text — and OCR produces lines in bulk. Five of seven slow visible batches
     * in a Taobao run overlapped an OCR job before this existed.
     */
    fun translateDeferred(texts: List<String>, dst: String): Array<String?> {
        val out = cached(texts, dst)
        val pending = texts.indices.filter { out[it] == null }
        if (pending.isEmpty()) return out
        for (from in pending.indices step AHEAD_CHUNK) {
            awaitVisibleIdle()
            val slice = pending.subList(from, minOf(from + AHEAD_CHUNK, pending.size))
            val part = translateBlocking(slice.map { texts[it] }, dst, ahead = true)
            slice.forEachIndexed { n, idx -> out[idx] = part.getOrNull(n) }
        }
        return out
    }

    private fun translateBlocking(texts: List<String>, dst: String, ahead: Boolean): Array<String?> {
        val out = cached(texts, dst)
        val misses = LinkedHashMap<String, MutableList<Int>>()
        texts.forEachIndexed { i, t ->
            if (out[i] == null) misses.getOrPut(t) { ArrayList() }.add(i)
        }
        if (misses.isEmpty()) return out

        val batch = misses.keys.toList()
        val started = android.os.SystemClock.elapsedRealtime()
        val results = try {
            engine.translate(batch, config.sourceLang, dst)
        } catch (t: Throwable) {
            Logs.e("engine threw", t)
            batch.map { null }
        }
        Logs.d(
            "engine ${engine.id}: ${batch.size} miss(es) in " +
                "${android.os.SystemClock.elapsedRealtime() - started} ms " +
                "(${texts.size - batch.size} from cache)${if (ahead) " [ahead]" else ""}"
        )

        val toPersist = HashMap<String, String>()
        batch.forEachIndexed { i, source ->
            val translated = results.getOrNull(i) ?: return@forEachIndexed
            val k = key(source, dst)
            mem[k] = translated
            toPersist[k] = translated
            misses[source]?.forEach { idx -> out[idx] = translated }
        }
        if (toPersist.isNotEmpty()) io.execute { db.put(toPersist) }
        return out
    }

    fun submit(texts: List<String>, dst: String, done: (Array<String?>) -> Unit) {
        submit(texts, dst, speculative = false, done = done)
    }

    /**
     * Translates [texts] and hands back one entry per input.
     *
     * When [speculative] is set the work is queued behind anything visible rather than alongside
     * it: nobody is waiting on off-screen text, so it is always the right thing to delay.
     */
    fun submit(
        texts: List<String>,
        dst: String,
        speculative: Boolean,
        done: (Array<String?>) -> Unit,
    ) {
        if (speculative) {
            slow.execute {
                try {
                    done(translateDeferred(texts, dst))
                } catch (t: Throwable) {
                    Logs.e("prefetch submit failed", t)
                    done(arrayOfNulls(texts.size))
                }
            }
            return
        }
        visibleActive.incrementAndGet()
        visible.execute {
            try {
                done(translateBlocking(texts, dst))
            } catch (t: Throwable) {
                Logs.e("submit failed", t)
                done(arrayOfNulls(texts.size))
            } finally {
                if (visibleActive.decrementAndGet() <= 0) {
                    synchronized(idle) { idle.notifyAll() }
                }
            }
        }
    }

    /**
     * Speculative translation, in small pieces with a yield between each.
     *
     * Waiting once at the start would not be enough. A batch handed to the engine occupies it
     * until it returns, so a scroll starting mid-translation still waits — which is exactly what
     * a single-string visible batch taking over a second looked like. Chunking bounds that to one
     * chunk's worth of work, and re-checking between chunks means a scroll that begins halfway
     * through a payload stops the rest of it rather than racing it.
     */

    /**
     * Blocks until no visible batch is being translated, or until the wait has gone on long
     * enough that something has clearly gone wrong.
     *
     * The timeout is the important part: a lost visible batch must delay prefetching, never
     * disable it, and this runs on a thread whose whole job is to wait.
     */
    private fun awaitVisibleIdle() {
        val deadline = android.os.SystemClock.elapsedRealtime() + AHEAD_WAIT_MAX_MS
        synchronized(idle) {
            while (visibleActive.get() > 0) {
                val left = deadline - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) return
                try {
                    idle.wait(left)
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private companion object {
        /**
         * Strings per speculative engine call.
         *
         * This is the pre-emption granularity, and it is deliberately tiny. A chunk cannot be
         * interrupted once it is handed to the engine — ML Kit runs every translator on one
         * internal executor, so a visible batch arriving mid-chunk interleaves with it — which
         * makes the chunk size a direct bound on how long the user can be made to wait. At four,
         * the visible batches unlucky enough to land during a chunk ran at a median of 200 ms
         * against 40 ms for the rest.
         *
         * Two costs almost nothing in throughput, because the per-call overhead here is a hash
         * lookup and not a network round trip.
         */
        const val AHEAD_CHUNK = 2

        /** Longest a speculative batch will wait for the screen to go quiet before proceeding. */
        const val AHEAD_WAIT_MAX_MS = 10_000L
    }

    fun markInFlight(k: String): Boolean = inFlight.putIfAbsent(k, true) == null
    fun clearInFlight(k: String) { inFlight.remove(k) }

    fun cacheCount(): Long = db.count()

    fun clearCache() {
        mem.clear()
        io.execute { db.clear() }
    }
}
