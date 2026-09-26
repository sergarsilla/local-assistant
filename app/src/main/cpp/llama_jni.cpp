#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "json.h"
#include "llama.h"
#include "sampling.h"

#define LOG_TAG "LlamaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kBatchSize = 512;
constexpr int kParseEveryTokens = 8;

enum GenerateStatus : jint {
    kOk = 0,
    kContextOverflow = 1,
    kDecodeFailed = 2,
    kToolCalls = 3,
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
    // Reading every weight into memory of our own was quickest while models fitted what Android
    // lets one app hold, and ruinous once they did not: those pages have nowhere to go but zram,
    // and get compressed and fetched back as the model works. Letting llama.cpp choose measured
    // best on both counts here (tok/s decoding, Tensor G5, 4096 tokens of context):
    //
    //                          own memory      mapped file      llama.cpp's choice
    //   Gemma 4 E2B Q4_0           9.7           6.4, 5.4              9.4
    //   Qwen3 4B Q4_0          minutes           7.6                   3.8
    modelParams.load_mode = LLAMA_LOAD_MODE_AUTO;
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
 * Renders the chat with the model's own template, reuses the cached prefix, and streams the
 * reply's text to `callback.onBytes(byte[]): boolean` as it is produced. Tool calls, when the
 * model makes them, are reported through `callback.onToolCall(String, String)` and the return
 * value is `kToolCalls`. Fills `stats` with {promptTokens, reusedTokens, prefillMs,
 * generatedTokens, generateMs}.
 *
 * `messagesJson` and `toolsJson` use the OpenAI-compatible shapes llama.cpp already understands.
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_localassistant_llm_LlamaCppEngine_nativeGenerate(JNIEnv *env, jclass, jlong handle,
                                                          jstring jmessages, jstring jtools,
                                                          jfloat temperature, jint maxTokens,
                                                          jobject callback, jlongArray stats) {
    auto *session = reinterpret_cast<Session *>(handle);
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(callbackClass, "onBytes", "([B)Z");
    jmethodID onToolCall = env->GetMethodID(callbackClass, "onToolCall",
                                            "(Ljava/lang/String;Ljava/lang/String;)V");

    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = false;
    // Thinking models still emit their <think> block; this keeps it out of the visible answer.
    inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
    const char *messagesJson = env->GetStringUTFChars(jmessages, nullptr);
    const char *toolsJson = env->GetStringUTFChars(jtools, nullptr);
    try {
        inputs.messages = common_chat_msgs_parse_oaicompat(common_json::parse(messagesJson));
        inputs.tools = common_chat_tools_parse_oaicompat(common_json::parse(toolsJson));
    } catch (const std::exception &e) {
        LOGE("Could not read the conversation: %s", e.what());
        env->ReleaseStringUTFChars(jmessages, messagesJson);
        env->ReleaseStringUTFChars(jtools, toolsJson);
        return kDecodeFailed;
    }
    env->ReleaseStringUTFChars(jmessages, messagesJson);
    env->ReleaseStringUTFChars(jtools, toolsJson);

    const common_chat_params chatParams = common_chat_templates_apply(session->templates.get(), inputs);
    LOGI("Chat format: %s (%zu tools)", common_chat_format_name(chatParams.format), inputs.tools.size());
    common_chat_parser_params parserParams(chatParams);
    // Tool-call formats come with a PEG grammar; without loading it the parser only sees text.
    if (!chatParams.parser.empty()) parserParams.parser.load(chatParams.parser);
    parserParams.reasoning_format = COMMON_REASONING_FORMAT_AUTO;

    llama_context *ctx = session->ctx;
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    // The template leaves the assistant's turn header out of the prompt; without it the model
    // writes the header itself and the reply parser cannot find the tool calls.
    const std::string prompt = chatParams.prompt + chatParams.generation_prompt;
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
    std::string raw;
    std::string streamed;
    long long parseUs = 0;
    int generated = 0;
    jint status = kOk;
    bool keepGoing = true;
    while (keepGoing && generated < maxTokens) {
        llama_token token = common_sampler_sample(sampler, ctx, -1);
        common_sampler_accept(sampler, token, true);
        if (llama_vocab_is_eog(vocab, token)) break;
        ++generated;

        raw += common_token_to_piece(ctx, token, /*special=*/false);
        // Parsing as we go keeps tool-call syntax out of what the user sees, but running the
        // grammar over the whole reply on every token would cost more than generating it.
        std::string content = streamed;
        if (generated % kParseEveryTokens == 0) {
            const auto parseStart = Clock::now();
            try {
                content = common_chat_parse(raw, /*is_partial=*/true, parserParams).content;
            } catch (const std::exception &) {
                content = streamed;
            }
            parseUs += std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - parseStart).count();
        }
        if (content.size() > streamed.size()) {
            const std::string delta = content.substr(streamed.size());
            const size_t ready = completeUtf8Prefix(delta);
            if (ready > 0) {
                jbyteArray chunk = env->NewByteArray(static_cast<jsize>(ready));
                env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(ready),
                                        reinterpret_cast<const jbyte *>(delta.data()));
                keepGoing = env->CallBooleanMethod(callback, onBytes, chunk);
                env->DeleteLocalRef(chunk);
                streamed += delta.substr(0, ready);
            }
        }

        std::vector<llama_token> next = {token};
        if (!decodeTokens(ctx, next, 0)) {
            status = kDecodeFailed;
            break;
        }
        session->cached.push_back(token);
    }
    common_sampler_free(sampler);

    LOGI("Raw reply (%d tokens, %lldms parsing): |%s|", generated, parseUs / 1000, raw.substr(0, 100).c_str());
    if (status == kOk) {
        try {
            const common_chat_msg reply = common_chat_parse(raw, /*is_partial=*/false, parserParams);
            LOGI("Parsed: %zu tool calls, content '%s'", reply.tool_calls.size(), reply.content.substr(0, 60).c_str());
            for (const auto &call : reply.tool_calls) {
                jstring name = env->NewStringUTF(call.name.c_str());
                jstring arguments = env->NewStringUTF(call.arguments.c_str());
                env->CallVoidMethod(callback, onToolCall, name, arguments);
                env->DeleteLocalRef(name);
                env->DeleteLocalRef(arguments);
                status = kToolCalls;
            }
        } catch (const std::exception &e) {
            LOGE("Could not read the reply: %s", e.what());
        }
    }

    jlong values[5] = {static_cast<jlong>(tokens.size()), static_cast<jlong>(reused), prefillMs, generated,
                       elapsedMs(generateStart)};
    env->SetLongArrayRegion(stats, 0, 5, values);
    return status;
}
