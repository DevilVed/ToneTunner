"""Cloud-only rebuild of upstream Extensions 0.13.0 JNI libraries for 16 KB pages."""
import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import urllib.request
import zipfile

from check_alignment import verify_archive

REVISION = "9d5638b0b1f28ed2ca9ef589d5ec3ea86f859432"
BASE_SHA256 = "cb98c6fbeac1a9707228c4b76cfbb395b4dc4d310900aefaa672561d17fd6d9d"
BASE_URL = ("https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/"
            "onnxruntime-extensions-android/0.13.0/onnxruntime-extensions-android-0.13.0.aar")
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")


def build(source, sdk, ndk, output):
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if revision != REVISION:
        raise ValueError("Extensions source is not the pinned upstream revision")
    replacements = {}
    linker_flags = "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
    for abi in ABIS:
        build_dir = source / "build" / abi
        subprocess.run([
            "python3", str(source / "tools/build.py"), "--android", f"--android_abi={abi}",
            "--android_api=28", f"--android_home={sdk}", f"--android_ndk_path={ndk}",
            f"--build_dir={build_dir}", "--config=Release", "--update", "--build",
            "--parallel=2", "--skip_tests", "--cmake_extra_defines",
            "OCOS_BUILD_PYTHON=OFF", "OCOS_ENABLE_CTEST=OFF", "OCOS_ENABLE_AUDIO=ON", "OCOS_BUILD_JAVA=ON",
            "ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "ANDROID_STL=c++_static",
            f"CMAKE_SHARED_LINKER_FLAGS={linker_flags}", f"CMAKE_MODULE_LINKER_FLAGS={linker_flags}",
        ], cwd=source, check=True)
        for library in ("libortextensions.so", "libonnxruntime_extensions4j_jni.so"):
            replacements[f"jni/{abi}/{library}"] = (build_dir / "Release/lib" / library).read_bytes()
    with urllib.request.urlopen(BASE_URL, timeout=60) as response:
        published = response.read()
    if hashlib.sha256(published).hexdigest() != BASE_SHA256:
        raise ValueError("Published Extensions AAR checksum does not match the pinned artifact")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(published)) as original, zipfile.ZipFile(output, "w") as rebuilt:
        original_native = {entry.filename for entry in original.infolist()
                           if entry.filename.startswith("jni/") and entry.filename.endswith(".so")}
        if original_native != set(replacements):
            raise ValueError("Upstream JNI package layout differs from the expected rebuild")
        for entry in original.infolist():
            rebuilt.writestr(entry, replacements.get(entry.filename, original.read(entry)))
        rebuilt.writestr("META-INF/LICENSE.onnxruntime-extensions", (source / "LICENSE").read_bytes())
        rebuilt.writestr("META-INF/ToneTunner-native-build.txt",
                         f"Upstream: microsoft/onnxruntime-extensions\nSource: {REVISION}\n"
                         f"Base AAR SHA256: {BASE_SHA256}\nNDK: {ndk.name}\nAPI: 28\n"
                         f"Link flags: {linker_flags}\nJava classes and resources unchanged.\n")
    print(f"Rebuilt Extensions AAR: {verify_archive(output)} validated 64-bit native libraries")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(args.source.resolve(), args.sdk.resolve(), args.ndk.resolve(), args.output.resolve())
