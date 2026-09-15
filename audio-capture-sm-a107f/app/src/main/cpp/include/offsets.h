//
// Created by user on 03-09-2025.
//
#include <stddef.h>
#ifndef VOIP_OFFSETS_H
#define VOIP_OFFSETS_H

// Samsung Galaxy A10s (SM-A107F), Android 10, build A107FXXU5BTD2.
// libaudioflinger.so build-id: 51db6a0fee71369e36cf45189540388a
// ARM32 functions are Thumb symbols, so the low address bit is intentional.
#define AUDIOFLINGER_SETMODE_OFFSET 0x1BC81
#define RECORDTRACK_GETNEXTBUFFER_OFFSET 0x59375
#define TRACK_GETNEXTBUFFER_OFFSET 0x55511
#define TRACK_STOP_OFFSET 0x55CBD
#define RECORDTRACK_STOP_OFFSET 0x59445

// TrackBase layout recovered from its constructor in this exact library.
constexpr size_t ATTR_OFFSET = 0x30;
constexpr size_t SAMPLERATE_OFFSET = 0x140;
constexpr size_t FORMAT_OFFSET = 0x144;
constexpr size_t CHANNELMASK_OFFSET = 0x148;
constexpr size_t ISOUT_OFFSET = 0x174;
constexpr size_t CREATOR_PID_OFFSET = 0x1B0;


// Symbol names (mangled)

// const char* sym_RecordTrack_getNextBuffer =
//     "_ZN7android12AudioFlinger12RecordThread11RecordTrack13getNextBufferEPNS_19AudioBufferProvider6BufferE";
// const char* sym_Track_getNextBuffer =
//     "_ZN7android12AudioFlinger14PlaybackThread5Track13getNextBufferEPNS_19AudioBufferProvider6BufferE";
// const char* sym_Track_stop =
//     "_ZN7android12AudioFlinger14PlaybackThread5Track4stopEv";
// const char* sym_RecordTrack_stop =
//     "_ZN7android12AudioFlinger12RecordThread11RecordTrack4stopEv";


#endif //VOIP_OFFSETS_H
