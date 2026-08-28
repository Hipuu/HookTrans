package io.hooktrans.ipc;

oneway interface ITranslateCallback {
    /**
     * results[i] is the translation of the sources[i] passed to translateAsync, or null if
     * that particular string could not be translated. The arrays are always the same length.
     */
    void onBatch(int requestId, in String[] sources, in String[] results);

    void onFailure(int requestId, String reason);
}
