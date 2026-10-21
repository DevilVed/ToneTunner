"""Validate the complete four-ABI JNI set and ELF alignment in Extensions AARs."""
import argparse
from pathlib import Path
import zipfile

from rebuild_extensions import verify_extensions_archive


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="rebuilt ONNX Runtime Extensions AAR")
    args = parser.parse_args(argv)
    try:
        checked = verify_extensions_archive(args.archive)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(f"Validated complete four-ABI Extensions JNI set and {checked} aligned 64-bit libraries")


if __name__ == "__main__":
    main()
