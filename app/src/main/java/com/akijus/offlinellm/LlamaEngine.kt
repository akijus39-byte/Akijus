package com.akijus.offlinellm

class LlamaEngine {

    private var isBackendReady = false
    @Volatile
    var isModelLoaded = false
        private set

    init {
        System.loadLibrary("offlinellm_native")
    }

    fun initBackend(): Boolean {
        if (!isBackendReady) {
            isBackendReady = nativeInitBackend()
        }
        return isBackendReady
    }

    fun loadModel(modelPath: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        if (!initBackend()) return false
        isModelLoaded = nativeLoadModel(modelPath, nCtx, nThreads)
        return isModelLoaded
    }

    fun generate(prompt: String, maxTokens: Int = 256): String {
        if (!isModelLoaded) {
            return "[Error] Model belum dimuat."
        }
        return nativeGenerate(prompt, maxTokens)
    }

    fun stopGeneration() {
        nativeStopGeneration()
    }

    fun release() {
        nativeFreeBackend()
        isBackendReady = false
        isModelLoaded = false
    }

    private external fun nativeInitBackend(): Boolean
    private external fun nativeFreeBackend()
    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeStopGeneration()
}
