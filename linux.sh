#!/bin/bash
#
# RustDesk Linux Desktop Build Script
# Based on .github/workflows/flutter-build.yml (build-rustdesk-linux job)
#
# This script builds RustDesk Flutter desktop client for Linux x86_64
# Run this from the ~/rustdesk directory
#
# Prerequisites (install with setup-ci-prereqs.sh first):
#   - Ubuntu 24.04 (or compatible)
#   - Rust toolchain (stable)
#   - Flutter SDK
#   - vcpkg with dependencies installed
#   - VCPKG_ROOT environment variable set
#

set -e

# ============================================================================
# Configuration (from CI workflow)
# ============================================================================

RUST_VERSION="1.75"
FLUTTER_VERSION="3.24.5"
FLUTTER_RUST_BRIDGE_VERSION="1.80.1"
CARGO_EXPAND_VERSION="1.0.95"
TARGET="x86_64-unknown-linux-gnu"
VCPKG_TRIPLET="x64-linux"
ARCH="x86_64"
DEB_ARCH="amd64"

# Features to build with
CARGO_FEATURES="hwcodec,flutter,unix-file-copy-paste"

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
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }
step() { echo -e "\n${BLUE}==== $1 ====${NC}\n"; }

check_command() {
    if ! command -v "$1" &> /dev/null; then
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
    check_command pkg-config
    
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
    
    # Check vcpkg dependencies are installed
    if [[ ! -d "$VCPKG_ROOT/installed/$VCPKG_TRIPLET" ]]; then
        warn "vcpkg dependencies may not be installed for $VCPKG_TRIPLET"
        warn "Run: $VCPKG_ROOT/vcpkg install --triplet $VCPKG_TRIPLET"
    fi
    
    # Get version from Cargo.toml
    VERSION=$(grep -m1 '^version' Cargo.toml | sed 's/version = "\(.*\)"/\1/')
    info "Building RustDesk version: $VERSION"
    
    # Show environment
    info "Rust version: $(rustc --version)"
    info "Flutter version: $(flutter --version | head -1)"
    info "VCPKG_ROOT: $VCPKG_ROOT"
}

# ============================================================================
# Install Additional Dependencies
# ============================================================================

install_dependencies() {
    step "Installing Additional Build Dependencies"
    
    # These are the deps from CI that might not be in setup-ci-prereqs.sh
    sudo apt-get update
    sudo apt-get install -y \
        libayatana-appindicator3-dev \
        rpm \
        tree \
        xz-utils || true
    
    # Remove system libopus if exists (we use vcpkg version)
    sudo apt-get remove -y libopus-dev 2>/dev/null || true
    
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
    if ! command -v flutter_rust_bridge_codegen &> /dev/null; then
        info "Installing flutter_rust_bridge_codegen..."
        cargo install flutter_rust_bridge_codegen \
            --version "$FLUTTER_RUST_BRIDGE_VERSION" \
            --features "uuid" \
            --locked
    fi
    
    # Install cargo-expand if not present
    if ! cargo expand --version &> /dev/null 2>&1; then
        info "Installing cargo-expand..."
        cargo install cargo-expand --version "$CARGO_EXPAND_VERSION" --locked
    fi
    
    # Run flutter pub get first
    info "Running flutter pub get..."
    pushd flutter
    # Patch pubspec.yaml for compatibility
    #sed -i -e 's/extended_text: 14.0.0/extended_text: 13.0.0/g' pubspec.yaml 2>/dev/null || true
    flutter pub get
    popd
    
    # Generate bridge
    info "Running flutter_rust_bridge_codegen..."
    flutter_rust_bridge_codegen \
        --rust-input ./src/flutter_ffi.rs \
        --dart-output ./flutter/lib/generated_bridge.dart \
        --c-output ./flutter/macos/Runner/bridge_generated.h
    
    # Copy header for iOS (even though we're building Linux, keep consistency)
    cp ./flutter/macos/Runner/bridge_generated.h ./flutter/ios/Runner/bridge_generated.h 2>/dev/null || true
    
    info "Bridge generation complete"
}

# ============================================================================
# Install vcpkg Dependencies
# ============================================================================

install_vcpkg_deps() {
    step "Installing vcpkg Dependencies"
    
    # Check if already installed
    if [[ -d "$VCPKG_ROOT/installed/$VCPKG_TRIPLET/lib" ]]; then
        info "vcpkg dependencies appear to be installed"
        read -p "Reinstall vcpkg dependencies? [y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            info "Skipping vcpkg install"
            return
        fi
    fi
    
    info "Installing vcpkg dependencies for $VCPKG_TRIPLET..."
    
    # Install libva-dev (required for hwcodec)
    sudo apt-get install -y libva-dev
    
    # Run vcpkg install
    if ! "$VCPKG_ROOT/vcpkg" install \
        --triplet "$VCPKG_TRIPLET" \
        --x-install-root="$VCPKG_ROOT/installed"; then
        
        warn "vcpkg install may have failed. Showing recent logs:"
        find "$VCPKG_ROOT/buildtrees" -name "*.log" -mmin -5 2>/dev/null | head -5 | while read -r log; do
            echo "=== $log ==="
            tail -50 "$log"
        done
        error "vcpkg install failed"
    fi
    
    info "vcpkg dependencies installed"
}

# ============================================================================
# Build Rust Library
# ============================================================================

build_rust_lib() {
    step "Building Rust Library"
    
    # Add target if needed
    rustup target add "$TARGET" 2>/dev/null || true
    
    # The CI modifies Cargo.toml to only build cdylib, but for local builds
    # we can skip this since we're using build.py
    
    info "Building Rust library with features: $CARGO_FEATURES"
    
    export VCPKG_ROOT="$VCPKG_ROOT"
    
    cargo build --lib \
        --features "$CARGO_FEATURES" \
        --release
    
    info "Rust library built successfully"
}

# ============================================================================
# Build Flutter App
# ============================================================================

build_flutter() {
    step "Building Flutter Desktop App"
    
    # Apply flutter patch if version matches
    CURRENT_FLUTTER_VERSION=$(flutter --version | grep -oP 'Flutter \K[0-9.]+')
    if [[ "$CURRENT_FLUTTER_VERSION" == "3.24.5" ]]; then
        FLUTTER_DIR=$(dirname $(dirname $(which flutter)))
        if [[ -f ".github/patches/flutter_3.24.4_dropdown_menu_enableFilter.diff" ]]; then
            info "Applying Flutter patch..."
            pushd "$FLUTTER_DIR"
            git apply "$OLDPWD/.github/patches/flutter_3.24.4_dropdown_menu_enableFilter.diff" 2>/dev/null || warn "Patch may already be applied"
            popd
        fi
    fi
    
    # Set environment for build.py
    export VCPKG_ROOT="$VCPKG_ROOT"
    export DEB_ARCH="$DEB_ARCH"
    export CARGO_INCREMENTAL=0
    
    info "Running build.py..."
    python3 ./build.py --flutter --hwcodec --unix-file-copy-paste
    
    info "Flutter build complete"
}

# ============================================================================
# Package Output
# ============================================================================

show_output() {
    step "Build Output"
    
    VERSION=$(grep -m1 '^version' Cargo.toml | sed 's/version = "\(.*\)"/\1/')
    
    echo ""
    info "Build completed successfully!"
    echo ""
    
    # Check for output files
    if [[ -f "rustdesk-${VERSION}-${ARCH}.deb" ]]; then
        info "Debian package: rustdesk-${VERSION}-${ARCH}.deb"
        ls -lh "rustdesk-${VERSION}-${ARCH}.deb"
    fi
    
    if [[ -d "flutter/build/linux/x64/release/bundle" ]]; then
        info "Flutter build directory: flutter/build/linux/x64/release/bundle/"
        ls -la flutter/build/linux/x64/release/bundle/
    fi
    
    echo ""
    info "To run RustDesk:"
    echo "  ./flutter/build/linux/x64/release/bundle/rustdesk"
    echo ""
    info "To install the .deb package:"
    echo "  sudo dpkg -i rustdesk-${VERSION}-${ARCH}.deb"
}

# ============================================================================
# Main
# ============================================================================

main() {
    echo "========================================"
    echo "RustDesk Linux Desktop Build Script"
    echo "========================================"
    
    START_TIME=$(date +%s)
    
    preflight_checks
    
    # Parse arguments
    SKIP_DEPS=false
    SKIP_BRIDGE=false
    SKIP_VCPKG=false
    SKIP_RUST=false
    
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
            --skip-rust)
                SKIP_RUST=true
                shift
                ;;
            --help)
                echo "Usage: $0 [options]"
                echo "Options:"
                echo "  --skip-deps    Skip apt dependency installation"
                echo "  --skip-bridge  Skip bridge generation"
                echo "  --skip-vcpkg   Skip vcpkg dependency installation"
                echo "  --skip-rust    Skip Rust library build (use with --skip-cargo in build.py)"
                exit 0
                ;;
            *)
                warn "Unknown option: $1"
                shift
                ;;
        esac
    done
    
    [[ "$SKIP_DEPS" == "false" ]] && install_dependencies
    [[ "$SKIP_BRIDGE" == "false" ]] && generate_bridge
    [[ "$SKIP_VCPKG" == "false" ]] && install_vcpkg_deps
    [[ "$SKIP_RUST" == "false" ]] && build_rust_lib
    build_flutter
    show_output
    
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    info "Total build time: $((DURATION / 60)) minutes $((DURATION % 60)) seconds"
}

main "$@"
#!/bin/bash
#
# RustDesk Linux Desktop Build Script
# Based on .github/workflows/flutter-build.yml (build-rustdesk-linux job)
#
# This script builds RustDesk Flutter desktop client for Linux x86_64
# Run this from the ~/rustdesk directory
#
# Prerequisites (install with setup-ci-prereqs.sh first):
#   - Ubuntu 24.04 (or compatible)
#   - Rust toolchain (stable)
#   - Flutter SDK
#   - vcpkg with dependencies installed
#   - VCPKG_ROOT environment variable set
#

set -e

# ============================================================================
# Configuration (from CI workflow)
# ============================================================================

RUST_VERSION="1.75"
FLUTTER_VERSION="3.24.5"
FLUTTER_RUST_BRIDGE_VERSION="1.80.1"
CARGO_EXPAND_VERSION="1.0.95"
TARGET="x86_64-unknown-linux-gnu"
VCPKG_TRIPLET="x64-linux"
ARCH="x86_64"
DEB_ARCH="amd64"

# Features to build with
CARGO_FEATURES="hwcodec,flutter,unix-file-copy-paste"

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
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }
step() { echo -e "\n${BLUE}==== $1 ====${NC}\n"; }

check_command() {
    if ! command -v "$1" &> /dev/null; then
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
    check_command pkg-config
    
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
    
    # Check vcpkg dependencies are installed
    if [[ ! -d "$VCPKG_ROOT/installed/$VCPKG_TRIPLET" ]]; then
        warn "vcpkg dependencies may not be installed for $VCPKG_TRIPLET"
        warn "Run: $VCPKG_ROOT/vcpkg install --triplet $VCPKG_TRIPLET"
    fi
    
    # Get version from Cargo.toml
    VERSION=$(grep -m1 '^version' Cargo.toml | sed 's/version = "\(.*\)"/\1/')
    info "Building RustDesk version: $VERSION"
    
    # Show environment
    info "Rust version: $(rustc --version)"
    info "Flutter version: $(flutter --version | head -1)"
    info "VCPKG_ROOT: $VCPKG_ROOT"
}

# ============================================================================
# Install Additional Dependencies
# ============================================================================

install_dependencies() {
    step "Installing Additional Build Dependencies"
    
    # These are the deps from CI that might not be in setup-ci-prereqs.sh
    sudo apt-get update
    sudo apt-get install -y \
        libayatana-appindicator3-dev \
        rpm \
        tree \
        xz-utils || true
    
    # Remove system libopus if exists (we use vcpkg version)
    sudo apt-get remove -y libopus-dev 2>/dev/null || true
    
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
    if ! command -v flutter_rust_bridge_codegen &> /dev/null; then
        info "Installing flutter_rust_bridge_codegen..."
        cargo install flutter_rust_bridge_codegen \
            --version "$FLUTTER_RUST_BRIDGE_VERSION" \
            --features "uuid" \
            --locked
    fi
    
    # Install cargo-expand if not present
    if ! cargo expand --version &> /dev/null 2>&1; then
        info "Installing cargo-expand..."
        cargo install cargo-expand --version "$CARGO_EXPAND_VERSION" --locked
    fi
    
    # Run flutter pub get first
    info "Running flutter pub get..."
    pushd flutter
    # Patch pubspec.yaml for compatibility
    sed -i -e 's/extended_text: 14.0.0/extended_text: 13.0.0/g' pubspec.yaml 2>/dev/null || true
    flutter pub get
    popd
    
    # Generate bridge
    info "Running flutter_rust_bridge_codegen..."
    flutter_rust_bridge_codegen \
        --rust-input ./src/flutter_ffi.rs \
        --dart-output ./flutter/lib/generated_bridge.dart \
        --c-output ./flutter/macos/Runner/bridge_generated.h
    
    # Copy header for iOS (even though we're building Linux, keep consistency)
    cp ./flutter/macos/Runner/bridge_generated.h ./flutter/ios/Runner/bridge_generated.h 2>/dev/null || true
    
    info "Bridge generation complete"
}

# ============================================================================
# Install vcpkg Dependencies
# ============================================================================

install_vcpkg_deps() {
    step "Installing vcpkg Dependencies"
    
    # Check if already installed
    if [[ -d "$VCPKG_ROOT/installed/$VCPKG_TRIPLET/lib" ]]; then
        info "vcpkg dependencies appear to be installed"
        read -p "Reinstall vcpkg dependencies? [y/N] " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            info "Skipping vcpkg install"
            return
        fi
    fi
    
    info "Installing vcpkg dependencies for $VCPKG_TRIPLET..."
    
    # Install libva-dev (required for hwcodec)
    sudo apt-get install -y libva-dev
    
    # Run vcpkg install
    if ! "$VCPKG_ROOT/vcpkg" install \
        --triplet "$VCPKG_TRIPLET" \
        --x-install-root="$VCPKG_ROOT/installed"; then
        
        warn "vcpkg install may have failed. Showing recent logs:"
        find "$VCPKG_ROOT/buildtrees" -name "*.log" -mmin -5 2>/dev/null | head -5 | while read -r log; do
            echo "=== $log ==="
            tail -50 "$log"
        done
        error "vcpkg install failed"
    fi
    
    info "vcpkg dependencies installed"
}

# ============================================================================
# Build Rust Library
# ============================================================================

build_rust_lib() {
    step "Building Rust Library"
    
    # Add target if needed
    rustup target add "$TARGET" 2>/dev/null || true
    
    # The CI modifies Cargo.toml to only build cdylib, but for local builds
    # we can skip this since we're using build.py
    
    info "Building Rust library with features: $CARGO_FEATURES"
    
    export VCPKG_ROOT="$VCPKG_ROOT"
    
    cargo build --lib \
        --features "$CARGO_FEATURES" \
        --release
    
    info "Rust library built successfully"
}

# ============================================================================
# Build Flutter App
# ============================================================================

build_flutter() {
    step "Building Flutter Desktop App"
    
    # Apply flutter patch if version matches
    CURRENT_FLUTTER_VERSION=$(flutter --version | grep -oP 'Flutter \K[0-9.]+')
    if [[ "$CURRENT_FLUTTER_VERSION" == "3.24.5" ]]; then
        FLUTTER_DIR=$(dirname $(dirname $(which flutter)))
        if [[ -f ".github/patches/flutter_3.24.4_dropdown_menu_enableFilter.diff" ]]; then
            info "Applying Flutter patch..."
            pushd "$FLUTTER_DIR"
            git apply "$OLDPWD/.github/patches/flutter_3.24.4_dropdown_menu_enableFilter.diff" 2>/dev/null || warn "Patch may already be applied"
            popd
        fi
    fi
    
    # Set environment for build.py
    export VCPKG_ROOT="$VCPKG_ROOT"
    export DEB_ARCH="$DEB_ARCH"
    export CARGO_INCREMENTAL=0
    
    info "Running build.py..."
    python3 ./build.py --flutter --hwcodec --unix-file-copy-paste
    
    info "Flutter build complete"
}

# ============================================================================
# Package Output
# ============================================================================

show_output() {
    step "Build Output"
    
    VERSION=$(grep -m1 '^version' Cargo.toml | sed 's/version = "\(.*\)"/\1/')
    
    echo ""
    info "Build completed successfully!"
    echo ""
    
    # Check for output files
    if [[ -f "rustdesk-${VERSION}-${ARCH}.deb" ]]; then
        info "Debian package: rustdesk-${VERSION}-${ARCH}.deb"
        ls -lh "rustdesk-${VERSION}-${ARCH}.deb"
    fi
    
    if [[ -d "flutter/build/linux/x64/release/bundle" ]]; then
        info "Flutter build directory: flutter/build/linux/x64/release/bundle/"
        ls -la flutter/build/linux/x64/release/bundle/
    fi
    
    echo ""
    info "To run RustDesk:"
    echo "  ./flutter/build/linux/x64/release/bundle/rustdesk"
    echo ""
    info "To install the .deb package:"
    echo "  sudo dpkg -i rustdesk-${VERSION}-${ARCH}.deb"
}

# ============================================================================
# Main
# ============================================================================

main() {
    echo "========================================"
    echo "RustDesk Linux Desktop Build Script"
    echo "========================================"
    
    START_TIME=$(date +%s)
    
    preflight_checks
    
    # Parse arguments
    SKIP_DEPS=false
    SKIP_BRIDGE=false
    SKIP_VCPKG=false
    SKIP_RUST=false
    
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
            --skip-rust)
                SKIP_RUST=true
                shift
                ;;
            --help)
                echo "Usage: $0 [options]"
                echo "Options:"
                echo "  --skip-deps    Skip apt dependency installation"
                echo "  --skip-bridge  Skip bridge generation"
                echo "  --skip-vcpkg   Skip vcpkg dependency installation"
                echo "  --skip-rust    Skip Rust library build (use with --skip-cargo in build.py)"
                exit 0
                ;;
            *)
                warn "Unknown option: $1"
                shift
                ;;
        esac
    done
    
    [[ "$SKIP_DEPS" == "false" ]] && install_dependencies
    [[ "$SKIP_BRIDGE" == "false" ]] && generate_bridge
    [[ "$SKIP_VCPKG" == "false" ]] && install_vcpkg_deps
    [[ "$SKIP_RUST" == "false" ]] && build_rust_lib
    build_flutter
    show_output
    
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    info "Total build time: $((DURATION / 60)) minutes $((DURATION % 60)) seconds"
}

main "$@"

