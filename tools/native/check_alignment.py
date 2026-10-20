"""Validate every shipped 64-bit ELF LOAD segment and APK native ZIP payload."""
import argparse
import struct
import zipfile
from pathlib import Path

PAGE_SIZE = 16_384


def validate_elf(data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian 64-bit ELF library")
    offset = struct.unpack_from("<Q", data, 32)[0]
    size, count = struct.unpack_from("<HH", data, 54)
    if size < 56 or offset + size * count > len(data):
        raise ValueError("Invalid ELF program-header table")
    loads = 0
    for index in range(count):
        header = offset + index * size
        if struct.unpack_from("<I", data, header)[0] != 1:
            continue
        file_offset, address = struct.unpack_from("<QQ", data, header + 8)
        alignment = struct.unpack_from("<Q", data, header + 48)[0]
        if alignment < PAGE_SIZE or alignment & (alignment - 1):
            raise ValueError(f"LOAD alignment {alignment} is not 16 KB compatible")
        if file_offset % PAGE_SIZE != address % PAGE_SIZE:
            raise ValueError("LOAD file offset and virtual address are incongruent")
        loads += 1
    if not loads:
        raise ValueError("ELF has no LOAD segments")


def verify_archive(path, apk=False):
    checked = 0
    with zipfile.ZipFile(path) as archive, open(path, "rb") as raw:
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if (len(parts) != 3 or parts[0] not in {"lib", "jni"}
                    or parts[1] not in {"arm64-v8a", "x86_64"} or not parts[2].endswith(".so")):
                continue
            try:
                validate_elf(archive.read(entry))
                if apk and entry.compress_type == zipfile.ZIP_STORED:
                    raw.seek(entry.header_offset + 26)
                    name_size, extra_size = struct.unpack("<HH", raw.read(4))
                    if (entry.header_offset + 30 + name_size + extra_size) % PAGE_SIZE:
                        raise ValueError("Uncompressed APK payload is not 16 KB ZIP aligned")
            except ValueError as error:
                raise ValueError(f"{entry.filename}: {error}") from error
            checked += 1
    if not checked:
        raise ValueError("Archive contains no 64-bit native libraries to validate")
    return checked


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--apk", action="store_true")
    args = parser.parse_args()
    count = verify_archive(args.archive, args.apk)
    print(f"Validated {count} 64-bit native libraries: {args.archive.name}")
