// EdgeFit EF-BENCH-01: minimal JNI bridge to llama.cpp (core C API only).
// No chat templates, no server, no network. Single synchronous benchmark
// call with cooperative cancellation via a direct-byte mailbox.

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#if defined(__ANDROID__)
#include <malloc.h>
#endif

#include "llama.h"

namespace {

using Clock = std::chrono::steady_clock;

long long now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               Clock::now().time_since_epoch())
        .count();
}

std::string json_escape(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20 || c >= 0x7F) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

std::string error_json(const std::string & message) {
    return "{\"ok\":false,\"error\":\"" + json_escape(message) + "\"}";
}

int tokenize_all(const llama_vocab * vocab, const char * text,
                 std::vector<llama_token> & out) {
    const int n_max = 8192;
    out.resize(n_max);
    int n = llama_tokenize(vocab, text, static_cast<int32_t>(std::strlen(text)),
                           out.data(), n_max, true, true);
    if (n < 0) return -1;
    out.resize(n);
    return n;
}

void read_meta(llama_model * model, std::string & json_fields) {
    static const char * keys[] = {
        "general.name", "general.architecture", "general.quantization_version",
        "general.size_label", "tokenizer.ggml.model"
    };
    char buf[256];
    bool first = true;
    for (const char * key : keys) {
        int32_t n = llama_model_meta_val_str(model, key, buf, sizeof(buf));
        if (n < 0) continue;
        if (!first) json_fields += ",";
        first = false;
        json_fields += "\"" + json_escape(key) + "\":\"" + json_escape(buf) + "\"";
    }
}

std::string run_benchmark(const char * model_path, const char * prompt, int n_ctx,
                          int n_threads, int n_predict,
                          std::atomic<uint8_t> * cancel) {
    static std::once_flag backend_once;
    std::call_once(backend_once, []() { llama_backend_init(); });

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU backend only
    llama_model * model = llama_model_load_from_file(model_path, mparams);
    if (!model) return error_json("model load failed");

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx);
    cparams.n_batch = 512;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        llama_model_free(model);
        return error_json("context init failed");
    }
    // NOTE: load timing is derived Kotlin-side (wall minus phases).

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const llama_token eos = llama_vocab_eos(vocab);

    std::vector<llama_token> prompt_tokens;
    if (tokenize_all(vocab, prompt, prompt_tokens) < 0 || prompt_tokens.empty()) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        llama_model_free(model);
        return error_json("prompt tokenize failed");
    }
    if (static_cast<int>(prompt_tokens.size()) + n_predict > n_ctx) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        llama_model_free(model);
        return error_json("prompt plus cap exceeds context");
    }
    if (cancel->load()) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        llama_model_free(model);
        return "{\"ok\":true,\"cancelled\":true,\"generated_tokens\":0,"
               "\"prefill_ms\":0,\"gen_ms\":0,\"prefill_tokens\":0,"
               "\"eos_stop\":false,\"meta\":{}}";
    }

    // Ownership: llama_batch_get_one returns a NON-OWNING view of caller
    // memory (llama.cpp v0.7.0 src/llama-batch.cpp). Never free it; only
    // batches from llama_batch_init may go to llama_batch_free.
    const long long t_pref0 = now_ms();
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(),
                                            static_cast<int32_t>(prompt_tokens.size()));
    bool decode_ok = llama_decode(ctx, batch) == 0;
    const long long prefill_ms = now_ms() - t_pref0;
    if (!decode_ok) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        llama_model_free(model);
        return error_json("prefill decode failed");
    }

    const long long t_gen0 = now_ms();
    int generated = 0;
    bool eos_stop = false;
    bool cancelled = false;
    for (int i = 0; i < n_predict; i++) {
        if (cancel->load()) {
            cancelled = true;
            break;
        }
        const llama_token id = llama_sampler_sample(sampler, ctx, -1);
        if (id == eos) {
            eos_stop = true;
            break;
        }
        generated++;
        llama_batch step = llama_batch_get_one(const_cast<llama_token *>(&id), 1);
        const bool step_ok = llama_decode(ctx, step) == 0;
        // Non-owning view: do not free (see note at prefill above).
        if (!step_ok) {
            llama_sampler_free(sampler);
            llama_free(ctx);
            llama_model_free(model);
            return error_json("generation decode failed");
        }
    }
    const long long gen_ms = now_ms() - t_gen0;

    std::string meta;
    read_meta(model, meta);
    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_model_free(model);

    char out[1024];
    std::snprintf(out, sizeof(out),
                  "{\"ok\":true,\"cancelled\":%s,\"generated_tokens\":%d,"
                  "\"prefill_ms\":%lld,\"gen_ms\":%lld,\"prefill_tokens\":%zu,"
                  "\"eos_stop\":%s,\"meta\":{%s}}",
                  cancelled ? "true" : "false", generated, prefill_ms, gen_ms,
                  prompt_tokens.size(), eos_stop ? "true" : "false", meta.c_str());
    return std::string(out);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_ai_edgefit_diag_LlamaBridge_nativeVersion(JNIEnv * env,
                                                                         jobject) {
    return env->NewStringUTF(llama_version());
}

JNIEXPORT jlong JNICALL Java_ai_edgefit_diag_LlamaBridge_nativeAllocBytes(JNIEnv *,
                                                                          jobject) {
#if defined(__ANDROID__)
    return static_cast<jlong>(mallinfo2().uordblks);
#else
    return -1;
#endif
}

JNIEXPORT jstring JNICALL Java_ai_edgefit_diag_LlamaBridge_nativeRun(
    JNIEnv * env, jobject, jstring j_model_path, jstring j_prompt, jint n_ctx,
    jint n_threads, jint n_predict, jobject cancel_mailbox) {
    const char * model_path = env->GetStringUTFChars(j_model_path, nullptr);
    const char * prompt = env->GetStringUTFChars(j_prompt, nullptr);
    auto * cancel = static_cast<std::atomic<uint8_t> *>(
        env->GetDirectBufferAddress(cancel_mailbox));
    std::string out;
    if (!model_path || !prompt || !cancel) {
        out = error_json("null JNI argument");
    } else {
        out = run_benchmark(model_path, prompt, n_ctx, n_threads, n_predict, cancel);
    }
    if (model_path) env->ReleaseStringUTFChars(j_model_path, model_path);
    if (prompt) env->ReleaseStringUTFChars(j_prompt, prompt);
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
