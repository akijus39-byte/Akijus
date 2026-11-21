package com.akijus.offlinellm

class LlamaEngine {
    companion object {
        init {
            System.loadLibrary("llama_jni")
        }
    }

    external fun initModel(path: String): Boolean
    external fun generateReply(prompt: String, maxTokens: Int): String
    external fun releaseModel()
}
