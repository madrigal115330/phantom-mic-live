#include <jni.h>
#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

#include "whisper.h"

namespace {
constexpr int kWhisperSampleRate = 16000;

void throw_java(JNIEnv *env, const char *class_name, const std::string &message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) env->ThrowNew(exception_class, message.c_str());
}

std::vector<float> convert_and_resample(const jshort *input, int input_count, int input_rate) {
    if (input_count <= 0 || input_rate <= 0) return {};

    if (input_rate == kWhisperSampleRate) {
        std::vector<float> output(input_count);
        for (int i = 0; i < input_count; ++i) output[i] = static_cast<float>(input[i]) / 32768.0f;
        return output;
    }

    // 32/48 kHz -> 16 kHz uses an inexpensive averaging filter to reduce aliasing.
    if (input_rate > kWhisperSampleRate && input_rate % kWhisperSampleRate == 0) {
        const int factor = input_rate / kWhisperSampleRate;
        const int output_count = input_count / factor;
        std::vector<float> output(output_count);
        for (int i = 0; i < output_count; ++i) {
            int sum = 0;
            for (int j = 0; j < factor; ++j) sum += input[i * factor + j];
            output[i] = static_cast<float>(sum) / (32768.0f * factor);
        }
        return output;
    }

    const int output_count = static_cast<int>(
            std::floor(static_cast<double>(input_count) * kWhisperSampleRate / input_rate));
    std::vector<float> output(std::max(0, output_count));
    const double step = static_cast<double>(input_rate) / kWhisperSampleRate;
    for (int i = 0; i < output_count; ++i) {
        const double position = i * step;
        const int left = static_cast<int>(position);
        const int right = std::min(left + 1, input_count - 1);
        const float fraction = static_cast<float>(position - left);
        output[i] = (input[left] + (input[right] - input[left]) * fraction) / 32768.0f;
    }
    return output;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_phantommic_whisper_WhisperStt_nativeInit(JNIEnv *env, jclass, jstring model_path) {
    if (model_path == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "modelPath is null");
        return 0;
    }
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = false;
    whisper_context *context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    if (context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Failed to load Whisper model");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_phantommic_whisper_WhisperStt_nativeTranscribe(
        JNIEnv *env, jclass, jlong context_ptr, jshortArray pcm, jint sample_rate,
        jstring language, jint threads) {
    auto *context = reinterpret_cast<whisper_context *>(context_ptr);
    if (context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Whisper context is null");
        return nullptr;
    }
    if (pcm == nullptr || sample_rate <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid PCM or sample rate");
        return nullptr;
    }

    const jsize input_count = env->GetArrayLength(pcm);
    jshort *input = env->GetShortArrayElements(pcm, nullptr);
    std::vector<float> audio = convert_and_resample(input, input_count, sample_rate);
    env->ReleaseShortArrayElements(pcm, input, JNI_ABORT);
    if (audio.empty()) return env->NewStringUTF("");

    const char *language_chars = language == nullptr ? nullptr : env->GetStringUTFChars(language, nullptr);
    const bool auto_language = language_chars == nullptr || language_chars[0] == '\0' ||
                               std::string(language_chars) == "auto";

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, static_cast<int>(threads));
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.language = auto_language ? nullptr : language_chars;

    const int result = whisper_full(context, params, audio.data(), static_cast<int>(audio.size()));
    if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
    if (result != 0) {
        throw_java(env, "java/lang/IllegalStateException", "Whisper transcription failed");
        return nullptr;
    }

    std::string text;
    const int segment_count = whisper_full_n_segments(context);
    for (int i = 0; i < segment_count; ++i) {
        const char *segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) text += segment;
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_phantommic_whisper_WhisperStt_nativeFree(JNIEnv *, jclass, jlong context_ptr) {
    auto *context = reinterpret_cast<whisper_context *>(context_ptr);
    if (context != nullptr) whisper_free(context);
}
