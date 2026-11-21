#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "OfflineLLM-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    llama_model* g_model = nullptr;
    llama_context* g_ctx = nullptr;
    std::atomic<bool> g_stop_requested{false};

    void free_context_and_model() {
        if (g_ctx != nullptr) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        if (g_model != nullptr) {
            llama_model_free(g_model);
            g_model = nullptr;
        }
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_akijus_offlinellm_LlamaEngine_nativeInitBackend(JNIEnv*, jobject) {
    llama_backend_init();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_akijus_offlinellm_LlamaEngine_nativeFreeBackend(JNIEnv*, jobject) {
    free_context_and_model();
    llama_backend_free();
}

JNIEXPORT jboolean JNICALL
Java_com_akijus_offlinellm_LlamaEngine_nativeLoadModel(
        JNIEnv* env,
        jobject,
        jstring modelPath,
        jint nCtx,
        jint nThreads
) {
    free_context_and_model();

    const char* path_cstr = env->GetStringUTFChars(modelPath, nullptr);
    if (path_cstr == nullptr) {
        return JNI_FALSE;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path_cstr, model_params);
    env->ReleaseStringUTFChars(modelPath, path_cstr);

    if (g_model == nullptr) {
        LOGE("Gagal memuat model GGUF");
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx > 0 ? static_cast<uint32_t>(nCtx) : 2048u;
    ctx_params.n_batch = 512u;
    ctx_params.n_threads = nThreads > 0 ? nThreads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Gagal membuat llama_context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model berhasil dimuat dengan n_ctx=%u, threads=%d", ctx_params.n_ctx, ctx_params.n_threads);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_akijus_offlinellm_LlamaEngine_nativeStopGeneration(JNIEnv*, jobject) {
    g_stop_requested.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_akijus_offlinellm_LlamaEngine_nativeGenerate(
        JNIEnv* env,
        jobject,
        jstring prompt,
        jint maxTokens
) {
    if (g_model == nullptr || g_ctx == nullptr) {
        return env->NewStringUTF("[Error] Model belum dimuat.");
    }

    g_stop_requested.store(false);

    const char* prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_cstr == nullptr) {
        return env->NewStringUTF("[Error] Prompt tidak valid.");
    }

    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    if (vocab == nullptr) {
        return env->NewStringUTF("[Error] Vocabulary model tidak tersedia.");
    }

    const int n_prompt_tokens = -llama_tokenize(
        vocab,
        prompt_str.c_str(),
        static_cast<int32_t>(prompt_str.size()),
        nullptr,
        0,
        true,
        true
    );

    if (n_prompt_tokens <= 0) {
        return env->NewStringUTF("[Error] Gagal melakukan tokenisasi prompt.");
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt_tokens));
    if (llama_tokenize(
        vocab,
        prompt_str.c_str(),
        static_cast<int32_t>(prompt_str.size()),
        prompt_tokens.data(),
        static_cast<int32_t>(prompt_tokens.size()),
        true,
        true
    ) < 0) {
        return env->NewStringUTF("[Error] Tokenisasi prompt gagal.");
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_batch batch = llama_batch_init(static_cast<int32_t>(prompt_tokens.size()), 0, 1);
    for (size_t i = 0; i < prompt_tokens.size(); ++i) {
        batch.token[i] = prompt_tokens[i];
        batch.pos[i] = static_cast<llama_pos>(i);
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == prompt_tokens.size() - 1);
    }
    batch.n_tokens = static_cast<int32_t>(prompt_tokens.size());

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("[Error] Gagal memproses prompt di llama_decode.");
    }
    llama_batch_free(batch);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output_text;
    int32_t n_cur = static_cast<int32_t>(prompt_tokens.size());
    const int limit = maxTokens > 0 ? maxTokens : 256;
    const llama_token eos_token = llama_vocab_eos(vocab);

    for (int i = 0; i < limit; ++i) {
        if (g_stop_requested.load()) {
            output_text += "\n[Dihentikan]";
            break;
        }

        llama_token new_token = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, new_token) || new_token == eos_token) {
            break;
        }

        char buf[256];
        int n_chars = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            output_text.append(buf, static_cast<size_t>(n_chars));
        }

        llama_batch next_batch = llama_batch_init(1, 0, 1);
        next_batch.token[0] = new_token;
        next_batch.pos[0] = n_cur;
        next_batch.n_seq_id[0] = 1;
        next_batch.seq_id[0][0] = 0;
        next_batch.logits[0] = true;
        next_batch.n_tokens = 1;

        n_cur += 1;
        if (llama_decode(g_ctx, next_batch) != 0) {
            llama_batch_free(next_batch);
            break;
        }
        llama_batch_free(next_batch);
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(output_text.c_str());
}

} // extern "C"
