#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <set>
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
constexpr size_t kMinTokensWorthSaving = 64;

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
    // Where the processed preface is kept between loads; empty when it is not kept.
    std::string statePath;
    // Set once the file turned out to be absent or unusable, so it is not read again in vain.
    bool stateMissing = false;
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

size_t commonPrefix(const std::vector<llama_token> &a, const std::vector<llama_token> &b) {
    size_t n = 0;
    while (n < a.size() && n < b.size() && a[n] == b[n]) ++n;
    return n;
}

/** Puts the saved preface into an empty context. On failure the context is left empty. */
bool restorePreface(Session *session) {
    if (session->statePath.empty() || session->stateMissing) return false;
    llama_context *ctx = session->ctx;
    std::vector<llama_token> saved(llama_n_ctx(ctx));
    size_t count = 0;
    const auto start = Clock::now();
    if (!llama_state_load_file(ctx, session->statePath.c_str(), saved.data(), saved.size(), &count)) {
        session->stateMissing = true;
        llama_memory_clear(llama_get_memory(ctx), true);
        return false;
    }
    saved.resize(count);
    session->cached = std::move(saved);
    LOGI("Restored %zu saved tokens in %lldms", count, elapsedMs(start));
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_localassistant_llm_LlamaCppEngine_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nCtx,
                                                      jint nThreads, jstring jstate) {
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
    const char *state = env->GetStringUTFChars(jstate, nullptr);
    session->statePath = state;
    env->ReleaseStringUTFChars(jstate, state);
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

    // Preparing generates nothing, so only what every conversation starts with is worked out
    // and kept: the prompt is cut where a different first question would start to differ.
    const bool preparing = maxTokens == 0;
    if (preparing && !inputs.messages.empty()) {
        common_chat_templates_inputs other = inputs;
        other.messages.back().content = "#";
        const common_chat_params otherParams = common_chat_templates_apply(session->templates.get(), other);
        const std::vector<llama_token> otherTokens = common_tokenize(
            ctx, otherParams.prompt + otherParams.generation_prompt, /*add_special=*/true, /*parse_special=*/true);
        tokens.resize(commonPrefix(tokens, otherTokens));
        if (tokens.empty()) return kOk;
    }

    llama_memory_t memory = llama_get_memory(ctx);
    // A fresh context first takes back the preface an earlier load saved: it is the same on
    // every load and takes many seconds to work through.
    if (session->cached.empty()) restorePreface(session);

    // Generating needs fresh logits, so at least the last prompt token is decoded again.
    const size_t reusable = preparing ? tokens.size() : tokens.size() - 1;
    size_t reused = std::min(commonPrefix(tokens, session->cached), reusable);
    if (reused < session->cached.size() && !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(reused), -1)) {
        // Models with recurrent layers cannot forget only the end of what they hold. Carrying
        // on regardless left the previous question in place and answered that one, so they go
        // back to the saved preface instead, or to nothing when it does not fit this prompt.
        llama_memory_clear(memory, true);
        session->cached.clear();
        reused = 0;
        if (restorePreface(session)) {
            if (commonPrefix(tokens, session->cached) == session->cached.size() && session->cached.size() <= reusable) {
                reused = session->cached.size();
            } else {
                llama_memory_clear(memory, true);
                session->cached.clear();
            }
        }
        LOGI("Could not trim the context; restarted from %zu saved tokens", reused);
    }
    session->cached.assign(tokens.begin(), tokens.begin() + static_cast<long>(reused));

    const auto prefillStart = Clock::now();
    if (!decodeTokens(ctx, tokens, reused)) {
        session->cached.clear();
        llama_memory_clear(memory, true);
        return kDecodeFailed;
    }
    session->cached = tokens;
    const long long prefillMs = elapsedMs(prefillStart);
    // Saved only when enough of it had to be worked out to make the next load quicker.
    if (preparing && !session->statePath.empty() && tokens.size() - reused > kMinTokensWorthSaving) {
        const auto saveStart = Clock::now();
        const bool saved = llama_state_save_file(ctx, session->statePath.c_str(), tokens.data(), tokens.size());
        session->stateMissing = !saved;
        LOGI("Saved %zu tokens for the next load in %lldms (%s)", tokens.size(), elapsedMs(saveStart), saved ? "ok" : "failed");
    }
    if (preparing) {
        jlong values[5] = {static_cast<jlong>(tokens.size()), static_cast<jlong>(reused), prefillMs, 0, 0};
        env->SetLongArrayRegion(stats, 0, 5, values);
        return kOk;
    }

    common_params_sampling samplingParams;
    samplingParams.temp = temperature;
    common_sampler *sampler = common_sampler_init(session->model, samplingParams);

    // Special tokens are left out of the text, except the ones a tool call is written with:
    // without them the parser sees an ordinary sentence and the call is shown, not run.
    std::set<llama_token> keptSpecial;
    for (const std::string &text : chatParams.preserved_tokens) {
        const std::vector<llama_token> ids = common_tokenize(vocab, text, /*add_special=*/false, /*parse_special=*/true);
        if (ids.size() == 1) keptSpecial.insert(ids[0]);
    }

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

        raw += common_token_to_piece(ctx, token, /*special=*/keptSpecial.count(token) > 0);
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
            // Text only goes out every few tokens, so the words after the last of those rounds
            // are still unsent here; without this every reply lost its ending.
            if (keepGoing && reply.content.size() > streamed.size()) {
                const std::string rest = reply.content.substr(streamed.size());
                jbyteArray chunk = env->NewByteArray(static_cast<jsize>(rest.size()));
                env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(rest.size()),
                                        reinterpret_cast<const jbyte *>(rest.data()));
                env->CallBooleanMethod(callback, onBytes, chunk);
                env->DeleteLocalRef(chunk);
            }
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
