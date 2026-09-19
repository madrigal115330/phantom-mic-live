# phantom-mic-live

Offline `whisper.cpp` speech-to-text bridge for B4A. It receives external
16-bit mono PCM buffers and never opens the Android microphone.

The GitHub Actions workflow builds these downloadable files:

- `WhisperStt.jar`
- `arm64-v8a/libwhisper.so`
- `WhisperStt.aar`

The wrapper accepts PCM from B4A with the sample rate passed as a parameter.
Both the current 32 kHz stream and a future 48 kHz stream are resampled
internally to the 16 kHz input required by Whisper. Pass `auto` as the language
to recognize Arabic (including Lebanese speech), English, and other supported
languages.

This replaces only the STT component. It does not change the ESP transport or
the AI/TTS return path.

The selected model, such as `ggml-large-v3-turbo-q5_0.bin`, stays outside the
JAR and is loaded from a normal Android filesystem path.
