# Building the Android APK with Nix (Linux/NixOS)

This flake provides a **declarative, encapsulated** development environment for building the RustDesk Android APK on Linux/NixOS. It does not install anything system-wide; everything is provided by the flake and the project-local vcpkg bootstrap.

## Prerequisites

- **Nix** with flakes enabled (`experimental-features = nix-command flakes` in `nix.conf` or `~/.config/nix/nix.conf`).
- **Linux** (x86_64 or aarch64). The Android build is not supported on macOS/Windows by this flake.

## Quick start

1. **Enter the dev shell** (from the repo root):

   ```bash
   nix develop
   ```

   On first run you may need to accept the Android SDK license:

   ```bash
   NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1 nix develop
   ```

2. **One-time vcpkg bootstrap**  
   The shell hook copies the pinned vcpkg source into `.vcpkg-root` and runs `bootstrap-vcpkg.sh` if `VCPKG_ROOT/vcpkg` is not present. So the first time you run `nix develop`, vcpkg will be bootstrapped there (project-local, no system side effects). You can add `.vcpkg-root` to `.gitignore`.

3. **Install cargo-ndk** (once per shell / globally with the flake’s Rust):

   ```bash
   cargo install cargo-ndk --version 3.1.2 --locked
   ```

4. **Install vcpkg dependencies for Android** (e.g. arm64):

   ```bash
   ./flutter/build_android_deps.sh arm64-v8a
   ```

   The script expects `VCPKG_ROOT` and `ANDROID_NDK_HOME`; both are set by the flake.

5. **Build the native library** (example for arm64):

   ```bash
   ./flutter/ndk_arm64.sh
   ```

   Then copy the library and `libc++_shared.so` into the Flutter jniLibs (see `android.sh` or CI for the exact copy steps).

6. **Build the APK**:

   ```bash
   cd flutter
   flutter build apk --release --target-platform android-arm64 --split-per-abi
   ```

For a single script that orchestrates these steps, use **`./android.sh`** (with the flake shell already active). The flake only provides the environment; the build steps are the same as in `android.sh` and CI.

## What the flake provides

- **Rust** 1.75 with targets: `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `i686-linux-android`
- **Flutter** (from nixpkgs; CI uses 3.24.5)
- **Android SDK & NDK** via `androidenv.composeAndroidPackages` (no Android Studio)
- **OpenJDK 17**
- **vcpkg** source at the CI commit (120deac…); bootstrapped into `.vcpkg-root` on first use
- **Build tools**: clang, cmake, ninja, nasm, pkg-config, python3, git, curl, wget

Environment variables set in the shell: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_NDK_HOME`, `ANDROID_NDK`, `ANDROID_NDK_ROOT`, `JAVA_HOME`, `VCPKG_ROOT` (after bootstrap), `VCPKG_DEFAULT_TRIPLET`.

## Reproducibility

- Run **`nix flake lock`** once to generate `flake.lock`. Commit it so everyone gets the same inputs.
- vcpkg is pinned to the same commit as in `vcpkg.json` (baseline). The only local state is the bootstrapped vcpkg binary and vcpkg installed artifacts under `VCPKG_ROOT` (e.g. `.vcpkg-root`).

## Optional: match CI NDK exactly

CI uses NDK **r27c**. The flake uses `ndkVersion = "latest"` so it works with whatever nixpkgs has. To force a specific version (if available in nixpkgs repo), edit `flake.nix` and set e.g.:

```nix
ndkVersion = "27.0.12077973";  # NDK r27c
```

Then run `nix develop` again.
