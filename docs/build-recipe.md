# Clean-clone Android build recipe

ToneTunner's application build needs the locally prepared ONNX Runtime Extensions AAR at `native-dependencies/onnxruntime-extensions-android.aar`. That directory is intentionally gitignored, and no AAR is committed or published by this project. A clean clone can create it with the portable preparation tool; GitHub Actions uses the same tool and then shares the AAR between jobs as a CI artifact.

## Prepare native dependencies

Install Python 3, Git, JDK 17, Gradle 8.14.5, Android SDK platform 35, Android build-tools 35.0.0, and Android NDK `27.2.12479018`. The ONNX Runtime Extensions native build also requires the upstream CMake and Python dependencies. Set `ANDROID_HOME` to the SDK root, then from the repository root run:

```sh
python3 tools/native/prepare_extensions.py \
  --sdk "$ANDROID_HOME" \
  --ndk "$ANDROID_HOME/ndk/27.2.12479018" \
  --output native-dependencies/onnxruntime-extensions-android.aar
```

With no `--source`, the tool fetches only the pinned upstream commit and initializes its recursive submodules in a temporary directory. With no `--base-aar`, it downloads Maven Central's Extensions Android `0.13.0` AAR and verifies the pinned SHA-256. Both inputs can be supplied explicitly to avoid those Git and Maven downloads. Upstream CMake may still fetch dependencies unless its dependency sources and caches are prepared; a fully network-isolated build has not been verified:

```sh
python3 tools/native/prepare_extensions.py \
  --source /path/to/onnxruntime-extensions-at-the-pinned-revision \
  --base-aar /path/to/onnxruntime-extensions-android-0.13.0.aar \
  --sdk "$ANDROID_HOME" \
  --ndk "$ANDROID_HOME/ndk/27.2.12479018" \
  --output native-dependencies/onnxruntime-extensions-android.aar
```

The command rejects any source revision or base-AAR checksum that differs from the values in `tools/native/rebuild_extensions.py`. It rebuilds the JNI libraries for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`, preserves the original AAR's Java classes and resources, adds the Extensions MIT license and build provenance, and checks 64-bit ELF alignment before returning success.

## Assemble and check APKs

The Gradle app build checks for the ignored AAR during configuration. If it is missing, the error prints the preparation command and documentation path. With the Android SDK and Gradle available, build an APK with:

```sh
gradle assembleDebug
```

The unsigned release build used for distribution verification is:

```sh
gradle assembleRelease
```

The release variant includes the four ABIs already present in its native dependencies. For an APK, run the pinned Android build-tools `zipalign` check and the project's native alignment checker:

```sh
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk
python3 tools/native/check_alignment.py --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --require-abis armeabi-v7a arm64-v8a x86 x86_64
```

The CI workflow runs JVM verification and the API 28, 34, and 35 Android runtime jobs before it assembles the APK. In its final gated build job, it assembles the requested APK and also the unsigned release variant when the requested artifact is debug, then checks alignment on every produced APK. The signed `release.yml` workflow remains a separate release process; this build check does not call it or publish an artifact as a release.

## F-Droid status

F-Droid readiness is **unverified**. There is no active or submitted metadata in this repository. The disabled example at [`fdroid/metadata-example.yml`](../fdroid/metadata-example.yml) is only a note about the intended review state; it is not a valid release recipe. No release version or tag has been selected for F-Droid.

The portable tool can build from caller-supplied source and base-AAR paths, but a real F-Droid metadata recipe must show how both inputs are delivered in its isolated build environment. The recipe must create the aligned AAR in F-Droid's actual `build` phase before Gradle assembles the app. F-Droid documents that `prebuild` is for preparation and that actual binaries belong in `build`; its metadata must pass `fdroid lint` and an isolated build before it is considered ready. This repository has not completed those checks. The current policy permits some FLOSS binaries from trusted Maven repositories, but it does not certify this project's custom AAR reconstruction or offline source recipe.

References: [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), [Build Metadata Reference](https://f-droid.org/en/docs/Build_Metadata_Reference/), and [Submitting to F-Droid Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/).
