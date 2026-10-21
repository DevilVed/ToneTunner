import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import prepare_extensions
import rebuild_extensions


ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
LIBRARIES = ("libortextensions.so", "libonnxruntime_extensions4j_jni.so")


def elf_64(alignment=16384):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    data[32:40] = (64).to_bytes(8, "little")
    data[54:56] = (56).to_bytes(2, "little")
    data[56:58] = (1).to_bytes(2, "little")
    data[64:68] = (1).to_bytes(4, "little")
    data[112:120] = alignment.to_bytes(8, "little")
    return bytes(data)


def make_base_aar(path):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.jar", b"java classes stay unchanged")
        archive.writestr("META-INF/LICENSE", b"upstream base license")
        for abi in ABIS:
            for library in LIBRARIES:
                archive.writestr(f"jni/{abi}/{library}", b"old native bytes")
    return path.read_bytes()


def write_fake_build_outputs(command, **kwargs):
    build_dir = Path(next(value.split("=", 1)[1] for value in command if value.startswith("--build_dir=")))
    library_dir = build_dir / "Release" / "lib"
    library_dir.mkdir(parents=True, exist_ok=True)
    for library in LIBRARIES:
        (library_dir / library).write_bytes(elf_64())


class RebuildExtensionsTest(unittest.TestCase):
    def test_offline_base_aar_skips_network_and_preserves_java_and_license_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "LICENSE").write_bytes(b"upstream source MIT license")
            base_aar = root / "base.aar"
            base_bytes = make_base_aar(base_aar)
            expected_hash = hashlib.sha256(base_bytes).hexdigest()
            output = root / "native-dependencies" / "extensions.aar"
            sdk = root / "sdk"
            ndk = sdk / "ndk" / "27.2.12479018"

            with patch.object(rebuild_extensions, "BASE_SHA256", expected_hash), \
                    patch.object(rebuild_extensions.subprocess, "check_output", side_effect=[
                        rebuild_extensions.REVISION, "", "", "",
                    ]), \
                    patch.object(rebuild_extensions.subprocess, "run", side_effect=write_fake_build_outputs) as native_build, \
                    patch.object(rebuild_extensions.urllib.request, "urlopen") as download:
                rebuild_extensions.build(source, sdk, ndk, output, base_aar=base_aar)

            download.assert_not_called()
            self.assertEqual(4, native_build.call_count)
            built_commands = [call.args[0] for call in native_build.call_args_list]
            self.assertEqual(set(ABIS), {
                next(value.split("=", 1)[1] for value in command if value.startswith("--android_abi="))
                for command in built_commands
            })
            for command in built_commands:
                self.assertIn("--android_api=28", command)
                self.assertIn("ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", command)
                self.assertIn("ANDROID_STL=c++_static", command)
                self.assertTrue(any(
                    argument.endswith("-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
                    for argument in command
                ))

            with zipfile.ZipFile(output) as archive:
                self.assertEqual(b"java classes stay unchanged", archive.read("classes.jar"))
                self.assertEqual(b"upstream base license", archive.read("META-INF/LICENSE"))
                self.assertEqual(b"upstream source MIT license",
                                 archive.read("META-INF/LICENSE.onnxruntime-extensions"))
                provenance = archive.read("META-INF/ToneTunner-native-build.txt").decode()
                self.assertIn(f"Source: {rebuild_extensions.REVISION}", provenance)
                self.assertIn(f"Base AAR SHA256: {expected_hash}", provenance)
                self.assertIn("NDK: 27.2.12479018", provenance)
                for abi in ABIS:
                    for library in LIBRARIES:
                        self.assertEqual(elf_64(), archive.read(f"jni/{abi}/{library}"))

    def test_offline_base_aar_checksum_mismatch_fails_before_native_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            base_aar = root / "base.aar"
            make_base_aar(base_aar)
            with patch.object(rebuild_extensions, "BASE_SHA256", "0" * 64), \
                    patch.object(rebuild_extensions.subprocess, "check_output", side_effect=[
                        rebuild_extensions.REVISION, "", "", "",
                    ]), \
                    patch.object(rebuild_extensions.subprocess, "run") as native_build, \
                    patch.object(rebuild_extensions.urllib.request, "urlopen") as download:
                with self.assertRaisesRegex(ValueError, "checksum"):
                    rebuild_extensions.build(source, root / "sdk", root / "ndk", root / "out.aar",
                                             base_aar=base_aar)
            native_build.assert_not_called()
            download.assert_not_called()

    def test_wrong_upstream_revision_fails_before_native_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            with patch.object(rebuild_extensions.subprocess, "check_output", return_value="wrong-revision"), \
                    patch.object(rebuild_extensions.subprocess, "run") as native_build:
                with self.assertRaisesRegex(ValueError, "pinned upstream revision"):
                    rebuild_extensions.build(source, root / "sdk", root / "ndk", root / "out.aar",
                                             base_aar=root / "unused.aar")
            native_build.assert_not_called()

    def test_dirty_source_checkout_fails_before_native_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            with patch.object(rebuild_extensions.subprocess, "check_output", side_effect=[
                    rebuild_extensions.REVISION, " M tools/build.py\n"]), \
                    patch.object(rebuild_extensions.subprocess, "run") as native_build:
                with self.assertRaisesRegex(ValueError, "modified or untracked"):
                    rebuild_extensions.build(source, root / "sdk", root / "ndk", root / "out.aar",
                                             base_aar=root / "unused.aar")
            native_build.assert_not_called()

    def test_recursive_submodule_mismatch_fails_before_native_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            with patch.object(rebuild_extensions.subprocess, "check_output", side_effect=[
                    rebuild_extensions.REVISION, "", f"+{'a' * 40} third_party/dependency\n"]), \
                    patch.object(rebuild_extensions.subprocess, "run") as native_build:
                with self.assertRaisesRegex(ValueError, "recursive submodule"):
                    rebuild_extensions.build(source, root / "sdk", root / "ndk", root / "out.aar",
                                             base_aar=root / "unused.aar")
            native_build.assert_not_called()

    def test_dirty_recursive_submodule_fails_before_native_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            with patch.object(rebuild_extensions.subprocess, "check_output", side_effect=[
                    rebuild_extensions.REVISION, "", f" {'a' * 40} third_party/dependency\n",
                    " M nested-source.cpp\n"]), \
                    patch.object(rebuild_extensions.subprocess, "run") as native_build:
                with self.assertRaisesRegex(ValueError, "modified or untracked files in a recursive submodule"):
                    rebuild_extensions.build(source, root / "sdk", root / "ndk", root / "out.aar",
                                             base_aar=root / "unused.aar")
            native_build.assert_not_called()

    def test_extensions_aar_gate_requires_both_libraries_for_every_abi(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / "incomplete.aar"
            missing = "jni/armeabi-v7a/libortextensions.so"
            with zipfile.ZipFile(archive_path, "w") as archive:
                for abi in ABIS:
                    for library in LIBRARIES:
                        name = f"jni/{abi}/{library}"
                        if name != missing:
                            archive.writestr(name, elf_64())
            with self.assertRaisesRegex(ValueError, "armeabi-v7a/libortextensions.so"):
                rebuild_extensions.verify_extensions_archive(archive_path)

    def test_missing_source_fetches_the_pinned_commit_and_recursive_submodules(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "extensions.aar"
            with patch.object(prepare_extensions.subprocess, "run") as git, \
                    patch.object(prepare_extensions, "build", return_value=4) as build:
                result = prepare_extensions.prepare(
                    source=None,
                    sdk=root / "sdk",
                    ndk=root / "ndk",
                    output=output,
                )

            self.assertEqual(4, result)
            commands = [call.args[0] for call in git.call_args_list]
            self.assertEqual(5, len(commands))
            self.assertIn(rebuild_extensions.SOURCE_URL, commands[1])
            self.assertEqual(["--depth", "1", "origin", rebuild_extensions.REVISION], commands[2][-4:])
            self.assertEqual(["submodule", "update", "--init", "--recursive"], commands[4][-4:])
            build.assert_called_once()

    def test_supplied_source_and_base_aar_keep_preparation_offline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            base_aar = root / "base.aar"
            with patch.object(prepare_extensions.subprocess, "run") as git, \
                    patch.object(prepare_extensions, "build", return_value=4) as build:
                result = prepare_extensions.prepare(
                    source=source,
                    sdk=root / "sdk",
                    ndk=root / "ndk",
                    output=root / "extensions.aar",
                    base_aar=base_aar,
                )

            self.assertEqual(4, result)
            git.assert_not_called()
            self.assertEqual(source.resolve(), build.call_args.args[0])
            self.assertEqual(base_aar, build.call_args.kwargs["base_aar"])


if __name__ == "__main__":
    unittest.main()
