"""Rebuild the Android sender from source. Requires Python 3, Android SDK, CMake and NDK."""
import argparse
import os
import shutil
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--mbedtls", type=Path, help="Local checkout of the pinned Mbed TLS including framework")
    parser.add_argument("--abi", choices=["arm64-v8a", "armeabi-v7a", "x86_64"], action="append")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    ndk = args.sdk / "ndk" / "28.2.13676358"
    cmake_tools = args.sdk / "cmake" / "3.22.1" / "bin"
    exe = ".exe" if os.name == "nt" else ""
    cmake = cmake_tools / ("cmake" + exe)
    host = next((ndk / "toolchains" / "llvm" / "prebuilt").iterdir())
    strip = host / "bin" / ("llvm-strip" + exe)
    for abi in args.abi or ["arm64-v8a", "armeabi-v7a", "x86_64"]:
        build = args.build_dir.resolve() / abi
        options = [
            cmake, "-S", source, "-B", build, "-G", "Ninja",
            f"-DCMAKE_MAKE_PROGRAM={cmake_tools / ('ninja' + exe)}",
            f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
            f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-28",
            "-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release",
        ]
        if args.mbedtls:
            options.append(f"-DFETCHCONTENT_SOURCE_DIR_MBEDTLS={args.mbedtls.resolve()}")
        subprocess.run(options, check=True)
        subprocess.run([cmake, "--build", build, "--target", "audilege_airplay", "--parallel", "6"], check=True)
        output = source.parent / "jniLibs" / abi / "libaudilege_airplay.so"
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(build / output.name, output)
        subprocess.run([strip, "--strip-unneeded", output], check=True)


if __name__ == "__main__":
    main()
