# Samsung SM-A107F build

This source variant targets the following exact system image:

- Device: Samsung Galaxy A10s (SM-A107F)
- Android: 10 / API 29
- Build: A107FXXU5BTD2
- ABI: armeabi-v7a
- libaudioflinger build ID: 51db6a0fee71369e36cf45189540388a
- libaudioflinger SHA-256: 74dfe5584d26a75728764e67c3b9dc239ae50e381d3de0e1f0ce062fdcd5d52a

Do not install this build on a different firmware fingerprint. The native hook
addresses and TrackBase layout are firmware-specific.

## Build with GitHub Actions (no Android Studio)

1. Push this `Android_App` directory as the root of a GitHub repository.
2. Open the repository's **Actions** tab.
3. Select **Build SM-A107F APK**, then choose **Run workflow**.
4. After the job succeeds, download the `SM-A107F-debug-apk` artifact.

The workflow installs the pinned Android SDK/NDK/CMake toolchain and builds only
the phone's `armeabi-v7a` ABI.

## Build with Android Studio

1. Open this `Android_App` directory in Android Studio.
2. Let Android Studio install Android SDK 34, CMake 3.22.1, and NDK 26.1.10909125.
3. Select `Build > Generate App Bundles or APKs > Generate APKs`.
4. Use the generated APK under `app/build/outputs/apk/debug/` for initial testing.

The hook uses the TrackBase creator PID to identify `com.whatsapp` and
`com.whatsapp.w4b`, including their colon-suffixed child processes.

Test only on the owned test phone. A bad native hook can restart or crash the
Android audioserver. Keep SELinux enforcing unless the injected rules fail, and
restore enforcing mode immediately after diagnosis.
