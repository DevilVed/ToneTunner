# Android native dependencies

GitHub Actions prepares and validates the native AAR before Android verification. The build can also be prepared from a clean clone on any machine with Python 3, Git, the Android SDK, Android NDK `27.2.12479018`, and the native build tools required by ONNX Runtime Extensions. This workflow is independent of GitHub credentials and does not publish or sign an APK. The prepared AAR lives under the gitignored `native-dependencies/` directory and is never committed.

Every Android CI build installs the pinned NDK explicitly, even when the aligned AAR cache is warm. Gradle uses that NDK to strip native debug symbols while packaging. The cache stores the AAR, not an NDK installation; a cache hit must not skip toolchain setup. Native unit checks always run. The AAR cache key tracks the native rebuild implementation and NDK version, while test and source-fetch helper changes do not trigger an otherwise unnecessary cold compile.

The upstream ONNX Runtime Extensions Android AAR is version `0.13.0`; its published native libraries use 4 KB ELF alignment. This project rebuilds **all four ABIs** from the exact upstream source revision `9d5638b0b1f28ed2ca9ef589d5ec3ea86f859432`, with NDK `27.2.12479018`, API 28, flexible page-size support, static C++ linkage, and shared/module linker flags for maximum and common 16 KB pages. It verifies the SHA-256 of the pinned Maven AAR before replacing JNI libraries, preserves its Java classes and resources, and embeds the upstream MIT license and build provenance.

NDK 28's LLVM 19 removes the generic `std::char_traits` template used by this source's pinned Dlib dependency. NDK 27 retains that compatibility and explicitly supports flexible 16 KB pages; the finished binary still has to pass the same ELF checks and 16 KB emulator tests. See [LLVM 19 removals](https://releases.llvm.org/19.1.0/projects/libcxx/docs/ReleaseNotes.html#deprecations-and-removals) and [NDK 27 page size support](https://github.com/android/ndk/wiki/Changelog-r27).

ONNX Runtime Android 1.27.0 and WebRTC VAD 2.0.10 provide compatible published native artifacts. `check_alignment.py` validates ELF LOAD segments for 64-bit `arm64-v8a` and `x86_64` libraries. For APKs it also checks ZIP alignment of those 64-bit native payloads and can require all four ABI directories. CI additionally runs Android `zipalign -P 16` on each APK, which checks the packaged native payload alignment across ABIs. The checker does not validate ELF LOAD segments in the 32-bit `armeabi-v7a` or `x86` libraries. The AAR and release APK include all four ABIs, but current 16 KB emulator verification runs on `x86_64`; there is no physical 32-bit device verification. The API 35 emulator loads ONNX, Extensions, and VAD and executes CPU inference. Suppressing lint or realigning the APK ZIP does not repair an ELF library with unaligned LOAD segments.

Sources: [Extensions source](https://github.com/microsoft/onnxruntime-extensions/tree/9d5638b0b1f28ed2ca9ef589d5ec3ea86f859432), [upstream alignment fix](https://github.com/microsoft/onnxruntime-extensions/pull/1007), [unpublished Android artifact issue](https://github.com/microsoft/onnxruntime-extensions/issues/1093), [Android page size guidance](https://developer.android.com/guide/practices/page-sizes), [VAD releases](https://github.com/gkonovalov/android-vad/releases).

To prepare the AAR from a clean checkout, with network access for the pinned source and Maven artifact:

```sh
python3 tools/native/prepare_extensions.py \
  --sdk "$ANDROID_HOME" --ndk "$ANDROID_HOME/ndk/27.2.12479018" \
  --output native-dependencies/onnxruntime-extensions-android.aar
```

To supply the source and base artifact locally, provide an already initialized checkout at the exact pinned revision and a local copy of the published base AAR. The script checks both the source revision and the AAR SHA-256 before building. This avoids its Git and Maven downloads; upstream CMake dependency fetching may still need network access unless its dependency sources and caches are also prepared. A fully network-isolated build has not been verified:

```sh
python3 tools/native/prepare_extensions.py \
  --source /path/to/onnxruntime-extensions \
  --base-aar /path/to/onnxruntime-extensions-android-0.13.0.aar \
  --sdk "$ANDROID_HOME" --ndk "$ANDROID_HOME/ndk/27.2.12479018" \
  --output native-dependencies/onnxruntime-extensions-android.aar
```

If `--source` is omitted, the script fetches the pinned source commit and its recursive submodules into a temporary directory. If `--base-aar` is omitted, it downloads the pinned Maven AAR. The base artifact is the trusted Maven Central coordinate `com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0`; its exact URL and hash are recorded in `rebuild_extensions.py`.

F-Droid status: **not ready for submission or publication**. The clean-clone command above documents the source build, but no F-Droid metadata has passed `fdroid lint` or an isolated F-Droid server build. In a future metadata recipe, source and base-AAR inputs must be made available to the build phase without relying on GitHub Actions or undocumented network access. F-Droid's policy permits some free binaries from trusted Maven repositories, but that alone does not verify this custom native build recipe. See [the metadata example](../../fdroid/metadata-example.yml) and the [full build recipe](../../docs/build-recipe.md).

The Gradle build deliberately fails early with a preparation command when the ignored AAR is missing. It has no fallback to the unaligned Maven AAR. Use the Android build workflow to produce a CI-verified APK; its release variant is assembled unsigned and checked for alignment in the same gated job.
