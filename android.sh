#!/bin/bash
#
# RustDesk Android Build Script
# Based on .github/workflows/flutter-build.yml (build-rustdesk-android job)
#
# This script builds RustDesk Android APK for arm64 (aarch64) - the most common
# modern Android architecture. Can also build for armv7 or x86_64.
#
# Run this from the ~/rustdesk directory on Ubuntu Linux.
#
# Prerequisites:
#   - Ubuntu 24.04 (or compatible Linux)
#   - Rust toolchain with Android targets
#   - Android SDK & NDK (r27c recommended)
#   - Flutter SDK
#   - vcpkg with VCPKG_ROOT set
#   - OpenJDK 17
#

set -e

# ============================================================================
# Configuration (from CI workflow)
# ============================================================================

RUST_VERSION="1.75"
FLUTTER_VERSION="3.24.5"
FLUTTER_RUST_BRIDGE_VERSION="1.80.1"
CARGO_EXPAND_VERSION="1.0.95"
CARGO_NDK_VERSION="3.1.2"
NDK_VERSION="r27c"
API_LEVEL="21"

# Default to arm64 (most common modern Android architecture)
ANDROID_ARCH="${ANDROID_ARCH:-aarch64}"

# ============================================================================
# Architecture Configuration
# ============================================================================

configure_arch() {
  case "$ANDROID_ARCH" in
  aarch64 | arm64)
    RUST_TARGET="aarch64-linux-android"
    ANDROID_ABI="arm64-v8a"
    VCPKG_TARGET="arm64-android"
    FLUTTER_PLATFORM="android-arm64"
    NDK_ARCH="aarch64-linux-android"
    ;;
  armv7 | arm)
    RUST_TARGET="armv7-linux-androideabi"
    ANDROID_ABI="armeabi-v7a"
    VCPKG_TARGET="arm-neon-android"
    FLUTTER_PLATFORM="android-arm"
    NDK_ARCH="arm-linux-androideabi"
    ;;
  x86_64 | x64)
    RUST_TARGET="x86_64-linux-android"
    ANDROID_ABI="x86_64"
    VCPKG_TARGET="x64-android"
    FLUTTER_PLATFORM="android-x64"
    NDK_ARCH="x86_64-linux-android"
    ;;
  x86)
    RUST_TARGET="i686-linux-android"
    ANDROID_ABI="x86"
    VCPKG_TARGET="x86-android"
    FLUTTER_PLATFORM="android-x86"
    NDK_ARCH="i686-linux-android"
    ;;
  *)
    error "Unknown architecture: $ANDROID_ARCH"
    error "Supported: aarch64, armv7, x86_64, x86"
    ;;
  esac

  info "Target architecture: $ANDROID_ARCH"
  info "  Rust target: $RUST_TARGET"
  info "  Android ABI: $ANDROID_ABI"
  info "  vcpkg triplet: $VCPKG_TARGET"
}

# ============================================================================
# Helper Functions
# ============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() {
  echo -e "${RED}[ERROR]${NC} $1"
  exit 1
}
step() { echo -e "\n${BLUE}==== $1 ====${NC}\n"; }

check_command() {
  if ! command -v "$1" &>/dev/null; then
    error "$1 is not installed. Please install it first."
  fi
}

# ============================================================================
# Pre-flight Checks
# ============================================================================

preflight_checks() {
  step "Pre-flight Checks"

  # Check we're in rustdesk directory
  if [[ ! -f "Cargo.toml" ]] || [[ ! -f "vcpkg.json" ]]; then
    error "This script must be run from the rustdesk directory"
  fi
  info "Running from: $(pwd)"

  # Check required tools
  check_command rustc
  check_command cargo
  check_command flutter
  check_command python3
  check_command cmake
  check_command java

  # Check Java version
  JAVA_VERSION=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
  if [[ "$JAVA_VERSION" -lt 17 ]]; then
    warn "Java version $JAVA_VERSION detected. Java 17+ is recommended."
  else
    info "Java version: $JAVA_VERSION"
  fi

  # Check JAVA_HOME
  if [[ -z "$JAVA_HOME" ]]; then
    # Try to find Java 17
    if [[ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]]; then
      export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
      info "Set JAVA_HOME=$JAVA_HOME"
    else
      warn "JAVA_HOME not set. Build may fail."
    fi
  fi

  # Check ANDROID_SDK_ROOT / ANDROID_HOME
  if [[ -z "$ANDROID_SDK_ROOT" ]] && [[ -z "$ANDROID_HOME" ]]; then
    # Try common locations
    for sdk_path in "$HOME/Android/Sdk" "/usr/local/lib/android/sdk" "$HOME/android-sdk"; do
      if [[ -d "$sdk_path" ]]; then
        export ANDROID_SDK_ROOT="$sdk_path"
        export ANDROID_HOME="$sdk_path"
        info "Found Android SDK: $sdk_path"
        break
      fi
    done

    if [[ -z "$ANDROID_SDK_ROOT" ]]; then
      error "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME"
    fi
  fi

  # Check ANDROID_NDK_HOME
  if [[ -z "$ANDROID_NDK_HOME" ]]; then
    # Try to find NDK
    for ndk_path in "$ANDROID_SDK_ROOT/ndk/$NDK_VERSION" "$ANDROID_SDK_ROOT/ndk-bundle" "$HOME/android-ndk-$NDK_VERSION"; do
      if [[ -d "$ndk_path" ]]; then
        export ANDROID_NDK_HOME="$ndk_path"
        export ANDROID_NDK_ROOT="$ndk_path"
        info "Found Android NDK: $ndk_path"
        break
      fi
    done

    # Also check for any NDK version
    if [[ -z "$ANDROID_NDK_HOME" ]] && [[ -d "$ANDROID_SDK_ROOT/ndk" ]]; then
      LATEST_NDK=$(ls -1 "$ANDROID_SDK_ROOT/ndk" | sort -V | tail -1)
      if [[ -n "$LATEST_NDK" ]]; then
        export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$LATEST_NDK"
        export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
        info "Found Android NDK: $ANDROID_NDK_HOME"
      fi
    fi

    if [[ -z "$ANDROID_NDK_HOME" ]]; then
      error "Android NDK not found. Set ANDROID_NDK_HOME"
    fi
  fi

  # Verify NDK toolchain exists
  NDK_TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
  if [[ ! -d "$NDK_TOOLCHAIN" ]]; then
    error "NDK toolchain not found at: $NDK_TOOLCHAIN"
  fi

  # Check VCPKG_ROOT
  if [[ -z "$VCPKG_ROOT" ]]; then
    if [[ -d "$HOME/vcpkg" ]]; then
      export VCPKG_ROOT="$HOME/vcpkg"
      warn "VCPKG_ROOT not set, using $VCPKG_ROOT"
    else
      error "VCPKG_ROOT is not set"
    fi
  fi

  if [[ ! -x "$VCPKG_ROOT/vcpkg" ]]; then
    error "vcpkg not found at $VCPKG_ROOT"
  fi

  # Get version from Cargo.toml
  VERSION=$(grep -m1 '^version' Cargo.toml | sed 's/version = "\(.*\)"/\1/')
  info "Building RustDesk version: $VERSION"

  # Show environment
  info "Rust version: $(rustc --version)"
  info "Flutter version: $(flutter --version | head -1)"
  info "VCPKG_ROOT: $VCPKG_ROOT"
  info "ANDROID_SDK_ROOT: $ANDROID_SDK_ROOT"
  info "ANDROID_NDK_HOME: $ANDROID_NDK_HOME"

  cd flutter
  flutter clean
  cd ../
}

# ============================================================================
# Install Dependencies
# ============================================================================

install_dependencies() {
  step "Installing Build Dependencies"

  sudo apt-get update
  sudo apt-get install -y \
    clang \
    cmake \
    curl \
    gcc-multilib \
    git \
    g++ \
    g++-multilib \
    libayatana-appindicator3-dev \
    libasound2-dev \
    libc6-dev \
    libclang-dev \
    libunwind-dev \
    libgstreamer1.0-dev \
    libgstreamer-plugins-base1.0-dev \
    libgtk-3-dev \
    libpam0g-dev \
    libpulse-dev \
    libva-dev \
    libxcb-randr0-dev \
    libxcb-shape0-dev \
    libxcb-xfixes0-dev \
    libxdo-dev \
    libxfixes-dev \
    llvm-dev \
    nasm \
    ninja-build \
    openjdk-17-jdk-headless \
    pkg-config \
    tree \
    wget

  # Set JAVA_HOME for OpenJDK 17
  export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
  export PATH="$JAVA_HOME/bin:$PATH"

  info "Dependencies installed"
}

# ============================================================================
# Generate Flutter-Rust Bridge
# ============================================================================

generate_bridge() {
  step "Generating Flutter-Rust Bridge"

  # Check if bridge files exist
  if [[ -f "src/bridge_generated.rs" ]] && [[ -f "flutter/lib/generated_bridge.dart" ]]; then
    info "Bridge files already exist"
    read -p "Regenerate bridge? [y/N] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
      info "Skipping bridge generation"
      return
    fi
  fi

  # Install flutter_rust_bridge_codegen if not present
  if ! command -v flutter_rust_bridge_codegen &>/dev/null; then
    info "Installing flutter_rust_bridge_codegen..."
    cargo install flutter_rust_bridge_codegen \
      --version "$FLUTTER_RUST_BRIDGE_VERSION" \
      --features "uuid" \
      --locked
  fi

  # Install cargo-expand if not present
  if ! cargo expand --version &>/dev/null 2>&1; then
    info "Installing cargo-expand..."
    cargo install cargo-expand --version "$CARGO_EXPAND_VERSION" --locked
  fi

  # Run flutter pub get
  info "Running flutter pub get..."
  pushd flutter
  flutter pub get
  popd

  # Generate bridge
  info "Running flutter_rust_bridge_codegen..."
  flutter_rust_bridge_codegen \
    --rust-input ./src/flutter_ffi.rs \
    --dart-output ./flutter/lib/generated_bridge.dart \
    --c-output ./flutter/macos/Runner/bridge_generated.h

  cp ./flutter/macos/Runner/bridge_generated.h ./flutter/ios/Runner/bridge_generated.h 2>/dev/null || true

  info "Bridge generation complete"
}

# ============================================================================
# Install vcpkg Dependencies
# ============================================================================

install_vcpkg_deps() {
  step "Installing vcpkg Dependencies for Android ($VCPKG_TARGET)"

  info "Running flutter/build_android_deps.sh..."

  if ! ./flutter/build_android_deps.sh "$ANDROID_ABI"; then
    warn "vcpkg install may have failed. Showing recent logs:"
    find "$VCPKG_ROOT/buildtrees" -name "*.log" -mmin -5 2>/dev/null | head -5 | while read -r log; do
      echo "=== $log ==="
      tail -50 "$log"
    done
    error "vcpkg install failed"
  fi

  info "vcpkg dependencies installed for $ANDROID_ABI"
}

# ============================================================================
# Setup Rust for Android
# ============================================================================

setup_rust_android() {
  step "Setting up Rust for Android"

  # Add Android target
  info "Adding Rust target: $RUST_TARGET"
  rustup target add "$RUST_TARGET"

  # Install cargo-ndk
  if ! command -v cargo-ndk &>/dev/null; then
    info "Installing cargo-ndk version $CARGO_NDK_VERSION..."
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
  else
    info "cargo-ndk already installed"
  fi

  info "Rust Android setup complete"
}

# ============================================================================
# Build Native Library
# ============================================================================

build_native_lib() {
  step "Building Native Library for Android ($ANDROID_ARCH)"

  export ANDROID_NDK_HOME="$ANDROID_NDK_HOME"
  export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"

  info "Using NDK: $ANDROID_NDK_HOME"
  info "Building for target: $RUST_TARGET"

  # Use the appropriate ndk script
  case "$ANDROID_ARCH" in
  aarch64 | arm64)
    ./flutter/ndk_arm64.sh
    ;;
  armv7 | arm)
    ./flutter/ndk_arm.sh
    ;;
  x86_64 | x64)
    ./flutter/ndk_x64.sh
    ;;
  x86)
    ./flutter/ndk_x86.sh
    ;;
  esac

  if [[ $? -ne 0 ]]; then
    error "Native library build failed"
  fi

  # Copy library to Flutter jniLibs
  info "Copying native library to Flutter..."
  JNILIBS_DIR="flutter/android/app/src/main/jniLibs/$ANDROID_ABI"
  mkdir -p "$JNILIBS_DIR"
  cp "./target/$RUST_TARGET/release/liblibrustdesk.so" "$JNILIBS_DIR/librustdesk.so"

  # Copy libc++_shared.so from NDK
  NDK_SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$NDK_ARCH"
  if [[ -f "$NDK_SYSROOT/libc++_shared.so" ]]; then
    cp "$NDK_SYSROOT/libc++_shared.so" "$JNILIBS_DIR/"
    info "Copied libc++_shared.so"
  else
    warn "libc++_shared.so not found at expected path"
  fi

  info "Native library built and copied to: $JNILIBS_DIR"
  ls -la "$JNILIBS_DIR"
}

# ============================================================================
# Build Flutter APK
# ============================================================================

build_flutter_apk() {
  step "Building Flutter APK"

  # Apply Flutter patch if version matches
  CURRENT_FLUTTER_VERSION=$(flutter --version | grep -oP 'Flutter \K[0-9.]+')
  if [[ "$CURRENT_FLUTTER_VERSION" == "3.24.5" ]]; then
    if [[ -f ".github/patches/flutter_3.24.4_dropdown_menu_enableFilter.diff" ]]; then
      FLUTTER_DIR=$(dirname $(dirname $(which flutter)))
      info "Applying Flutter patch..."
      pushd "$FLUTTER_DIR"
      git apply "$OLDPWD/.github/patches/flutter_3.24.4_dropdown_menu_enableFilter.diff" 2>/dev/null || warn "Patch may already be applied"
      popd
    fi
  fi

  # Modify gradle for CI-like memory settings
  sed -i "s/org.gradle.jvmargs=-Xmx1024M/org.gradle.jvmargs=-Xmx2g/g" ./flutter/android/gradle.properties 2>/dev/null || true

  # Use debug signing config (for unsigned APK)
  sed -i "s/signingConfigs.release/signingConfigs.debug/g" ./flutter/android/app/build.gradle 2>/dev/null || true

  # Set Java environment
  export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
  export PATH="$JAVA_HOME/bin:$PATH"

  info "Building APK for platform: $FLUTTER_PLATFORM"

  pushd flutter
  flutter build apk --release --target-platform "$FLUTTER_PLATFORM" --split-per-abi

  if [[ $? -ne 0 ]]; then
    popd
    error "Flutter APK build failed"
  fi
  popd

  # Move APK to root directory
  APK_NAME="rustdesk-$VERSION-$ANDROID_ARCH.apk"
  case "$ANDROID_ARCH" in
  aarch64 | arm64)
    mv "./flutter/build/app/outputs/flutter-apk/app-arm64-v8a-release.apk" "./$APK_NAME"
    ;;
  armv7 | arm)
    mv "./flutter/build/app/outputs/flutter-apk/app-armeabi-v7a-release.apk" "./$APK_NAME"
    ;;
  x86_64 | x64)
    mv "./flutter/build/app/outputs/flutter-apk/app-x86_64-release.apk" "./$APK_NAME"
    ;;
  x86)
    mv "./flutter/build/app/outputs/flutter-apk/app-x86-release.apk" "./$APK_NAME"
    ;;
  esac

  info "APK built: $APK_NAME"
}

# ============================================================================
# Show Output
# ============================================================================

show_output() {
  step "Build Output"

  APK_NAME="rustdesk-$VERSION-$ANDROID_ARCH.apk"

  echo ""
  info "Build completed successfully!"
  echo ""

  if [[ -f "$APK_NAME" ]]; then
    info "APK: $APK_NAME"
    ls -lh "$APK_NAME"
    echo ""
    info "To install on device:"
    echo "  adb install $APK_NAME"
  fi

  # Also check flutter build directory
  info "Flutter build outputs:"
  ls -la flutter/build/app/outputs/flutter-apk/*.apk 2>/dev/null || true
}

# ============================================================================
# Main
# ============================================================================

print_usage() {
  echo "Usage: $0 [options] [architecture]"
  echo ""
  echo "Architecture (default: aarch64):"
  echo "  aarch64, arm64    - ARM 64-bit (most modern Android devices)"
  echo "  armv7, arm        - ARM 32-bit (older devices)"
  echo "  x86_64, x64       - x86 64-bit (emulators, some tablets)"
  echo "  x86               - x86 32-bit (old emulators)"
  echo ""
  echo "Options:"
  echo "  --skip-deps       Skip apt dependency installation"
  echo "  --skip-bridge     Skip bridge generation"
  echo "  --skip-vcpkg      Skip vcpkg dependency installation"
  echo "  --skip-native     Skip native library build"
  echo "  --help            Show this help"
  echo ""
  echo "Environment variables:"
  echo "  ANDROID_ARCH      Set architecture (alternative to positional arg)"
  echo "  ANDROID_SDK_ROOT  Android SDK path"
  echo "  ANDROID_NDK_HOME  Android NDK path"
  echo "  VCPKG_ROOT        vcpkg installation path"
  echo "  JAVA_HOME         Java 17 installation path"
}

main() {
  echo "========================================"
  echo "RustDesk Android Build Script"
  echo "========================================"

  START_TIME=$(date +%s)

  # Parse arguments
  SKIP_DEPS=false
  SKIP_BRIDGE=false
  SKIP_VCPKG=false
  SKIP_NATIVE=false

  while [[ $# -gt 0 ]]; do
    case $1 in
    --skip-deps)
      SKIP_DEPS=true
      shift
      ;;
    --skip-bridge)
      SKIP_BRIDGE=true
      shift
      ;;
    --skip-vcpkg)
      SKIP_VCPKG=true
      shift
      ;;
    --skip-native)
      SKIP_NATIVE=true
      shift
      ;;
    --help | -h)
      print_usage
      exit 0
      ;;
    aarch64 | arm64 | armv7 | arm | x86_64 | x64 | x86)
      ANDROID_ARCH="$1"
      shift
      ;;
    *)
      warn "Unknown option: $1"
      shift
      ;;
    esac
  done

  configure_arch
  preflight_checks

  [[ "$SKIP_DEPS" == "false" ]] && install_dependencies
  [[ "$SKIP_BRIDGE" == "false" ]] && generate_bridge
  [[ "$SKIP_VCPKG" == "false" ]] && install_vcpkg_deps
  setup_rust_android
  [[ "$SKIP_NATIVE" == "false" ]] && build_native_lib
  build_flutter_apk
  show_output

  END_TIME=$(date +%s)
  DURATION=$((END_TIME - START_TIME))
  info "Total build time: $((DURATION / 60)) minutes $((DURATION % 60)) seconds"
}

main "$@"
