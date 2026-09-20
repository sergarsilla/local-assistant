#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kBatchSize = 512;

enum GenerateStatus : jint {
    kOk = 0,
    kContextOverflow = 1,
    kDecodeFailed = 2,
};

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    common_chat_templates_ptr templates;
    // Tokens currently held in the KV cache, used to reuse the shared prefix between turns.
    std::vector<llama_token> cached;
};

using Clock = std::chrono::steady_clock;

long long elapsedMs(Clock::time_point since) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - since).count();
}

void logCallback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
}

// Length of the longest prefix of `bytes` that ends on a complete UTF-8 sequence.
size_t completeUtf8Prefix(const std::string &bytes) {
    size_t i = bytes.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        auto c = static_cast<unsigned char>(bytes[i - 1]);
        if ((c & 0xC0) != 0x80) {
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (back + 1 >= need) ? bytes.size() : i - 1;
        }
        --i;
        ++back;
    }
    return bytes.size();
}

bool decodeTokens(llama_context *ctx, const std::vector<llama_token> &tokens, size_t from) {
    for (size_t i = from; i < tokens.size(); i += kBatchSize) {
        auto n = static_cast<int32_t>(std::min<size_t>(kBatchSize, tokens.size() - i));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data() + i), n);
        if (llama_decode(ctx, batch) != 0) return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_localassistant_llm_LlamaCppEngine_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx,
                                                      jint nThreads) {
    static bool backendReady = false;
    if (!backendReady) {
        llama_log_set(logCallback, nullptr);
        llama_backend_init();
        backendReady = true;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params modelParams = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, modelParams);
    env->ReleaseStringUTFChars(jpath, path);
    if (model == nullptr) return 0;

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(nCtx);
    ctxParams.n_batch = kBatchSize;
    ctxParams.n_ubatch = kBatchSize;
    ctxParams.n_threads = nThreads;
    ctxParams.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->templates = common_chat_templates_init(model, "");
    LOGI("Loaded model, n_ctx=%d threads=%d", nCtx, nThreads);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_localassistant_llm_LlamaCppEngine_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    session->templates.reset();
    llama_free(session->ctx);
    llama_model_free(session->model);
    delete session;
}

/**
 * Renders the chat with the model's own template, reuses the cached prefix, and streams complete
 * UTF-8 chunks to `callback.onBytes(byte[]): boolean` until EOG, `maxTokens`, or the callback
 * returns false. Fills `stats` with {promptTokens, reusedTokens, prefillMs, generatedTokens, generateMs,
 * sampleMs, decodeMs}.
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_localassistant_llm_LlamaCppEngine_nativeGenerate(JNIEnv *env, jclass, jlong handle,
                                                          jobjectArray roles, jobjectArray contents,
                                                          jfloat temperature, jint maxTokens,
                                                          jobject callback, jlongArray stats) {
    auto *session = reinterpret_cast<Session *>(handle);
    jmethodID onBytes = env->GetMethodID(env->GetObjectClass(callback), "onBytes", "([B)Z");

    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = false;
    const jsize count = env->GetArrayLength(roles);
    for (jsize i = 0; i < count; ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        const char *r = env->GetStringUTFChars(role, nullptr);
        const char *c = env->GetStringUTFChars(content, nullptr);
        common_chat_msg msg;
        msg.role = r;
        msg.content = c;
        inputs.messages.push_back(std::move(msg));
        env->ReleaseStringUTFChars(role, r);
        env->ReleaseStringUTFChars(content, c);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    const std::string prompt = common_chat_templates_apply(session->templates.get(), inputs).prompt;

    llama_context *ctx = session->ctx;
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    std::vector<llama_token> tokens = common_tokenize(ctx, prompt, /*add_special=*/true, /*parse_special=*/true);
    if (tokens.size() + static_cast<size_t>(maxTokens) > llama_n_ctx(ctx)) return kContextOverflow;

    size_t reused = 0;
    while (reused < tokens.size() && reused < session->cached.size() && tokens[reused] == session->cached[reused]) {
        ++reused;
    }
    // At least the last prompt token must be decoded to get fresh logits.
    reused = std::min(reused, tokens.size() - 1);
    llama_memory_seq_rm(llama_get_memory(ctx), 0, static_cast<llama_pos>(reused), -1);
    session->cached.assign(tokens.begin(), tokens.begin() + static_cast<long>(reused));

    const auto prefillStart = Clock::now();
    if (!decodeTokens(ctx, tokens, reused)) {
        session->cached.clear();
        llama_memory_clear(llama_get_memory(ctx), true);
        return kDecodeFailed;
    }
    session->cached = tokens;
    const long long prefillMs = elapsedMs(prefillStart);

    common_params_sampling samplingParams;
    samplingParams.temp = temperature;
    common_sampler *sampler = common_sampler_init(session->model, samplingParams);

    const auto generateStart = Clock::now();
    std::string pending;
    int generated = 0;
    jint status = kOk;
    bool keepGoing = true;
    long long sampleUs = 0;
    long long decodeUs = 0;
    while (keepGoing && generated < maxTokens) {
        const auto sampleStart = Clock::now();
        llama_token token = common_sampler_sample(sampler, ctx, -1);
        common_sampler_accept(sampler, token, true);
        sampleUs += std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - sampleStart).count();
        if (llama_vocab_is_eog(vocab, token)) break;
        ++generated;

        pending += common_token_to_piece(ctx, token, /*special=*/false);
        const size_t ready = completeUtf8Prefix(pending);
        if (ready > 0) {
            jbyteArray chunk = env->NewByteArray(static_cast<jsize>(ready));
            env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(ready),
                                    reinterpret_cast<const jbyte *>(pending.data()));
            keepGoing = env->CallBooleanMethod(callback, onBytes, chunk);
            env->DeleteLocalRef(chunk);
            pending.erase(0, ready);
        }

        std::vector<llama_token> next = {token};
        const auto decodeStart = Clock::now();
        const bool decoded = decodeTokens(ctx, next, 0);
        decodeUs += std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - decodeStart).count();
        if (!decoded) {
            status = kDecodeFailed;
            break;
        }
        session->cached.push_back(token);
    }
    common_sampler_free(sampler);

    jlong values[7] = {static_cast<jlong>(tokens.size()), static_cast<jlong>(reused), prefillMs, generated,
                       elapsedMs(generateStart), sampleUs / 1000, decodeUs / 1000};
    env->SetLongArrayRegion(stats, 0, 7, values);
    return status;
}
