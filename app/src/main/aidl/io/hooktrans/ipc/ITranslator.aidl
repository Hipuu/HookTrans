package io.hooktrans.ipc;

import io.hooktrans.ipc.ITranslateCallback;
import io.hooktrans.ipc.IOcrCallback;
import io.hooktrans.ipc.TextRegion;
import android.graphics.Bitmap;

interface ITranslator {
    /** Bumped whenever this interface changes incompatibly. */
    int apiVersion();

    /** Serialized HookConfig for the calling package. Never null. */
    String configFor(String packageName);

    /**
     * Pure cache probe. Never hits the network, never blocks on IO beyond a local
     * SQLite read, safe to call from a binder thread. Entry is null when not cached.
     */
    String[] lookupCached(in String[] texts, String dstLang);

    /**
     * Fire-and-forget batch translation. Results arrive on the callback.
     *
     * [speculative] marks text that has been loaded but is not on screen: nobody is waiting for
     * it, so the engine may hold it back and break it into small pieces to stay responsive.
     * Keeping visible and speculative work apart on the caller's side is not enough — an engine
     * is a single set of translators behind a single executor, so a large speculative batch is
     * still something a visible one would have to queue behind.
     */
    oneway void translate(int requestId, in String[] texts, String srcLang, String dstLang,
                          String callerPackage, boolean speculative, in ITranslateCallback cb);

    /**
     * Diagnostics for the UI / self test. Blocking and network-bound, so it is restricted to
     * the module's own UI process and must never be called from a hooked app.
     */
    String selfTest(String text, String srcLang, String dstLang);

    /** Pre-downloads an offline ML Kit model. Blocking; module UI only. */
    String downloadModel(String srcLang, String dstLang);

    /** Number of persisted translations, for the settings screen. */
    long cacheCount();

    /**
     * Recognises the text in [image], translates each line, and returns the laid-out regions
     * on the callback.
     *
     * A Bitmap crossing a binder is written into ashmem rather than copied inline, so this is
     * not subject to the usual ~1 MB transaction limit — but the caller is still expected to
     * downscale before submitting, because recognition cost scales with pixel count and not
     * with how big the image looked on screen.
     *
     * [imageKey] is a content hash the caller computes. The engine keeps a result cache under
     * it, so the same picture scrolling past repeatedly is recognised exactly once.
     *
     * Fire-and-forget: OCR takes far too long to block a binder thread on.
     */
    oneway void recognize(int requestId, in Bitmap image, String imageKey, String dstLang,
                          String callerPackage, in IOcrCallback cb);

    /**
     * Cache-only probe for a previously recognised image, keyed by the same content hash.
     * Returns null when that image has not been processed yet. Safe on a binder thread.
     */
    TextRegion[] ocrCached(String imageKey, String dstLang);

    /** Drops the translation memory. Module UI only. */
    void clearCache();
}
