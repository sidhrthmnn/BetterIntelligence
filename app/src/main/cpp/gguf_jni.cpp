#include <jni.h>
#include <llama.h>
#include <ggml-backend.h>
#include <android/log.h>
#include <dlfcn.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <sstream>
#include <atomic>
#include <cstdio>
#include <cstring>

namespace {
std::mutex guard;
std::once_flag initialized;
using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
Model model(nullptr, llama_model_free);
std::string path;
int loadedLayers = 0;
std::atomic<int> observedLayers{0};
ggml_backend_dev_t gpu = nullptr;
bool probed = false;
std::string gpuReason = "Not probed", gpuName;
size_t gpuFree = 0, gpuTotal = 0;

void initialize() {
    std::call_once(initialized, [] {
        llama_log_set([](ggml_log_level level, const char *text, void *) {
            int layers = 0, total = 0;
            const char *offload = strstr(text, "offloaded ");
            if (offload && sscanf(offload, "offloaded %d/%d layers", &layers, &total) == 2) observedLayers = layers;
            __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "BetterGguf", text);
        }, nullptr);
        if (!ggml_backend_load("libggml-cpu.so")) throw std::runtime_error("Packaged CPU backend could not be loaded");
        llama_backend_init();
    });
}

// Preflight before upstream registration, which aborts on a pre-1.2 loader.
// No vendor-name guessing. Exclude software Vulkan and require actual compute
// queues, 16-bit storage and the features used by this pinned backend.
void probeGpu() {
    if (probed) return;
    probed = true;
    void *loader = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!loader) { gpuReason = "Android Vulkan loader unavailable"; return; }
    struct Close { void *p; ~Close() { dlclose(p); } } close{loader};
    auto get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr"));
    if (!get) { gpuReason = "Vulkan entry point unavailable"; return; }
    auto versionFn = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(get(nullptr, "vkEnumerateInstanceVersion"));
    uint32_t version = 0;
    if (!versionFn || versionFn(&version) != VK_SUCCESS || version < VK_API_VERSION_1_2) {
        gpuReason = "Packaged backend requires Vulkan 1.2"; return;
    }
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; info.pApplicationInfo = &app;
    auto create = reinterpret_cast<PFN_vkCreateInstance>(get(nullptr, "vkCreateInstance"));
    VkInstance instance = VK_NULL_HANDLE;
    if (!create || create(&info, nullptr, &instance) != VK_SUCCESS) { gpuReason = "Vulkan instance creation failed"; return; }
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(get(instance, "vkDestroyInstance"));
    struct Instance { VkInstance i; PFN_vkDestroyInstance d; ~Instance() { if (d) d(i, nullptr); } } cleanup{instance, destroy};
    if (!destroy) { gpuReason = "Vulkan instance cleanup entry point missing"; return; }
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(get(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(get(instance, "vkGetPhysicalDeviceProperties"));
    auto features = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(get(instance, "vkGetPhysicalDeviceFeatures2"));
    auto queues = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(get(instance, "vkGetPhysicalDeviceQueueFamilyProperties"));
    if (!enumerate || !properties || !features || !queues) { gpuReason = "Required Vulkan 1.2 entry points missing"; return; }
    uint32_t count = 0;
    if (enumerate(instance, &count, nullptr) != VK_SUCCESS || count == 0) { gpuReason = "No Vulkan device"; return; }
    std::vector<VkPhysicalDevice> devices(count);
    if (enumerate(instance, &count, devices.data()) != VK_SUCCESS) { gpuReason = "Cannot enumerate Vulkan devices"; return; }
    int selected = -1;
    gpuReason = "No hardware Vulkan 1.2 compute device with 16-bit storage";
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceProperties props{}; properties(devices[i], &props);
        if (props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU) { gpuReason = std::string("Software Vulkan is not GPU acceleration: ") + props.deviceName; continue; }
        if (props.apiVersion < VK_API_VERSION_1_2) continue;
        std::string name = props.deviceName;
        if (name.find("SwiftShader") != std::string::npos || name.find("llvmpipe") != std::string::npos) continue;
        VkPhysicalDeviceVulkan11Features f11{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES};
        VkPhysicalDeviceFeatures2 f{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2}; f.pNext = &f11;
        features(devices[i], &f);
        if (!f11.storageBuffer16BitAccess) continue;
        uint32_t n = 0; queues(devices[i], &n, nullptr);
        std::vector<VkQueueFamilyProperties> families(n); queues(devices[i], &n, families.data());
        if (std::none_of(families.begin(), families.end(), [](const auto &q) { return q.queueCount && (q.queueFlags & VK_QUEUE_COMPUTE_BIT); })) continue;
        selected = i; gpuName = name + " (driver " + std::to_string(props.driverVersion) + ", API " + std::to_string(props.apiVersion) + ")"; break;
    }
    if (selected < 0) return;
    setenv("GGML_VK_VISIBLE_DEVICES", std::to_string(selected).c_str(), 1);
    try {
        auto reg = ggml_backend_load("libggml-vulkan.so");
        if (!reg || ggml_backend_reg_dev_count(reg) == 0) { gpuReason = "Packaged Vulkan backend rejected device"; return; }
        gpu = ggml_backend_reg_dev_get(reg, 0);
        // This creates the real backend/logical device. Model operators are
        // checked separately by preparing and decoding a warm-up graph.
        auto backend = ggml_backend_dev_init(gpu, nullptr);
        if (!backend) { gpu = nullptr; gpuReason = "Vulkan backend initialisation failed"; return; }
        ggml_backend_free(backend);
        ggml_backend_dev_memory(gpu, &gpuFree, &gpuTotal);
        gpuReason = "Vulkan runtime ready; model compatibility needs a successful load and decode";
    } catch (const std::exception &e) { gpu = nullptr; gpuReason = e.what(); }
}

std::string quoted(const std::string &s) {
    std::string out = "\"";
    for (unsigned char c : s) { if (c == '"' || c == '\\') out += '\\'; if (c >= 32) out += c; }
    return out + "\"";
}
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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_engine_NativeGguf_runtimeInfo(JNIEnv *env, jobject, jboolean allowGpuProbe) {
    try {
        std::lock_guard<std::mutex> lock(guard);
        initialize(); if (allowGpuProbe) probeGpu();
        if (gpu) ggml_backend_dev_memory(gpu, &gpuFree, &gpuTotal);
        std::ostringstream json;
        json << "{\"gpuAvailable\":" << (gpu ? "true" : "false") << ",\"gpuName\":" << quoted(gpuName)
             << ",\"gpuReason\":" << quoted(gpuReason) << ",\"gpuFreeBytes\":" << gpuFree
             << ",\"gpuTotalBytes\":" << gpuTotal << ",\"cpuBuild\":" << quoted(llama_print_system_info()) << "}";
        return array(env, json.str());
    } catch (const std::exception &e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_engine_NativeGguf_prepare(JNIEnv *env, jobject, jbyteArray pathBytes, jint gpuLayers, jint threads, jint contextSize) {
    try {
        std::lock_guard<std::mutex> lock(guard);
        initialize();
        const auto next = bytes(env, pathBytes);
        if (model && path == next && loadedLayers == gpuLayers) return loadedLayers;
        // Release previous weights before allocating another model.
        model.reset(); path.clear(); loadedLayers = 0; observedLayers = 0;
        auto params = llama_model_default_params();
        if (gpuLayers > 0) { probeGpu(); if (!gpu) throw std::runtime_error(gpuReason); }
        ggml_backend_dev_t selected[] = {gpuLayers > 0 ? gpu : nullptr, nullptr};
        params.devices = selected;
        params.n_gpu_layers = std::max(0, gpuLayers); params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model.reset(llama_model_load_from_file(next.c_str(), params));
        if (!model) throw std::runtime_error("Cannot load GGUF weights; check format and available memory");
        try {
            if (gpuLayers > 0) {
                if (observedLayers <= 0) throw std::runtime_error("Model loaded without any GPU layers");
                auto cp = llama_context_default_params();
                struct Proof { ggml_backend_dev_t device; int matrixOps = 0; } proof{gpu};
                cp.cb_eval = [](ggml_tensor *tensor, bool ask, void *data) -> bool {
                    const bool matrix = tensor->op == GGML_OP_MUL_MAT || tensor->op == GGML_OP_MUL_MAT_ID;
                    if (ask) return matrix;
                    auto *p = static_cast<Proof *>(data);
                    auto *buffer = tensor->buffer;
                    if (!buffer && tensor->view_src) buffer = tensor->view_src->buffer;
                    if (matrix && buffer && ggml_backend_buft_get_device(ggml_backend_buffer_get_type(buffer)) == p->device) ++p->matrixOps;
                    return true;
                };
                cp.cb_eval_user_data = &proof;
                cp.n_ctx = contextSize; cp.n_batch = 32; cp.n_ubatch = 32;
                cp.n_threads = threads; cp.n_threads_batch = threads;
                using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
                Context warmup(llama_init_from_model(model.get(), cp), llama_free);
                if (!warmup) throw std::runtime_error("Vulkan context allocation failed");
                llama_token token = llama_vocab_bos(llama_model_get_vocab(model.get()));
                if (token < 0) token = 0;
                if (llama_decode(warmup.get(), llama_batch_get_one(&token, 1))) throw std::runtime_error("Vulkan model warm-up failed");
                llama_synchronize(warmup.get());
                if (proof.matrixOps == 0) throw std::runtime_error("Vulkan warm-up executed no GPU matrix operations; using CPU");
                __android_log_print(ANDROID_LOG_INFO, "BetterGguf", "Verified Vulkan: %d offloaded layers, %d GPU matrix operations", observedLayers.load(), proof.matrixOps);
            }
        } catch (...) { model.reset(); throw; }
        path = next;
        loadedLayers = observedLayers;
        return loadedLayers;
    } catch (const std::exception &e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_NativeGguf_release(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(guard);
    model.reset(); path.clear(); loadedLayers = 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_example_engine_NativeGguf_generate(JNIEnv *env, jobject, jbyteArray promptBytes,
        jbyteArray systemBytes, jobject observer, jint maxTokens, jint threads, jint contextSize,
        jfloat temperature, jfloat topP, jint topK, jint timeoutMs) {
    try {
        std::lock_guard<std::mutex> lock(guard);
        if (!model) throw std::runtime_error("No GGUF model selected");
        auto observerClass = env->GetObjectClass(observer);
        auto cancelled = env->GetMethodID(observerClass, "isCancelled", "()Z");
        auto onText = env->GetMethodID(observerClass, "onText", "([BII)V");
        auto resourceThreads = env->GetMethodID(observerClass, "resourceThreads", "()I");
        auto onPerformance = env->GetMethodID(observerClass, "onPerformance", "(DDII)V");
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
        auto check = [&] {
            if (env->ExceptionCheck()) throw std::runtime_error("Callback failed");
            if (env->CallBooleanMethod(observer, cancelled)) throw std::runtime_error("Inference cancelled");
            if (std::chrono::steady_clock::now() >= deadline) throw std::runtime_error("Inference timed out; try a shorter prompt");
        };
        check();
        const auto user = bytes(env, promptBytes);
        const auto system = bytes(env, systemBytes);
        std::vector<llama_chat_message> messages;
        // Preserve roles so instruction models receive the system prompt in their native template.
        // llama.cpp merges system text into the user turn for templates such as Gemma.
        if (!system.empty()) messages.push_back({"system", system.c_str()});
        messages.push_back({"user", user.c_str()});
        const auto *chatTemplate = llama_model_chat_template(model.get(), nullptr);
        if (!chatTemplate) throw std::runtime_error("Model has no chat template; import an instruction model");
        std::vector<char> formatted;
        int size;
        char architecture[64] = {};
        llama_model_meta_val_str(model.get(), "general.architecture", architecture, sizeof(architecture));
        if (std::string(architecture) == "gemma4" && std::string(chatTemplate).find("<|turn>") != std::string::npos) {
            // llama_chat_apply_template's legacy formatter does not implement
            // Gemma 4. Use Google's documented text-only, thinking-disabled
            // format. Keep this scoped to Gemma 4; never guess another model's
            // template. BOS is added once by llama_tokenize below.
            // https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4
            std::string text;
            if (!system.empty()) text += "<|turn>system\n" + system + "<turn|>\n";
            text += "<|turn>user\n" + user + "<turn|>\n<|turn>model\n";
            formatted.assign(text.begin(), text.end());
            size = static_cast<int>(formatted.size());
        } else {
            size = llama_chat_apply_template(chatTemplate, messages.data(), messages.size(), true, nullptr, 0);
            if (size <= 0) throw std::runtime_error("Unsupported chat template");
            formatted.resize(size + 1);
            size = llama_chat_apply_template(chatTemplate, messages.data(), messages.size(), true, formatted.data(), formatted.size());
            if (size <= 0 || size > static_cast<int>(formatted.size())) throw std::runtime_error("Cannot format prompt");
        }
        const auto *vocab = llama_model_get_vocab(model.get());
        int count = -llama_tokenize(vocab, formatted.data(), size, nullptr, 0, true, true);
        if (count <= 0 || count + maxTokens > contextSize) throw std::runtime_error("Prompt exceeds context; shorten it or reduce output tokens");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, formatted.data(), size, tokens.data(), count, true, true) != count)
            throw std::runtime_error("Tokenization failed");
        auto params = llama_context_default_params();
        params.n_ctx = contextSize; params.n_batch = 128; params.n_ubatch = 128;
        params.n_threads = threads; params.n_threads_batch = threads;
        params.no_perf = false;
        using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
        Context context(llama_init_from_model(model.get(), params), llama_free);
        if (!context) throw std::runtime_error("Not enough memory for inference context");
        auto resources = [&] {
            check();
            int current = env->CallIntMethod(observer, resourceThreads);
            if (current <= 0) throw std::runtime_error("Inference stopped: critical thermal or memory pressure. Let the device cool or free memory.");
            llama_set_n_threads(context.get(), current, current);
        };
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
            resources();
            auto batch = llama_batch_get_one(tokens.data() + i, std::min(128, count - i));
            if (llama_decode(context.get(), batch)) throw std::runtime_error("Prompt evaluation failed");
        }
        std::string output;
        for (int i = 0; i < maxTokens; ++i) {
            resources();
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
        const auto perf = llama_perf_context(context.get());
        env->CallVoidMethod(observer, onPerformance, perf.t_p_eval_ms, perf.t_eval_ms, perf.n_p_eval, perf.n_eval);
        check();
        return array(env, output.substr(0, utf8Prefix(output)));
    } catch (const std::exception &e) { fail(env, e); return nullptr; }
}
