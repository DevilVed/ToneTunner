import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from check_alignment import validate_elf, verify_archive


def elf(alignment=16384, offset=0, address=0, loads=1):
    data = bytearray(64 + 56 * loads)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, loads)
    for index in range(loads):
        header = 64 + 56 * index
        struct.pack_into("<I", data, header, 1)
        struct.pack_into("<QQ", data, header + 8, offset, address)
        struct.pack_into("<Q", data, header + 48, alignment)
    return data


class AlignmentTest(unittest.TestCase):
    def test_accepts_16kb_and_larger_power_of_two_alignment(self):
        validate_elf(elf())
        validate_elf(elf(alignment=65536, loads=2))

    def test_rejects_a_later_unaligned_load_segment(self):
        data = elf(loads=2)
        struct.pack_into("<Q", data, 64 + 56 + 48, 4096)
        with self.assertRaisesRegex(ValueError, "LOAD alignment"):
            validate_elf(data)

    def test_rejects_incongruent_load_offset(self):
        with self.assertRaisesRegex(ValueError, "incongruent"):
            validate_elf(elf(offset=1))

    def test_rejects_truncated_and_empty_program_headers(self):
        for data in [b"", elf()[:-1], elf(loads=0)]:
            with self.assertRaises(ValueError):
                validate_elf(data)

    def test_checks_every_64bit_library_in_an_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "fixture.aar"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("jni/arm64-v8a/first.so", elf())
                output.writestr("jni/x86_64/second.so", elf(alignment=4096))
            with self.assertRaisesRegex(ValueError, "second.so"):
                verify_archive(archive)

    def test_rejects_misaligned_apk_zip_payload_even_when_elf_is_aligned(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("lib/arm64-v8a/library.so", elf())
            self.assertEqual(1, verify_archive(archive))
            with self.assertRaisesRegex(ValueError, "ZIP aligned"):
                verify_archive(archive, apk=True)

    def test_can_require_all_four_release_abis_while_validating_64bit_elfs(self):
        abis = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "universal.apk"
            with zipfile.ZipFile(archive, "w") as output:
                for abi in abis:
                    payload = elf() if abi in {"arm64-v8a", "x86_64"} else b"32-bit ELF is not parsed here"
                    output.writestr(f"lib/{abi}/library.so", payload)
            self.assertEqual(2, verify_archive(archive, required_abis=abis))

            with self.assertRaisesRegex(ValueError, "x86"):
                verify_archive(archive, required_abis=("armeabi-v7a", "arm64-v8a", "x86_64"))
