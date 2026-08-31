package io.hooktrans.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.hooktrans.core.Const
import io.hooktrans.core.Engines
import io.hooktrans.core.HookConfig
import io.hooktrans.core.Logs
import io.hooktrans.core.Lru
import io.hooktrans.engine.DeepLEngine
import io.hooktrans.engine.GoogleFreeEngine
import io.hooktrans.engine.LibreTranslateEngine
import io.hooktrans.engine.MyMemoryEngine
import io.hooktrans.engine.TranslationEngine
import io.hooktrans.ipc.IOcrCallback
import io.hooktrans.ipc.ITranslateCallback
import io.hooktrans.ipc.ITranslator
import io.hooktrans.ipc.TextRegion
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything the hooked process needs in order to turn a source string into a translated
 * one, without ever blocking the app.
 *
 * Design rules, in priority order:
 *   1. Never block a caller thread. Lookups are a hash map hit or nothing.
 *   2. Never throw into the host. Every public entry point swallows.
 *   3. Prefer the module's own :engine process over doing work here. The in-process HTTP
 *      path exists only for the case where binding is not possible.
 */
object HostBridge {

    @Volatile
    var cfg: HookConfig = HookConfig()
        private set

    @Volatile
    var dstLang: String = "en"
        private set

    @Volatile
    private var context: Context? = null

    @Volatile
    private var service: ITranslator? = null

    @Volatile
    private var bindAttempted = false

    @Volatile
    private var lastBindTry = 0L

    /** True between a bindService() call and its connection (or failure) callback. */
    @Volatile
    private var bindInFlight = false

    @Volatile
    private var directEngine: TranslationEngine? = null

    private val memory = Lru<String, String>(4_000)

    /** Sources that failed recently, with the timestamp, so we do not hammer the engine. */
    private val cooldown = Lru<String, Long>(2_000)
    private const val COOLDOWN_MS = 60_000L

    private val waiters = HashMap<String, MutableList<(String) -> Unit>>()
    private val queue = LinkedHashSet<String>()

    /**
     * Strings that were parsed but are not on screen yet, kept strictly apart from [queue].
     *
     * These never share a batch with visible text. An earlier version filled the tail of every
     * visible batch from here, on the theory that the engine charges per request and the spare
     * room was therefore free. The room is free; the *wait* is not. Results arrive one callback
     * per batch, so five visible strings padded out to forty-five were delivered at the speed of
     * forty-five — 2 to 4 seconds on a busy feed instead of the usual handful of milliseconds.
     */
    private val prefetchQueue = LinkedHashSet<String>()
    private val requestIds = AtomicInteger(1)

    /** Guarded by [waiters]. True between arming the batch timer and the drain it triggers. */
    private var flushScheduled = false

    /** Visible batches sent and not yet answered. Guarded by [waiters]. */
    private var visibleInFlight = 0

    /** Guarded by [waiters]. At most one speculative batch is ever outstanding. */
    private var prefetchInFlight = false
    private var prefetchScheduled = false

    private val worker: Handler by lazy {
        Handler(HandlerThread("ht-bridge").apply { start() }.looper)
    }
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ht-net").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private const val BATCH_DELAY_MS = 80L

    /**
     * How long the queue must have been idle for the next request to skip the batch delay.
     *
     * The 80 ms window exists to coalesce a screenful of labels into one request, which is
     * exactly right while a list is binding. It is exactly wrong for the first string after a
     * pause — a newly opened screen, a tab switch — where there is nothing to coalesce with and
     * the delay is pure latency the user sees as the translation "popping in" late.
     *
     * So an isolated request flushes on the spot and a burst still batches: the second string
     * within the window arrives to find a drain already scheduled and rides along with it.
     */
    private const val IDLE_FLUSH_MS = 250L

    @Volatile
    private var lastRequestAt = 0L

    /**
     * Strings per engine round trip. This is not a throughput knob so much as a *wave count*
     * knob: the engine sends one HTTP request per chunk of 100, so anything at or below 100
     * costs a single request and one screenful of labels resolves in one wave. At 48 a busy
     * feed of ~90 visible strings was split into two waves 80 ms apart for no reason.
     */
    private const val BATCH_MAX = 100

    /**
     * Cap on prefetched strings held in memory, and on how many go out in one speculative batch.
     *
     * A comment thread is a few dozen strings, an infinite feed is unbounded, so the queue has
     * to be allowed to drop rather than grow: the oldest entries are the ones the user has
     * already scrolled past.
     *
     * [PREFETCH_PER_BATCH] is small on purpose. It is not a throughput limit — the queue keeps
     * draining — but a *pre-emption granularity*: it bounds how long the engine can be busy with
     * off-screen text at the moment a scroll starts. Twelve strings is well under 200 ms of work,
     * so the first visible batch after a fling is never queued behind much.
     */
    private const val PREFETCH_QUEUE_MAX = 600
    private const val PREFETCH_PER_BATCH = 12

    /**
     * Queues [source] without a callback: it is not on screen, so there is nothing to refresh.
     *
     * Never arms the visible flush timer. Prefetching must not be the reason a request goes out
     * early, and it must never be part of a batch the user is waiting on.
     */
    fun prefetch(source: String) {
        try {
            if (memory[source] != null) return
            val cooling = cooldown[source]
            if (cooling != null && SystemClock.elapsedRealtime() - cooling < COOLDOWN_MS) return
            synchronized(waiters) {
                if (queue.contains(source)) return
                if (!prefetchQueue.add(source)) return
                // Drop from the front: those are the oldest, which in a feed means the furthest
                // behind the user.
                while (prefetchQueue.size > PREFETCH_QUEUE_MAX) {
                    val oldest = prefetchQueue.iterator()
                    if (!oldest.hasNext()) break
                    oldest.next()
                    oldest.remove()
                }
            }
            armPrefetch()
        } catch (t: Throwable) {
            Logs.d("prefetch failed: ${t.message}")
        }
    }

    /**
     * Arms the speculative drain, unless one is already pending or in flight.
     *
     * The single-flight rule is what keeps this from becoming the starvation it replaced: with
     * one batch outstanding at a time, a scroll arriving mid-prefetch waits for at most
     * [PREFETCH_PER_BATCH] strings rather than for a whole payload.
     */
    private fun armPrefetch() {
        val schedule = synchronized(waiters) {
            expireStaleInFlight()
            if (prefetchScheduled || prefetchInFlight || prefetchQueue.isEmpty()) false
            else {
                prefetchScheduled = true
                true
            }
        }
        if (schedule) worker.postDelayed(prefetchFlush, prefetchDelay())
    }

    /**
     * How long to hold off the next speculative batch.
     *
     * [PREFETCH_IDLE_MS] is a yield to the user, so it is only owed when the user has actually
     * done something recently. Once the screen has been quiet for that long there is nobody to
     * yield to, and paying it again between every batch would turn a queue of a few hundred
     * strings into half a minute of work that finishes long after the user has scrolled past it.
     */
    private fun prefetchDelay(): Long =
        if (SystemClock.elapsedRealtime() - lastRequestAt > PREFETCH_IDLE_MS) PREFETCH_GAP_MS
        else PREFETCH_IDLE_MS

    /**
     * Breathing room between consecutive speculative batches. Not zero: it leaves a window in
     * which a scroll can claim the engine first, and keeps the parse-time flood from monopolising
     * the worker thread.
     */
    private const val PREFETCH_GAP_MS = 60L

    /**
     * Forgets in-flight batches the engine never answered.
     *
     * Every counted batch is normally released by its callback, but a callback is not a promise:
     * the engine process can be killed with requests outstanding. Without this, one lost batch
     * would leave the counter permanently non-zero and quietly switch prefetching off for the
     * life of the process. Must be called with [waiters] held.
     */
    private fun expireStaleInFlight() {
        if (visibleInFlight == 0 && !prefetchInFlight) return
        if (SystemClock.elapsedRealtime() - inFlightSince < IN_FLIGHT_TIMEOUT_MS) return
        visibleInFlight = 0
        prefetchInFlight = false
        visibleRequests.clear()
    }

    /**
     * When the current unbroken run of outstanding work began. Guarded by [waiters].
     *
     * Deliberately not "when the last batch was sent": under continuous use that would be pushed
     * forward forever, so a batch the engine really did lose would never age out and prefetching
     * would stay off for good. Timing the run rather than the last send bounds it — a counter that
     * never returns to zero expires, however busy the app is.
     */
    private var inFlightSince = 0L

    /** Marks a batch as outstanding. Must be called with [waiters] held. */
    private fun markInFlight(visible: Boolean) {
        if (visibleInFlight == 0 && !prefetchInFlight) inFlightSince = SystemClock.elapsedRealtime()
        if (visible) visibleInFlight++ else prefetchInFlight = true
    }

    /**
     * How long a batch waits for a bind that is already being established before giving up and
     * using the in-process engine. Longer than a warm bind ever takes, shorter than the network
     * fallback a wait that expires is trying to beat.
     */
    private const val BIND_WAIT_MS = 2_000L

    /**
     * Comfortably longer than the engine's own per-batch budget, so this only ever fires for a
     * batch that is genuinely never coming back.
     */
    private const val IN_FLIGHT_TIMEOUT_MS = 30_000L

    /**
     * How long the visible queue must stay empty before a round trip is spent on off-screen
     * text. Long enough that anything the user is looking at claims the engine first, short
     * enough to be ready before they scroll to it.
     */
    private const val PREFETCH_IDLE_MS = 400L

    fun init(config: HookConfig, packageName: String) {
        cfg = config
        dstLang = config.langFor(packageName)
        Logs.verbose = config.logVerbose
    }

    fun onContext(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext ?: ctx
        worker.post { ensureService() }
    }

    fun hasContext() = context != null

    // ---------------------------------------------------------------- lookups

    /** Non-blocking cache probe. Returns null when the translation is not known yet. */
    fun peek(source: String): String? = try {
        memory[source]
    } catch (t: Throwable) {
        null
    }

    /**
     * Asks for [source] to be translated. [onResult] runs on the main thread, once, and only
     * if a translation different from the source is produced.
     */
    fun request(source: String, onResult: (String) -> Unit) {
        try {
            val hit = memory[source]
            if (hit != null) {
                if (hit != source) main.post { safe { onResult(hit) } }
                return
            }
            val cooling = cooldown[source]
            if (cooling != null && SystemClock.elapsedRealtime() - cooling < COOLDOWN_MS) return

            val schedule: Boolean
            val immediate: Boolean
            val now = SystemClock.elapsedRealtime()
            synchronized(waiters) {
                val list = waiters.getOrPut(source) { ArrayList(2) }
                list.add(onResult)
                if (list.size > 64) list.subList(0, 32).clear()
                // This string is on screen now, so it must not wait for the prefetch tail.
                prefetchQueue.remove(source)
                if (!queue.add(source)) return
                // Arm the timer for the *first* item of a batch only. Re-arming on every
                // request would starve the queue: a scrolling list or a mutating web page
                // produces requests faster than 80 ms apart, so the flush would be pushed
                // forward forever and nothing would ever be translated while the user scrolls.
                schedule = !flushScheduled
                immediate = schedule && (now - lastRequestAt) > IDLE_FLUSH_MS
                if (schedule) flushScheduled = true
                lastRequestAt = now
            }
            if (schedule) {
                if (immediate) worker.post(flush) else worker.postDelayed(flush, BATCH_DELAY_MS)
            }
        } catch (t: Throwable) {
            Logs.d("request failed: ${t.message}")
        }
    }

    private val flush = Runnable { safe { drain() } }

    /**
     * Sends one speculative batch of off-screen text, and only when the user is not waiting on
     * anything. Both checks matter: [queue] non-empty means a visible batch is about to go out,
     * and [visibleInFlight] means one is already at the engine and will answer sooner if it is
     * not competing with work nobody is looking at.
     */
    private val prefetchFlush: Runnable = Runnable {
        safe {
            val batch: List<String>
            synchronized(waiters) {
                prefetchScheduled = false
                if (prefetchQueue.isEmpty()) return@safe
                expireStaleInFlight()
                if (queue.isNotEmpty() || visibleInFlight > 0 || prefetchInFlight) {
                    // Something visible owns the engine. Come back after it is done rather than
                    // queueing behind it.
                    prefetchScheduled = true
                    worker.postDelayed(prefetchFlush, PREFETCH_IDLE_MS)
                    return@safe
                }
                batch = prefetchQueue.take(PREFETCH_PER_BATCH)
                prefetchQueue.removeAll(batch.toSet())
                markInFlight(visible = false)
            }
            if (batch.isEmpty()) {
                synchronized(waiters) { prefetchInFlight = false }
                return@safe
            }
            send(batch, visible = false)
        }
    }

    private fun drain() {
        val batch: List<String>
        synchronized(waiters) {
            flushScheduled = false
            if (queue.isEmpty()) return
            batch = queue.take(BATCH_MAX)
            queue.removeAll(batch.toSet())
            markInFlight(visible = true)
            if (queue.isNotEmpty()) {
                flushScheduled = true
                worker.postDelayed(flush, BATCH_DELAY_MS)
            }
        }
        send(batch, visible = true)
    }

    /**
     * Hands [batch] to the engine process, or translates it here if there is none.
     *
     * [visible] only affects bookkeeping: a batch the user is waiting on holds off the next
     * speculative one until its results land, which is the entire mechanism keeping prefetch off
     * the critical path.
     */
    private fun send(batch: List<String>, visible: Boolean) {
        if (batch.isEmpty()) {
            settle(visible)
            return
        }

        var svc = ensureService()
        if (svc == null && bindInFlight) {
            // A bind is being established right now. The engine process answers in tens of
            // milliseconds when warm, while the fallback below is a full HTTPS round trip
            // (seconds) whose results the engine will not share — so a bounded wait here is the
            // cheaper path on every start where the engine process is merely a step behind.
            // Bounded, so a genuinely stuck bind still reaches the fallback.
            awaitBinder(BIND_WAIT_MS)
            svc = ensureService()
        }
        if (svc != null) {
            // Cheap cache probe first: the engine process may already know these.
            val cached = try {
                svc.lookupCached(batch.toTypedArray(), dstLang)
            } catch (t: Throwable) {
                service = null; null
            }
            val remaining = ArrayList<String>(batch.size)
            if (cached != null && cached.size == batch.size) {
                batch.forEachIndexed { i, s ->
                    val v = cached[i]
                    if (v != null) deliver(s, v) else remaining += s
                }
            } else {
                remaining += batch
            }
            if (remaining.isEmpty()) {
                settle(visible)
                return
            }
            val id = requestIds.getAndIncrement()
            synchronized(waiters) { if (visible) visibleRequests += id }
            val ok = runCatching {
                svc.translate(
                    id,
                    remaining.toTypedArray(),
                    cfg.sourceLang,
                    dstLang,
                    context?.packageName ?: "?",
                    !visible,
                    callback
                )
                true
            }.getOrElse { service = null; false }
            if (ok) return
            synchronized(waiters) { visibleRequests.remove(id) }
        }

        // No engine process reachable: translate here if this app can reach the network.
        val engine = ensureDirectEngine()
        if (engine == null) {
            batch.forEach { fail(it) }
            settle(visible)
            return
        }
        net.execute {
            try {
                safe {
                    val res = engine.translate(batch, cfg.sourceLang, dstLang)
                    batch.forEachIndexed { i, s ->
                        val v = res.getOrNull(i)
                        if (v != null) deliver(s, v) else fail(s)
                    }
                }
            } finally {
                settle(visible)
            }
        }
    }

    /**
     * Request ids belonging to visible batches. The callback identifies work by id only, so this
     * is what tells [settle] which counter a completed batch was holding. Guarded by [waiters].
     */
    private val visibleRequests = HashSet<Int>()

    /**
     * Releases whichever in-flight slot a finished batch held, and lets the next speculative
     * batch go out now that the engine is free.
     */
    private fun settle(visible: Boolean) {
        synchronized(waiters) {
            if (visible) {
                if (visibleInFlight > 0) visibleInFlight--
            } else {
                prefetchInFlight = false
            }
        }
        armPrefetch()
    }

    private val callback = object : ITranslateCallback.Stub() {
        override fun onBatch(requestId: Int, sources: Array<out String>?, results: Array<out String>?) {
            val wasVisible = synchronized(waiters) { visibleRequests.remove(requestId) }
            safe {
                val s = sources ?: return@safe
                val r = results ?: return@safe
                for (i in s.indices) {
                    val v = r.getOrNull(i)
                    if (v != null) deliver(s[i], v) else fail(s[i])
                }
            }
            settle(wasVisible)
        }

        override fun onFailure(requestId: Int, reason: String?) {
            Logs.d("translate request $requestId failed: $reason")
            val wasVisible = synchronized(waiters) { visibleRequests.remove(requestId) }
            settle(wasVisible)
        }
    }

    private fun deliver(source: String, translated: String) {
        memory[source] = translated
        val list = synchronized(waiters) { waiters.remove(source) } ?: return
        if (translated == source) return
        main.post {
            list.forEach { cb -> safe { cb(translated) } }
        }
    }

    /**
     * A string the engine could not translate. The waiters are dropped along with the
     * cooldown: keeping them would grow [waiters] without bound over a long session, and a
     * callback that will never fire is just a retained reference.
     */
    private fun fail(source: String) {
        cooldown[source] = SystemClock.elapsedRealtime()
        synchronized(waiters) { waiters.remove(source) }
    }

    // ---------------------------------------------------------------- images

    /**
     * A finished recognition. [srcWidth]/[srcHeight] are the dimensions of the bitmap that was
     * actually submitted, which is not the app's bitmap: it was downscaled on the way out. The
     * region coordinates are in that submitted space, so the drawing side needs these numbers
     * to map them back onto the image as displayed.
     */
    class Recognized(val regions: List<TextRegion>, val srcWidth: Int, val srcHeight: Int)

    /**
     * Recognised regions per image content hash. Small, because each entry holds the text of a
     * whole picture, and because a screen only ever shows a handful of images at once.
     */
    private val regions = Lru<String, Recognized>(300)

    /**
     * Bitmap *object* identity to content hash.
     *
     * This is the tier that makes the draw hook affordable. Hashing pixels costs milliseconds;
     * a drawing app hands us the same bitmap sixty times a second. Identity plus `generationId`
     * answers "have I already hashed exactly this" in constant time, and `generationId` changing
     * is precisely the signal that an app drew into a bitmap we had already seen.
     */
    private val fingerprints = Lru<Long, String>(2_000)

    /** Content hashes currently being recognised by the engine. Guarded by [imageLock]. */
    private val ocrInFlight = HashSet<String>()

    /** Bitmap objects queued for hashing, so a redraw does not queue the same one again. */
    private val hashInFlight = HashSet<Long>()

    /** Callbacks waiting on a content hash, keyed by it. Guarded by [imageLock]. */
    private val ocrWaiters = HashMap<String, MutableList<() -> Unit>>()

    private val imageLock = Any()

    /** Images the engine could not read, with the timestamp. */
    private val ocrCooldown = Lru<String, Long>(500)

    /**
     * Longest edge of the bitmap actually sent for recognition. Recognition cost scales with
     * pixel count, and text that is illegible below this is illegible on the phone screen too.
     */
    private const val OCR_MAX_DIM = 1280

    /**
     * The translated text regions for [bitmap], or null when they are not known yet.
     *
     * Safe to call from a draw callback: the hit path is two hash lookups and nothing else.
     * On a miss the work is started on a background thread and [onReady] is posted to the main
     * thread once regions exist, so the caller can invalidate and draw them on a later frame.
     * [onReady] never runs for an image that turns out to hold no translatable text.
     */
    fun regionsFor(bitmap: Bitmap, onReady: () -> Unit): Recognized? {
        return try {
            // Video and animation surfaces produce fresh pixels faster than recognition can
            // finish: every frame is a new content hash, so nothing is ever cached and the
            // engine process pays a full OCR job (plus a binder bitmap copy) tens of times a
            // second for results that are stale on arrival. Detect the churn and stop reading.
            if (isChurning(bitmap)) return null
            val print = fingerprint(bitmap)
            val key = fingerprints[print]
            if (key != null) return regions[key]

            synchronized(imageLock) { if (!hashInFlight.add(print)) return null }
            worker.post { safe { hashAndSubmit(print, bitmap, onReady) } }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * True when this Bitmap object's pixels keep changing faster than OCR is useful.
     *
     * The signal is `generationId` on a constant object identity: a static image is drawn many
     * times but mutated never, while a video frame or animated banner mutates before the
     * previous recognition even lands. Three changes inside two seconds is far past anything a
     * photograph does, so the surface is banned from recognition for a while — long enough that
     * a video costs a handful of jobs per minute instead of tens per second. When the ban
     * expires the next draw reads it once more, so a surface that genuinely settles (a paused
     * video, a finished animation) is recognised instead of skipped forever.
     */
    private fun isChurning(bitmap: Bitmap): Boolean {
        val identity = System.identityHashCode(bitmap)
        val gen = bitmap.generationId.toLong()
        val now = SystemClock.elapsedRealtime()
        synchronized(imageLock) {
            // Entries are tiny and identities are recycled constantly; a wholesale reset when
            // the map runs large is cheaper and simpler than tracking eviction order.
            if (churn.size > CHURN_MAP_MAX) churn.clear()
            val c = churn[identity]
            if (c == null) {
                churn[identity] = Churn(gen, 0, now, 0L)
                return false
            }
            if (now < c.bannedUntil) return true
            if (c.gen != gen) {
                c.gen = gen
                if (now - c.windowStart > CHURN_WINDOW_MS) {
                    c.windowStart = now
                    c.changes = 0
                }
                c.changes++
                if (c.changes >= CHURN_CHANGES) c.bannedUntil = now + CHURN_BAN_MS
            }
            return false
        }
    }

    /** Generation-change tracker for one Bitmap object. Guarded by [imageLock]. */
    private class Churn(var gen: Long, var changes: Int, var windowStart: Long, var bannedUntil: Long)

    private val churn = HashMap<Int, Churn>()

    private const val CHURN_WINDOW_MS = 2_000L
    private const val CHURN_CHANGES = 3
    private const val CHURN_BAN_MS = 10_000L
    private const val CHURN_MAP_MAX = 2_000

    /**
     * Identity and generation packed into one long. Identity alone would be wrong: apps reuse
     * bitmaps as scratch surfaces, and a cached OCR result for a repainted bitmap is text from
     * an image that is no longer there.
     */
    private fun fingerprint(bitmap: Bitmap): Long =
        (System.identityHashCode(bitmap).toLong() shl 32) or (bitmap.generationId.toLong() and 0xFFFF_FFFFL)

    private fun hashAndSubmit(print: Long, bitmap: Bitmap, onReady: () -> Unit) {
        val scaled = try {
            downscale(bitmap)
        } catch (t: Throwable) {
            // The app may have recycled it between the draw and this post, or it may be a
            // hardware bitmap on a device that refuses to hand back its pixels.
            Logs.d("cannot read bitmap for ocr: ${t.message}")
            null
        }
        if (scaled == null) {
            synchronized(imageLock) { hashInFlight.remove(print) }
            return
        }

        val key = contentHash(scaled)
        fingerprints[print] = key
        synchronized(imageLock) { hashInFlight.remove(print) }

        // A different Bitmap object holding pixels we have already read. Common: image loaders
        // decode the same avatar once per list binding.
        regions[key]?.let {
            scaled.recycle()
            if (it.regions.isNotEmpty()) main.post { safe { onReady() } }
            return
        }

        val cooling = ocrCooldown[key]
        if (cooling != null && SystemClock.elapsedRealtime() - cooling < COOLDOWN_MS) {
            scaled.recycle()
            return
        }

        val submit: Boolean
        synchronized(imageLock) {
            ocrWaiters.getOrPut(key) { ArrayList(2) }.add(onReady)
            submit = ocrInFlight.add(key)
        }
        if (!submit) {
            // Another draw already sent these exact pixels; this copy is redundant.
            scaled.recycle()
            return
        }
        safe { sendForRecognition(scaled, key) }
    }

    private fun sendForRecognition(scaled: Bitmap, key: String) {
        val w = scaled.width
        val h = scaled.height
        val svc = ensureService()
        if (svc == null) {
            // There is no in-process fallback for this the way there is for text: ML Kit's
            // native libraries are not loaded into a host app, so an image simply waits for
            // the engine process to come up and is retried the next time it is drawn.
            scaled.recycle()
            imageFail(key, cooldown = false)
            return
        }
        val cached = runCatching { svc.ocrCached(key, dstLang) }.getOrElse { service = null; null }
        if (cached != null) {
            scaled.recycle()
            deliverRegions(key, cached.toList(), w, h)
            return
        }
        val id = requestIds.getAndIncrement()
        synchronized(imageLock) { ocrRequests[id] = OcrRequest(key, w, h) }
        val ok = runCatching {
            svc.recognize(id, scaled, key, dstLang, context?.packageName ?: "?", ocrCallback)
            true
        }.getOrElse { service = null; false }
        // The engine received its own copy across the binder and recycles that one itself; this
        // side's copy has done its job either way. Not freeing it here would leak a full-size
        // bitmap per distinct image in every hooked process.
        scaled.recycle()
        if (!ok) {
            synchronized(imageLock) { ocrRequests.remove(id) }
            imageFail(key, cooldown = false)
        }
    }

    /**
     * Request id to the image it is for. The OCR callback identifies the work by request id
     * only, so this is what tells us which image a batch of regions belongs to — and at what
     * scale it was recognised.
     */
    private class OcrRequest(val key: String, val width: Int, val height: Int)

    private val ocrRequests = HashMap<Int, OcrRequest>()

    private val ocrCallback = object : IOcrCallback.Stub() {
        override fun onRegions(requestId: Int, result: Array<out TextRegion>?) {
            safe {
                val req = synchronized(imageLock) { ocrRequests.remove(requestId) } ?: return@safe
                deliverRegions(req.key, result?.filterNotNull().orEmpty(), req.width, req.height)
            }
        }

        override fun onFailure(requestId: Int, reason: String?) {
            Logs.d("ocr request $requestId failed: $reason")
            safe {
                val req = synchronized(imageLock) { ocrRequests.remove(requestId) } ?: return@safe
                // Genuine engine failures cool down; a request the engine dropped because the
                // same image was already being worked on does not, because that result is
                // coming and the next draw should pick it up.
                imageFail(req.key, cooldown = reason != null && !reason.contains("already"))
            }
        }
    }

    private fun deliverRegions(key: String, list: List<TextRegion>, width: Int, height: Int) {
        // Cached even when empty: "this image has no text" is the answer we least want to
        // recompute, and on an ungated hook most images are icons with nothing in them.
        regions[key] = Recognized(list, maxOf(1, width), maxOf(1, height))
        val waiting = synchronized(imageLock) {
            ocrInFlight.remove(key)
            ocrWaiters.remove(key)
        } ?: return
        if (list.isEmpty()) return
        main.post { waiting.forEach { cb -> safe { cb() } } }
    }

    private fun imageFail(key: String, cooldown: Boolean) {
        if (cooldown) ocrCooldown[key] = SystemClock.elapsedRealtime()
        synchronized(imageLock) {
            ocrInFlight.remove(key)
            ocrWaiters.remove(key)
        }
    }

    /**
     * A readable ARGB_8888 copy, no larger than [OCR_MAX_DIM] on its long edge. Never upscales:
     * inventing pixels cannot add detail a recognizer can use, it only adds work.
     *
     * Always returns a bitmap this module owns, never the app's own object: the caller sends it
     * over a binder and recycles it, and doing either to an app's bitmap would be exactly the
     * kind of damage this hook exists to avoid.
     */
    private fun downscale(src: Bitmap): Bitmap? {
        if (src.isRecycled) return null
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null

        // A HARDWARE bitmap lives in graphics memory with no CPU-side pixels: getPixels throws
        // and it cannot be read directly. Modern image loaders hand these out by default, so
        // this is the common case rather than an edge one. copy() on API 31+ pulls it back into
        // system memory; below that the image is simply not translatable.
        val hardware = src.config == Bitmap.Config.HARDWARE
        if (hardware && Build.VERSION.SDK_INT < 31) return null

        val longEdge = maxOf(w, h)
        if (longEdge <= OCR_MAX_DIM) {
            return src.copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = OCR_MAX_DIM.toFloat() / longEdge
        val dst = Bitmap.createBitmap(
            maxOf(1, (w * scale).toInt()),
            maxOf(1, (h * scale).toInt()),
            Bitmap.Config.ARGB_8888
        )
        // A hardware bitmap cannot be the source of a software canvas draw either, so it takes
        // one full-size trip through system memory before it can be shrunk.
        val readable = if (hardware) src.copy(Bitmap.Config.ARGB_8888, false) ?: return null else src
        try {
            Canvas(dst).drawBitmap(readable, null, Rect(0, 0, dst.width, dst.height), scalePaint)
        } finally {
            if (readable !== src) runCatching { readable.recycle() }
        }
        return dst
    }

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * A content hash of the pixels actually sent for recognition. Two decodes of the same
     * picture must land on the same key, which is the whole reason this is not an object
     * identity: an image loader hands out a fresh Bitmap for every list binding.
     */
    private fun contentHash(bitmap: Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var a = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) {
                a = a xor p.toLong()
                a *= 0x100000001b3L
            }
        }
        return "${w}x$h.${java.lang.Long.toHexString(a)}"
    }

    // ---------------------------------------------------------------- plumbing

    private fun ensureService(): ITranslator? {
        service?.let { if (it.asBinder().isBinderAlive) return it else service = null }
        val ctx = context ?: return null
        val now = SystemClock.elapsedRealtime()
        if (bindAttempted && now - lastBindTry < 15_000) return null
        lastBindTry = now
        bindAttempted = true
        return try {
            val intent = Intent(Const.ACTION_BIND).apply {
                component = ComponentName(Const.PKG, Const.SERVICE_CLASS)
            }
            val bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY)
            if (!bound) Logs.d("bindService refused; falling back to in-process translation")
            else bindInFlight = true
            null // the binder arrives asynchronously; this drain uses the fallback
        } catch (t: Throwable) {
            Logs.d("bindService threw: ${t.message}")
            null
        }
    }

    /**
     * Blocks the worker until the pending bind resolves or [ms] elapses.
     *
     * Only ever called from the worker thread, which owns nothing the connection callback
     * (delivered on the app's main thread) needs: [service] and [bindInFlight] are volatile, so
     * the poll sees the answer the moment it is published.
     */
    private fun awaitBinder(ms: Long) {
        val deadline = SystemClock.elapsedRealtime() + ms
        while (bindInFlight && service == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(10)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ITranslator.Stub.asInterface(binder)
            bindInFlight = false
            Logs.d("engine connected in ${context?.packageName}")
            // onServiceConnected runs on the host app's main thread, and configFor is a
            // synchronous binder call into another process that may still be cold-starting.
            // Doing it here would block the app's UI thread on our service, so it happens on
            // the worker instead, immediately before the drain that needs it.
            worker.post {
                safe {
                    val json = service?.configFor(context?.packageName ?: "")
                    if (!json.isNullOrBlank()) {
                        val fresh = HookConfig.fromJson(json)
                        cfg = fresh
                        dstLang = fresh.langFor(context?.packageName ?: "")
                        Logs.verbose = fresh.logVerbose
                    }
                }
                safe { drain() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bindAttempted = false
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            bindAttempted = false
            bindInFlight = false
        }

        override fun onNullBinding(name: ComponentName?) {
            service = null
            bindInFlight = false
        }
    }

    private fun ensureDirectEngine(): TranslationEngine? {
        directEngine?.let { return it }
        val ctx = context ?: return null
        val hasInternet = runCatching {
            ctx.checkPermission(
                android.Manifest.permission.INTERNET,
                android.os.Process.myPid(),
                android.os.Process.myUid()
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!hasInternet) {
            Logs.d("${ctx.packageName} has no INTERNET permission; needs the engine process")
            return null
        }
        val e = when (cfg.engine) {
            Engines.LIBRE -> LibreTranslateEngine(cfg.endpoint, cfg.apiKey)
            Engines.DEEPL -> DeepLEngine(cfg.apiKey)
            Engines.MYMEMORY -> MyMemoryEngine()
            // ML Kit cannot run here: its native libraries are not loaded into a host app.
            else -> GoogleFreeEngine()
        }
        directEngine = e
        Logs.d("using in-process ${e.id} engine for ${ctx.packageName}")
        return e
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logs.d("swallowed: ${t.message}")
        }
    }

    private fun <T> LinkedHashSet<T>.take(n: Int): List<T> {
        val out = ArrayList<T>(minOf(n, size))
        for (e in this) {
            out += e
            if (out.size >= n) break
        }
        return out
    }
}
