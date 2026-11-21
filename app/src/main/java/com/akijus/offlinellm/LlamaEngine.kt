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

    // LoRA adapters (bisa banyak)
    external fun addLora(path: String, scale: Float): Boolean
    external fun removeLora(index: Int): Boolean
    external fun clearLoras()
    external fun getLoraCount(): Int
}
