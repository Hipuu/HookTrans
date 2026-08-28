package io.hooktrans.xposed

import android.graphics.Canvas
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference

/**
 * Which View is currently recording its display list.
 *
 * A Canvas has no back-reference to the View drawing into it, but the draw hooks need one: a
 * translation that arrives after the frame has been painted has to invalidate something, and
 * invalidating the whole window for one late label would repaint the world.
 *
 * Shared by [CanvasHooks] and [ImageHooks] rather than installed by each, because both want
 * the same answer and `View.draw` is called for every view of every frame — hooking it twice
 * would double that cost for one piece of information.
 */
object DrawTracker {

    private val current = ThreadLocal<WeakReference<View>?>()

    @Volatile
    private var installed = false

    /** The view being drawn on this thread, if any. */
    fun current(): View? = current.get()?.get()

    /**
     * `View.draw(Canvas)` brackets every view's recording, including custom views that paint
     * their own text. The previous value is restored rather than cleared, because a
     * ViewGroup's own drawing continues around its children's.
     */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java, "draw",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.firstOrNull() !is Canvas) return
                        val v = param.thisObject as? View ?: return
                        param.setObjectExtra(EXTRA_PREV, current.get())
                        param.setObjectExtra(EXTRA_MINE, true)
                        current.set(WeakReference(v))
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra(EXTRA_MINE) != true) return
                        @Suppress("UNCHECKED_CAST")
                        current.set(param.getObjectExtra(EXTRA_PREV) as? WeakReference<View>)
                    }
                }
            )
        }
    }

    private const val EXTRA_PREV = "ht_prev_view"
    private const val EXTRA_MINE = "ht_view_mine"
}
