{
  description = "RustDesk development environment for building the Android APK (Linux/NixOS)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.05";
    rust-overlay.url = "github:oxalica/rust-overlay";
    # vcpkg at the commit used by CI (vcpkg.json baseline). Pinned by flake.lock.
    vcpkg.url = "github:microsoft/vcpkg?rev=120deac3062162151622ca4860575a33844ba10b";
    vcpkg.flake = false;
  };

  outputs = { self, nixpkgs, rust-overlay, vcpkg }: let
    inherit (nixpkgs) lib;
    forAllSystems = lib.genAttrs [
      "x86_64-linux"
      "aarch64-linux"
    ];
    # Only Linux is supported for Android APK builds (CI uses Ubuntu 24.04).
    supportedSystems = [ "x86_64-linux" "aarch64-linux" ];
  in {
    devShells = forAllSystems (system: let
      pkgs = import nixpkgs {
        inherit system;
        overlays = [ rust-overlay.overlays.default ];
        config = {
          allowUnfree = true;
          # Required for Android SDK/NDK.
          android_sdk.accept_license = true;
        };
      };

      # Versions aligned with ci.sh, android.sh, and .github/workflows/flutter-build.yml
      RUST_VERSION = "1.75";

      rustToolchain = pkgs.rust-bin.stable."${RUST_VERSION}".default.override {
        extensions = [ "rustfmt" ];
        targets = [
          "aarch64-linux-android"
          "armv7-linux-androideabi"
          "x86_64-linux-android"
          "i686-linux-android"
        ];
      };

      # Android SDK + NDK via nixpkgs androidenv (deterministic, no Android Studio).
      # CI uses NDK r27c; nixpkgs "latest" may differ; set ndkVersion to e.g. "27.0.12077973" to match.
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        cmdLineToolsVersion = "latest";
        platformToolsVersion = "latest";
        buildToolsVersions = [ "34.0.0" ];
        platformVersions = [ "34" "21" ];  # API 21 for minSdk, 34 for build
        includeNDK = true;
        ndkVersion = "latest";
        includeEmulator = false;
        includeSystemImages = false;
        useGoogleAPIs = false;
        licenseAccepted = true;
      };

      jdk = pkgs.jdk17;

      # Writable vcpkg root: copy from store and bootstrap on first use (one-time, project-local).
      vcpkgBootstrapScript = ''
        export VCPKG_ROOT="''${VCPKG_ROOT:-$PWD/.vcpkg-root}"
        if [ ! -x "$VCPKG_ROOT/vcpkg" ]; then
          echo "[rustdesk-flake] Bootstrapping vcpkg at $VCPKG_ROOT (one-time)..."
          mkdir -p "$VCPKG_ROOT"
          cp -R "${vcpkg}/." "$VCPKG_ROOT/"
          (cd "$VCPKG_ROOT" && ./bootstrap-vcpkg.sh -disableMetrics)
        fi
      '';
    in {
      default = pkgs.mkShell rec {
        name = "rustdesk-android";

        nativeBuildInputs = with pkgs; [
          rustToolchain
          # Flutter (version in nixpkgs; CI uses 3.24.5)
          flutter
          # Android build
          jdk
          androidComposition.androidsdk
          # C/C++ and vcpkg build deps (from ci.sh / android.sh)
          clang
          cmake
          ninja
          nasm
          pkg-config
          python3
          git
          curl
          wget
        ];

        buildInputs = with pkgs; [
          # Libraries referenced by CI/android.sh (for host tools / vcpkg)
          stdenv.cc.cc.lib
        ];

        # build_android_deps.sh uses ANDROID_NDK for the toolchain path but only checks ANDROID_NDK_HOME.
        # Set both so the script finds the NDK.
        ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = ANDROID_HOME;
        ANDROID_NDK_HOME = "${ANDROID_HOME}/ndk-bundle";
        ANDROID_NDK = ANDROID_NDK_HOME;
        ANDROID_NDK_ROOT = ANDROID_NDK_HOME;

        JAVA_HOME = "${jdk}";
        VCPKG_DEFAULT_TRIPLET = "arm64-android";

        shellHook = ''
          ${vcpkgBootstrapScript}
          export PATH="$JAVA_HOME/bin:$PATH"
          echo "[rustdesk-flake] ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
          echo "[rustdesk-flake] ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
          echo "[rustdesk-flake] VCPKG_ROOT=$VCPKG_ROOT"
          echo "[rustdesk-flake] Rust $(rustc --version); Flutter $(flutter --version 2>/dev/null | head -1 || true)"
        '';
      };
    });
  };
}
