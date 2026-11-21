#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <thread>
#include <android/log.h>

#include "llama.h"

#define TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model   * g_model = nullptr;
static llama_context * g_ctx   = nullptr;
static const llama_vocab * g_vocab = nullptr;
static bool g_backend_init = false;

// LoRA adapters (bisa banyak)
static std::vector<llama_adapter_lora*> g_loras;
static std::vector<float> g_loraScales;

static void freeLorasLocked() {
    for (auto * a : g_loras) {
        if (a) llama_adapter_lora_free(a);
    }
    g_loras.clear();
    g_loraScales.clear();
}

static bool applyLorasLocked() {
    if (!g_ctx) return false;
    if (g_loras.empty()) {
        llama_set_adapters_lora(g_ctx, nullptr, 0, nullptr);
        return true;
    }
    int32_t rc = llama_set_adapters_lora(g_ctx, g_loras.data(), g_loras.size(), g_loraScales.data());
    if (rc != 0) {
        LOGE("llama_set_adapters_lora failed: %d", (int) rc);
        return false;
    }
    return true;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_akijus_offlinellm_LlamaEngine_initModel(JNIEnv * env, jobject /*thiz*/, jstring jpath) {
    // model baru = adapters lama tidak valid lagi (ikut ke-free bersama model)
    // jadi kosongkan dulu tanpa double-free: free eksplisit sebelum free model
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    freeLorasLocked();
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
    }

    const char * cpath = env->GetStringUTFChars(jpath, nullptr);
    std::string path = cpath ? cpath : "";
    if (cpath) env->ReleaseStringUTFChars(jpath, cpath);

    if (path.empty()) {
        LOGE("initModel: empty path");
        return JNI_FALSE;
    }

    if (!g_backend_init) {
        llama_backend_init();
        g_backend_init = true;
    }

    llama_model_params mparams = llama_model_default_params();
    // HP offline: full CPU (Redmi 13 tidak ada GPU delegate yang stabil untuk llama)
    mparams.n_gpu_layers = 0;

    LOGI("Loading model: %s", path.c_str());
    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (!g_model) {
        LOGE("llama_model_load_from_file failed");
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = 2048;
    cparams.n_batch = 512;
    // Pakai CPU yang tersedia (kurangi 2 untuk UI), minimal 2 thread.
    // Penting untuk HP: thread lebih banyak = jawaban jauh lebih cepat.
    int hw = (int) std::thread::hardware_concurrency();
    if (hw < 2) hw = 2;
    cparams.n_threads = hw > 4 ? hw - 2 : hw;
    if (cparams.n_threads < 2) cparams.n_threads = 2;

    // API terbaru llama.cpp: llama_init_from_model (pengganti llama_new_context_with_model)
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        g_vocab = nullptr;
        return JNI_FALSE;
    }

    // pastikan tidak ada adapter nyangkut
    applyLorasLocked();

    LOGI("Model loaded OK");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_akijus_offlinellm_LlamaEngine_addLora(JNIEnv * env, jobject /*thiz*/, jstring jpath, jfloat jscale) {
    if (!g_model || !g_ctx) {
        LOGE("addLora: model belum di-load");
        return JNI_FALSE;
    }
    const char * cpath = env->GetStringUTFChars(jpath, nullptr);
    std::string path = cpath ? cpath : "";
    if (cpath) env->ReleaseStringUTFChars(jpath, cpath);
    if (path.empty()) return JNI_FALSE;

    float scale = (float) jscale;
    if (scale < -2.0f) scale = -2.0f;
    if (scale >  2.0f) scale =  2.0f;
    if (scale == 0.0f) scale = 1.0f;

    llama_adapter_lora * ad = llama_adapter_lora_init(g_model, path.c_str());
    if (!ad) {
        LOGE("llama_adapter_lora_init failed: %s", path.c_str());
        return JNI_FALSE;
    }
    g_loras.push_back(ad);
    g_loraScales.push_back(scale);

    if (!applyLorasLocked()) {
        LOGE("apply after addLora failed, rollback last");
        llama_adapter_lora_free(ad);
        g_loras.pop_back();
        g_loraScales.pop_back();
        applyLorasLocked();
        return JNI_FALSE;
    }
    LOGI("LoRA added OK (%zu adapters): %s scale=%.2f", g_loras.size(), path.c_str(), scale);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_akijus_offlinellm_LlamaEngine_removeLora(JNIEnv * /*env*/, jobject /*thiz*/, jint jindex) {
    int idx = (int) jindex;
    if (idx < 0 || idx >= (int) g_loras.size()) return JNI_FALSE;
    if (g_loras[idx]) llama_adapter_lora_free(g_loras[idx]);
    g_loras.erase(g_loras.begin() + idx);
    g_loraScales.erase(g_loraScales.begin() + idx);
    return applyLorasLocked() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_akijus_offlinellm_LlamaEngine_clearLoras(JNIEnv * /*env*/, jobject /*thiz*/) {
    freeLorasLocked();
    applyLorasLocked();
}

JNIEXPORT jint JNICALL
Java_com_akijus_offlinellm_LlamaEngine_getLoraCount(JNIEnv * /*env*/, jobject /*thiz*/) {
    return (jint) g_loras.size();
}

JNIEXPORT jstring JNICALL
Java_com_akijus_offlinellm_LlamaEngine_generateReply(JNIEnv * env, jobject /*thiz*/, jstring jprompt, jint jmaxTokens) {
    if (!g_model || !g_ctx || !g_vocab) {
        return env->NewStringUTF("[model belum di-load]");
    }

    const char * cprompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt = cprompt ? cprompt : "";
    if (cprompt) env->ReleaseStringUTFChars(jprompt, cprompt);
    int max_tokens = (int) jmaxTokens;
    if (max_tokens <= 0)   max_tokens = 128;
    if (max_tokens > 1024) max_tokens = 1024;

    // 1. Terapkan chat template bawaan model (wajib untuk model instruct
    // seperti Qwen: tanpa ini model sering langsung EOS / ngaco)
    std::string formatted;
    {
        llama_chat_message msgs[1];
        msgs[0].role = "user";
        msgs[0].content = prompt.c_str();
        int32_t need = llama_chat_apply_template(g_model, nullptr, msgs, 1, true, nullptr, 0);
        if (need > 0 && need < 32768) {
            std::vector<char> buf((size_t) need + 1);
            int32_t n = llama_chat_apply_template(g_model, nullptr, msgs, 1, true, buf.data(), (int32_t) buf.size());
            if (n > 0) formatted.assign(buf.data(), (size_t) n);
        }
        if (formatted.empty()) formatted = prompt; // fallback: prompt mentah
    }

    // 2. Tokenize prompt (yang sudah diformat template)
    const int n_prompt_max = (int) formatted.size() + 256;
    std::vector<llama_token> prompt_tokens(n_prompt_max);
    bool add_bos = llama_vocab_get_add_bos(g_vocab);
    int n_prompt = llama_tokenize(g_vocab, formatted.c_str(), (int) formatted.size(),
                                  prompt_tokens.data(), (int) prompt_tokens.size(),
                                  add_bos, true);
    if (n_prompt < 0) {
        LOGE("tokenize failed");
        return env->NewStringUTF("[gagal tokenize prompt]");
    }
    prompt_tokens.resize(n_prompt);

    // 3. Sampler suhu (bukan greedy): tidak gampang macet di pengulangan,
    // jawaban berhenti wajar di token akhir
    uint32_t seed = (uint32_t) std::chrono::system_clock::now().time_since_epoch().count();
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed));

    std::string out;
    std::vector<llama_token> batch_tokens;
    batch_tokens.reserve(prompt_tokens.size() + max_tokens);

    // Buffer batch manual (hindari API deprecated llama_batch_init / llama_batch_get_one)
    const int BATCH = 512;
    std::vector<llama_token>  tok(BATCH);
    std::vector<llama_pos>    pos(BATCH);
    std::vector<int32_t>      n_seq(BATCH);
    std::vector<llama_seq_id> seq0(BATCH, 0);
    std::vector<llama_seq_id*> seq_id(BATCH);
    std::vector<int8_t>       logits(BATCH);
    for (int i = 0; i < BATCH; i++) seq_id[i] = &seq0[i];

    llama_token eos = llama_vocab_eos(g_vocab);
    int n_past = 0;
    size_t prompt_pos = 0;
    int n_generated = 0;
    llama_token cur = -1;

    // Decode prompt per chunk, lalu generate satu-per-satu
    while (n_generated < max_tokens) {
        int n_eval = 0;
        if (prompt_pos < prompt_tokens.size()) {
            int chunk = (int) prompt_tokens.size() - (int) prompt_pos;
            if (chunk > BATCH) chunk = BATCH;
            for (int i = 0; i < chunk; i++) {
                tok[i]    = prompt_tokens[prompt_pos + i];
                pos[i]    = n_past + i;
                n_seq[i]  = 1;
                logits[i] = (i == chunk - 1) ? 1 : 0;
            }
            n_eval = chunk;
            prompt_pos += chunk;
        } else if (n_generated == 0 && cur == -1 && !prompt_tokens.empty()) {
            // prompt sudah habis, logits terakhir sudah dihitung -> langsung sample
            n_eval = 0;
        } else {
            tok[0]    = cur;
            pos[0]    = n_past;
            n_seq[0]  = 1;
            logits[0] = 1;
            n_eval = 1;
        }

        if (n_eval > 0) {
            llama_batch batch;
            batch.n_tokens = n_eval;
            batch.token    = tok.data();
            batch.embd     = nullptr;
            batch.pos      = pos.data();
            batch.n_seq_id = n_seq.data();
            batch.seq_id   = seq_id.data();
            batch.logits   = logits.data();

            if (llama_decode(g_ctx, batch) != 0) {
                LOGE("llama_decode failed");
                break;
            }
            n_past += n_eval;
        }

        cur = llama_sampler_sample(sampler, g_ctx, -1);
        if (cur == eos || llama_vocab_is_eog(g_vocab, cur)) break;

        char buf[256];
        int n = llama_token_to_piece(g_vocab, cur, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);

        n_generated++;
        batch_tokens.push_back(cur);
    }

    llama_sampler_free(sampler);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_akijus_offlinellm_LlamaEngine_releaseModel(JNIEnv * /*env*/, jobject /*thiz*/) {
    if (g_ctx)   { llama_free(g_ctx);       g_ctx = nullptr; }
    // adapters ikut ke-free bersama model, jadi kosongkan vektor tanpa double-free
    // tapi kalau masih ada yang belum di-free manual, free dulu sebelum free model
    freeLorasLocked();
    if (g_model) { llama_model_free(g_model); g_model = nullptr; g_vocab = nullptr; }
}

} // extern "C"
