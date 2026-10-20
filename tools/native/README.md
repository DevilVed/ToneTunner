# Android native dependencies

All native compilation runs on GitHub Actions. The workflow builds ONNX Runtime Extensions before Android verification; later jobs download the same AAR artifact. The AAR is cached by the build scripts' content and is never committed.

The latest published Extensions Android AAR is 0.13.0. Its native libraries still use 4 KB ELF alignment. This project rebuilds **all four ABIs** from the exact upstream 0.13.0 source revision `9d5638b0b1f28ed2ca9ef589d5ec3ea86f859432`, using Android NDK `28.2.13676358`, API 28, and both shared/module linker flags for maximum/common 16 KB pages. The official Maven AAR's Java classes and resources are preserved, with its SHA-256 verified before replacing its JNI libraries. The result includes the upstream MIT license and build provenance.

ONNX Runtime Android 1.27.0 and WebRTC VAD 2.0.10 provide compatible published native artifacts. Every shipped 64-bit library is checked for aligned ELF LOAD segments; final APKs also receive ZIP payload checks. The API 35 16 KB emulator tests load ONNX/extensions/VAD and execute CPU inference. Merely suppressing lint or realigning the APK ZIP does not repair unaligned ELF libraries.

Sources: [Extensions source](https://github.com/microsoft/onnxruntime-extensions/tree/9d5638b0b1f28ed2ca9ef589d5ec3ea86f859432), [upstream alignment fix](https://github.com/microsoft/onnxruntime-extensions/pull/1007), [unpublished Android artifact issue](https://github.com/microsoft/onnxruntime-extensions/issues/1093), [Android page size guidance](https://developer.android.com/guide/practices/page-sizes), [VAD releases](https://github.com/gkonovalov/android-vad/releases).

To reproduce in a Linux Android build environment, check out the pinned source and run:

```sh
python3 tools/native/rebuild_extensions.py --source /path/to/extensions-source \
  --sdk "$ANDROID_HOME" --ndk "$ANDROID_HOME/ndk/28.2.13676358" \
  --output native-dependencies/onnxruntime-extensions-android.aar
```

The application intentionally has no fallback to the unaligned Maven AAR. Use the Android build workflow for a complete verified APK.
