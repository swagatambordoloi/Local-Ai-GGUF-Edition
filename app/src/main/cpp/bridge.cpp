#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "GGUF-Engine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model* global_model = nullptr;
static llama_context* global_ctx = nullptr;
static bool is_model_initialized = false;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_localaiggufedition_NativeBridge_initModel(JNIEnv* env, jobject /* this */, jstring modelPath) {
    if (modelPath == nullptr) return env->NewStringUTF("Error: Null path");

    // Initialize the backend (Vulkan, etc.)
    LOGI("Initializing llama backend...");
    llama_backend_init();
    LOGI("Llama backend initialized.");

    const char *nativePath = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading GGUF Model from target path: %s", nativePath);

    auto model_params = llama_model_default_params();
    model_params.vocab_only = false;

    // HARDWARE ACCELERATION: Offload some model computational layers to the GPU via Vulkan backend
    model_params.n_gpu_layers = 10;
    LOGI("Offloading %d layers to GPU", model_params.n_gpu_layers);

    global_model = llama_model_load_from_file(nativePath, model_params);
    LOGI("Model load result: %p", global_model);
    env->ReleaseStringUTFChars(modelPath, nativePath);

    if (global_model == nullptr) {
        return env->NewStringUTF("Error: Failed to load GGUF model file stream.");
    }

    auto ctx_params = llama_context_default_params();
    global_ctx = llama_init_from_model(global_model, ctx_params);

    if (global_ctx == nullptr) {
        llama_model_free(global_model);
        global_model = nullptr;
        return env->NewStringUTF("Error: Failed to create execution context.");
    }

    is_model_initialized = true;
    return env->NewStringUTF("Model loaded successfully");
}

JNIEXPORT jstring JNICALL
Java_com_example_localaiggufedition_NativeBridge_runInference(JNIEnv* env, jobject /* this */, jstring prompt) {
    if (!is_model_initialized || global_model == nullptr) {
        return env->NewStringUTF("Error: Model not initialized.");
    }

    // Refresh context window safely to flush cache between subsequent turns
    if (global_ctx != nullptr) {
        llama_free(global_ctx);
    }
    auto ctx_params = llama_context_default_params();
    global_ctx = llama_init_from_model(global_model, ctx_params);
    if (global_ctx == nullptr) {
        return env->NewStringUTF("Error: Failed to refresh context window.");
    }

    const char *nativePrompt = env->GetStringUTFChars(prompt, nullptr);
    const struct llama_vocab* vocab = llama_model_get_vocab(global_model);

    // Tokenize the structural prompt template input
    std::vector<llama_token> tokens(llama_n_ctx(global_ctx));
    int n_tokens = llama_tokenize(vocab, nativePrompt, strlen(nativePrompt), tokens.data(), tokens.size(), true, true);
    env->ReleaseStringUTFChars(prompt, nativePrompt);

    if (n_tokens < 0) {
        return env->NewStringUTF("Error: Tokenization failed.");
    }

    // Evaluate processing stream batch layout
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i]    = tokens[i];
        batch.pos[i]      = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]   = (i == n_tokens - 1);
    }
    batch.n_tokens = n_tokens;

    if (llama_decode(global_ctx, batch) != 0) {
        llama_batch_free(batch);
        return env->NewStringUTF("Error: Initial prompt decode evaluation failed.");
    }
    llama_batch_free(batch);

    // Text Generation Loop
    std::string response_text = "";
    std::string debug_tokens = "";
    llama_token curr_token = 0;
    int max_tokens_to_generate = 128; // Extracted output token ceiling length
    int n_past = n_tokens;
    int logit_index = n_tokens - 1;

    for (int i = 0; i < max_tokens_to_generate; i++) {
        auto* logits = llama_get_logits_ith(global_ctx, logit_index);
        if (logits == nullptr) {
            break;
        }

        // Greedy sampling selection pass
        curr_token = 0;
        float max_logit = -1e9f;
        int n_vocab = llama_vocab_n_tokens(vocab);
        for (int v = 0; v < n_vocab; v++) {
            if (logits[v] > max_logit) {
                max_logit = logits[v];
                curr_token = v;
            }
        }

        debug_tokens += "[" + std::to_string(curr_token) + "]";

        if (llama_vocab_is_eog(vocab, curr_token)) {
            break;
        }

        char buf[128];
        int n_chars = llama_token_to_piece(vocab, curr_token, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            response_text.append(buf, n_chars);
        }

        // Advance sequence execution context state step parameters
        batch = llama_batch_init(1, 0, 1);
        batch.token[0]    = curr_token;
        batch.pos[0]      = n_past;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]   = true;
        batch.n_tokens    = 1;

        n_past++;

        if (llama_decode(global_ctx, batch) != 0) {
            llama_batch_free(batch);
            break;
        }
        llama_batch_free(batch);

        logit_index = 0;
    }

    if (!response_text.empty() && response_text[0] == ' ') {
        response_text = response_text.substr(1);
    }

    if (response_text.empty()) {
        return env->NewStringUTF(("Debug - Tokens Predicted: " + debug_tokens).c_str());
    }

    return env->NewStringUTF(response_text.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_localaiggufedition_NativeBridge_freeModel(JNIEnv* env, jobject /* this */) {
    LOGI("Clearing engine allocations.");
    if (global_ctx) {
        llama_free(global_ctx);
        global_ctx = nullptr;
    }
    if (global_model) {
        llama_model_free(global_model);
        global_model = nullptr;
    }
    llama_backend_free();
    is_model_initialized = false;
}

} // End of extern "C"