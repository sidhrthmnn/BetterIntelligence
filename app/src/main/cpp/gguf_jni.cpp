#include <jni.h>
#include <llama.h>
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex guard;
std::once_flag initialized;
using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
Model model(nullptr, llama_model_free);
std::string path;
std::string bytes(JNIEnv *env, jbyteArray array) {
    std::string value(env->GetArrayLength(array), '\0');
    env->GetByteArrayRegion(array, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
jbyteArray array(JNIEnv *env, const std::string &text) {
    auto result = env->NewByteArray(text.size());
    if (result) env->SetByteArrayRegion(result, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return result;
}
void fail(JNIEnv *env, const std::exception &error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
// A token can end inside a UTF-8 code point. Send only complete characters.
size_t utf8Prefix(const std::string &text) {
    size_t end = text.size();
    if (!end) return 0;
    size_t start = end - 1;
    while (start > 0 && (static_cast<unsigned char>(text[start]) & 0xC0) == 0x80) --start;
    const auto lead = static_cast<unsigned char>(text[start]);
    size_t length = lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
    return end - start < length ? start : end;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_NativeGguf_prepare(JNIEnv *env, jobject, jbyteArray pathBytes) {
    try {
        std::lock_guard<std::mutex> lock(guard);
        std::call_once(initialized, [] { llama_backend_init(); });
        const auto next = bytes(env, pathBytes);
        if (model && path == next) return;
        // Release previous weights before allocating another model.
        model.reset(); path.clear();
        auto params = llama_model_default_params();
        params.n_gpu_layers = 0; params.use_mmap = true;
        model.reset(llama_model_load_from_file(next.c_str(), params));
        if (!model) throw std::runtime_error("Cannot load GGUF weights; check format and available memory");
        path = next;
    } catch (const std::exception &e) { fail(env, e); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_NativeGguf_release(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(guard);
    model.reset(); path.clear();
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_engine_NativeGguf_generate(JNIEnv *env, jobject, jbyteArray promptBytes,
        jobject observer, jint maxTokens, jint threads, jint contextSize,
        jfloat temperature, jfloat topP, jint topK) {
    try {
        std::lock_guard<std::mutex> lock(guard);
        if (!model) throw std::runtime_error("No GGUF model selected");
        auto observerClass = env->GetObjectClass(observer);
        auto cancelled = env->GetMethodID(observerClass, "isCancelled", "()Z");
        auto onText = env->GetMethodID(observerClass, "onText", "([BII)V");
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(180);
        auto check = [&] {
            if (env->ExceptionCheck()) throw std::runtime_error("Callback failed");
            if (env->CallBooleanMethod(observer, cancelled)) throw std::runtime_error("Inference cancelled");
            if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Inference timed out; try a shorter prompt");
        };
        check();
        const auto user = bytes(env, promptBytes);
        const llama_chat_message message{"user", user.c_str()};
        const auto *chatTemplate = llama_model_chat_template(model.get(), nullptr);
        if (!chatTemplate) throw std::runtime_error("Model has no chat template; import an instruction model");
        int size = llama_chat_apply_template(chatTemplate, &message, 1, true, nullptr, 0);
        if (size <= 0) throw std::runtime_error("Unsupported chat template");
        std::vector<char> formatted(size + 1);
        size = llama_chat_apply_template(chatTemplate, &message, 1, true, formatted.data(), formatted.size());
        if (size <= 0 || size > static_cast<int>(formatted.size())) throw std::runtime_error("Cannot format prompt");
        const auto *vocab = llama_model_get_vocab(model.get());
        int count = -llama_tokenize(vocab, formatted.data(), size, nullptr, 0, true, true);
        if (count <= 0 || count + maxTokens > contextSize) throw std::runtime_error("Prompt exceeds context; shorten it or reduce output tokens");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, formatted.data(), size, tokens.data(), count, true, true) != count)
            throw std::runtime_error("Tokenization failed");
        auto params = llama_context_default_params();
        params.n_ctx = contextSize; params.n_batch = 128; params.n_ubatch = 128;
        params.n_threads = threads; params.n_threads_batch = threads;
        using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
        Context context(llama_init_from_model(model.get(), params), llama_free);
        if (!context) throw std::runtime_error("Not enough memory for inference context");
        using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
        if (temperature <= 0) llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        else {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(topK));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(topP, 1));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }
        for (int i = 0; i < count; i += 128) {
            check();
            auto batch = llama_batch_get_one(tokens.data() + i, std::min(128, count - i));
            if (llama_decode(context.get(), batch)) throw std::runtime_error("Prompt evaluation failed");
        }
        std::string output;
        for (int i = 0; i < maxTokens; ++i) {
            check();
            auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (length < 0) { piece.resize(-length); length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (length < 0) throw std::runtime_error("Token decoding failed");
            output.append(piece.data(), length);
            auto value = array(env, output.substr(0, utf8Prefix(output)));
            if (!value) throw std::runtime_error("Output allocation failed");
            env->CallVoidMethod(observer, onText, value, count, i + 1);
            env->DeleteLocalRef(value);
            check();
            if (i + 1 < maxTokens) {
                auto batch = llama_batch_get_one(&token, 1);
                if (llama_decode(context.get(), batch)) throw std::runtime_error("Generation failed");
            }
        }
        return array(env, output.substr(0, utf8Prefix(output)));
    } catch (const std::exception &e) { fail(env, e); return nullptr; }
}
