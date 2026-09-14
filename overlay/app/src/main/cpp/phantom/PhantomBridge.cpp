#include <cstdint>
#include <malloc.h>
#include <memory>
#include <assert.h>
#include <cstring>
#include <algorithm>
#include "PhantomBridge.h"
#include "../logging.h"

#define ENCODING_PCM_16BIT          2
#define ENCODING_PCM_24BIT_PACKED   21
#define ENCODING_PCM_32BIT          22
#define ENCODING_PCM_8BIT           3
#define ENCODING_PCM_FLOAT          4

int audioFormatToJava(int audioFormat) {
    switch (audioFormat) {
        case 0x1:
            return ENCODING_PCM_16BIT;
        case 0x2:
            return ENCODING_PCM_8BIT;
        case 0x3u:
            return ENCODING_PCM_32BIT;
        case 0x5u:
            return ENCODING_PCM_FLOAT;
        case 0x6u:
            return ENCODING_PCM_24BIT_PACKED;
        default:
            return ENCODING_PCM_8BIT;
    }
}

void PhantomBridge::update_audio_format(JNIEnv* env, int sampleRate, int audioFormat, int channelMask) {
    jclass j_phantomManagerClass = env->GetObjectClass(j_phantomManager);
    jmethodID method = env->GetMethodID(j_phantomManagerClass, "updateAudioFormat", "(III)V");
    env->CallVoidMethod(j_phantomManager, method, sampleRate, channelMask, audioFormatToJava(audioFormat));
    env->DeleteLocalRef(j_phantomManagerClass);

    mAudioFormat = audioFormat;
}

void PhantomBridge::load(JNIEnv *env) {
    jclass j_phantomManagerClass = env->GetObjectClass(j_phantomManager);
    jmethodID method = env->GetMethodID(j_phantomManagerClass, "load", "()V");
    env->CallVoidMethod(j_phantomManager, method);
    env->DeleteLocalRef(j_phantomManagerClass);
}

PhantomBridge::PhantomBridge(jobject j_phantomManager) : j_phantomManager(j_phantomManager) {}

void PhantomBridge::on_buffer_chunk_loaded(jbyte *buffer, jsize size) {
    std::lock_guard<std::mutex> lock(m_buffer_mutex);

    if (m_buffer == nullptr) {
        m_buffer = (jbyte*) malloc(m_buffer_size);
    }

    while (m_buffer_write_position + size > m_buffer_size) {
        m_buffer_size *= 2;
        m_buffer = (jbyte*) realloc(m_buffer, m_buffer_size);
    }

    if (mAudioFormat == 0x5u) {
        float* dst_float = reinterpret_cast<float*>(m_buffer);
        int16_t* src16 = reinterpret_cast<int16_t*>(buffer);
        size_t n_samples = size / sizeof(int16_t);
        for (size_t i = 0; i < n_samples; ++i) {
            dst_float[i + m_buffer_write_position / sizeof(float)] = src16[i] / 32768.0f;
        }
        m_buffer_write_position += n_samples * sizeof(float);
    }
    else {
        if (mAudioFormat != 0x1) {
            LOGW("Unsupported audio format %d", mAudioFormat);
        }
        memcpy(m_buffer + m_buffer_write_position, buffer, size);
        m_buffer_write_position += size;
    }
}

bool PhantomBridge::overwrite_buffer(char* buffer, int size) {
    if (!m_ai_mode.load(std::memory_order_relaxed)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(m_buffer_mutex);

    int available = m_buffer_write_position - m_buffer_read_position;
    int to_copy = std::max(0, std::min(size, available));

    if (to_copy > 0 && m_buffer != nullptr) {
        memcpy(buffer, m_buffer + m_buffer_read_position, to_copy);
        m_buffer_read_position += to_copy;
    }

    if (to_copy < size) {
        memset(buffer + to_copy, 0, size - to_copy);
    }

    if (m_buffer_loaded && m_buffer_read_position >= m_buffer_write_position) {
        m_playback_finished = true;
    }

    return true;
}

void PhantomBridge::on_load_done() {
    std::lock_guard<std::mutex> lock(m_buffer_mutex);
    m_buffer_loaded = true;
    if (m_buffer_write_position == 0) {
        m_playback_finished = true;
    }
}

void PhantomBridge::set_ai_mode(bool enabled) {
    m_ai_mode.store(enabled, std::memory_order_relaxed);
}

bool PhantomBridge::is_playback_finished() {
    std::lock_guard<std::mutex> lock(m_buffer_mutex);
    return m_playback_finished;
}

void PhantomBridge::reset_audio_buffer_locked() {
    if (m_buffer != nullptr) {
        free(m_buffer);
        m_buffer = nullptr;
    }

    m_buffer_loaded = false;
    m_playback_finished = false;
    m_buffer_size = 16384;
    m_buffer_write_position = 0;
    m_buffer_read_position = 0;
}

void PhantomBridge::reset_audio_buffer() {
    std::lock_guard<std::mutex> lock(m_buffer_mutex);
    reset_audio_buffer_locked();
}

void PhantomBridge::unload(JNIEnv *env) {
    reset_audio_buffer();

    jclass j_phantomManagerClass = env->GetObjectClass(j_phantomManager);
    jmethodID method = env->GetMethodID(j_phantomManagerClass, "unload", "()V");
    env->CallVoidMethod(j_phantomManager, method);
    env->DeleteLocalRef(j_phantomManagerClass);
}
