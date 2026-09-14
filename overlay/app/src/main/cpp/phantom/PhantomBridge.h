//
// Created by amin on 7/23/24.
//

#ifndef PHANTOMMIC_PHANTOMBRIDGE_H
#define PHANTOMMIC_PHANTOMBRIDGE_H

#include <jni.h>
#include <mutex>
#include <atomic>

class PhantomBridge {
public:
    PhantomBridge(jobject j_phantomManager);

    void update_audio_format(JNIEnv* env, int sampleRate, int audioFormat, int channelMask);
    void load(JNIEnv* env);
    void on_buffer_chunk_loaded(jbyte* buffer, jsize size);
    bool overwrite_buffer(char* buffer, int size);
    void on_load_done();
    void unload(JNIEnv *env);

    void set_ai_mode(bool enabled);
    bool is_playback_finished();
    void reset_audio_buffer();

private:
    void reset_audio_buffer_locked();

    jobject j_phantomManager;

    bool m_buffer_loaded = false;
    bool m_playback_finished = false;
    int m_buffer_size = 16384;
    int m_buffer_write_position = 0;
    int m_buffer_read_position = 0;
    jbyte* m_buffer = nullptr;

    int mAudioFormat = 0x1;
    std::mutex m_buffer_mutex;
    std::atomic<bool> m_ai_mode{true};
};

#endif //PHANTOMMIC_PHANTOMBRIDGE_H
