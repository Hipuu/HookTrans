package io.hooktrans.ipc;

import io.hooktrans.ipc.TextRegion;

oneway interface IOcrCallback {
    /**
     * Recognised and translated regions for the image submitted under [requestId].
     *
     * Coordinates are in the pixel space of the bitmap that was submitted, so the caller
     * scales them into whatever space it is drawing in. An empty array means the image was
     * processed and holds no translatable text — that is a result, not a failure, and the
     * caller should remember it so the same image is never submitted again.
     */
    void onRegions(int requestId, in TextRegion[] regions);

    /**
     * The image could not be processed. Unlike an empty region list this is retryable, so the
     * caller may submit the same image again later.
     */
    void onFailure(int requestId, String reason);
}
