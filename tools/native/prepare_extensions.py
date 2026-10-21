"""Prepare the pinned ONNX Runtime Extensions AAR for an Android build."""
import argparse
from pathlib import Path
import subprocess
import tempfile

from rebuild_extensions import REVISION, SOURCE_URL, build


def checkout_pinned_source(destination):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(["git", "init", str(destination)], check=True)
    subprocess.run(["git", "-C", str(destination), "remote", "add", "origin", SOURCE_URL], check=True)
    subprocess.run(["git", "-C", str(destination), "fetch", "--depth", "1", "origin", REVISION], check=True)
    subprocess.run(["git", "-C", str(destination), "checkout", "--detach", "FETCH_HEAD"], check=True)
    subprocess.run(["git", "-C", str(destination), "submodule", "update", "--init", "--recursive"], check=True)
    return destination


def prepare(source, sdk, ndk, output, base_aar=None):
    sdk = Path(sdk).resolve()
    ndk = Path(ndk).resolve()
    output = Path(output).resolve()
    if source is not None:
        return build(Path(source).resolve(), sdk, ndk, output, base_aar=base_aar)

    with tempfile.TemporaryDirectory(prefix="tonetunner-extensions-") as temporary:
        source_path = checkout_pinned_source(Path(temporary) / "onnxruntime-extensions")
        return build(source_path, sdk, ndk, output, base_aar=base_aar)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path,
                        help="existing checkout of the pinned upstream revision (cloned temporarily if omitted)")
    parser.add_argument("--sdk", type=Path, required=True, help="Android SDK root")
    parser.add_argument("--ndk", type=Path, required=True, help="Android NDK 27.2.12479018 directory")
    parser.add_argument("--base-aar", type=Path,
                        help="local copy of the pinned upstream 0.13.0 Maven AAR for offline preparation")
    parser.add_argument("--output", type=Path, required=True, help="path for the rebuilt Extensions AAR")
    args = parser.parse_args(argv)
    try:
        prepare(args.source, args.sdk, args.ndk, args.output, base_aar=args.base_aar)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
