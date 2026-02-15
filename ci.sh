#!/usr/bin/bash
#
# RustDesk CI Prerequisites Setup Script
# Emulates .github/workflows/ci.yml lines 113-156
#
# This script sets up the build environment for RustDesk on Ubuntu (24.04)
# Run this from the ~/rustdesk directory
#
# Prerequisites:
#   - Ubuntu 24.04 (or compatible)
#   - vcpkg installed at $HOME/vcpkg (or set VCPKG_ROOT)
#   - VCPKG_ROOT environment variable set
#
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

# vcpkg commit ID from CI (must match vcpkg.json baseline)
# https://github.com/rustdesk/rustdesk/blob/master/.github/workflows/ci.yml
CI_VCPKG_COMMIT_ID="120deac3062162151622ca4860575a33844ba10b"

# Check if we're in the rustdesk directory
check_rustdesk_dir() {
    if [[ ! -f "Cargo.toml" ]] || [[ ! -f "vcpkg.json" ]]; then
        error "This script must be run from the rustdesk directory (e.g., ~/rustdesk)"
    fi
    info "Running from: $(pwd)"
}

# Step 1: Install Ubuntu prerequisites (ci.yml lines 113-145)
install_prerequisites() {
    info "Installing system prerequisites..."
    
    sudo apt-get -y update
    sudo apt-get install -y \
        clang \
        cmake \
        curl \
        gcc \
        git \
        g++ \
        libpam0g-dev \
        libasound2-dev \
        libunwind-dev \
        libgstreamer1.0-dev \
        libgstreamer-plugins-base1.0-dev \
        libgtk-3-dev \
        libpulse-dev \
        libva-dev \
        libvdpau-dev \
        libxcb-randr0-dev \
        libxcb-shape0-dev \
        libxcb-xfixes0-dev \
        libxdo-dev \
        libxfixes-dev \
        nasm \
        wget \
        pkg-config \
        ninja-build \
        libssl-dev \
        autoconf \
        automake \
        libtool
    
    info "System prerequisites installed successfully."
}

# Step 2: Setup vcpkg (emulates lukka/run-vcpkg@v11)
# The GitHub Action does:
#   1. Clones/updates vcpkg to a specific commit
#   2. Bootstraps vcpkg if needed
#   3. Sets VCPKG_ROOT environment variable
#   4. Configures binary caching (GitHub Actions specific, skipped locally)
setup_vcpkg() {
    info "Setting up vcpkg..."
    echo $VCPK_ROOT
    
    # Check VCPKG_ROOT
    if [[ -z "$VCPKG_ROOT" ]]; then
        if [[ -d "$HOME/vcpkg" ]]; then
            export VCPKG_ROOT="$HOME/vcpkg"
            warn "VCPKG_ROOT not set, using $VCPKG_ROOT"
        else
            error "VCPKG_ROOT environment variable is not set and ~/vcpkg doesn't exist"
        fi
    fi
    
    if [[ ! -d "$VCPKG_ROOT" ]]; then
        error "VCPKG_ROOT directory does not exist: $VCPKG_ROOT"
    fi
    
    info "Using VCPKG_ROOT: $VCPKG_ROOT"
    
    # Check if vcpkg is a git repository
    if [[ -d "$VCPKG_ROOT/.git" ]]; then
        # Get current commit
        current_commit=$(git -C "$VCPKG_ROOT" rev-parse HEAD 2>/dev/null || echo "unknown")
        info "Current vcpkg commit: $current_commit"
        info "CI vcpkg commit:      $CI_VCPKG_COMMIT_ID"
        
        if [[ "$current_commit" != "$CI_VCPKG_COMMIT_ID" ]]; then
            warn "vcpkg commit differs from CI. You can optionally checkout the exact CI commit:"
            warn "  cd $VCPKG_ROOT && git fetch && git checkout $CI_VCPKG_COMMIT_ID"
            echo ""
            read -p "Do you want to checkout the CI vcpkg commit? [y/N] " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                info "Checking out vcpkg commit $CI_VCPKG_COMMIT_ID..."
                git -C "$VCPKG_ROOT" fetch origin
                git -C "$VCPKG_ROOT" checkout "$CI_VCPKG_COMMIT_ID"
                info "vcpkg checked out to CI commit."
            else
                warn "Continuing with current vcpkg version (may cause build differences)"
            fi
        else
            info "vcpkg is already at CI commit."
        fi
    else
        warn "VCPKG_ROOT is not a git repository, cannot verify commit."
    fi
    
    # Bootstrap vcpkg if needed
    if [[ ! -x "$VCPKG_ROOT/vcpkg" ]]; then
        info "Bootstrapping vcpkg..."
        "$VCPKG_ROOT/bootstrap-vcpkg.sh" -disableMetrics
    else
        info "vcpkg executable found: $VCPKG_ROOT/vcpkg"
    fi
    
    # Verify vcpkg works
    "$VCPKG_ROOT/vcpkg" version
    
    info "vcpkg setup complete."
}

# Step 3: Install vcpkg dependencies (ci.yml lines 153-156)
# Uses vcpkg.json manifest mode with overlay-ports from res/vcpkg
install_vcpkg_dependencies() {
    info "Installing vcpkg dependencies..."
    
    # The CI uses: $VCPKG_ROOT/vcpkg install --x-install-root="$VCPKG_ROOT/installed"
    # In manifest mode, vcpkg reads vcpkg.json from the current directory
    # The overlay-ports are defined in vcpkg.json under vcpkg-configuration
    
    # Create installed directory if it doesn't exist
    mkdir -p "$VCPKG_ROOT/installed"
    
    # Determine the triplet for x86_64 Linux
    # For static linking (default for RustDesk), use x64-linux
    VCPKG_DEFAULT_TRIPLET="${VCPKG_DEFAULT_TRIPLET:-x64-linux}"
    export VCPKG_DEFAULT_TRIPLET
    
    info "Using vcpkg triplet: $VCPKG_DEFAULT_TRIPLET"
    info "Using overlay-ports from: $(pwd)/res/vcpkg"
    
    # Run vcpkg install in manifest mode
    # The vcpkg.json in the current directory defines dependencies
    # overlay-ports is configured in vcpkg.json's vcpkg-configuration section
    "$VCPKG_ROOT/vcpkg" install \
        --x-install-root="$VCPKG_ROOT/installed"
    
    info "vcpkg dependencies installed successfully."
    
    # Show what was installed
    info "Installed packages:"
    "$VCPKG_ROOT/vcpkg" list --x-install-root="$VCPKG_ROOT/installed" || true
}

# Show environment info
show_environment() {
    info "Environment Information:"
    echo "  OS:          $(lsb_release -d 2>/dev/null | cut -f2 || cat /etc/os-release | grep PRETTY_NAME | cut -d'"' -f2)"
    echo "  Kernel:      $(uname -r)"
    echo "  Architecture: $(uname -m)"
    echo "  VCPKG_ROOT:  ${VCPKG_ROOT:-'(not set)'}"
    echo "  PWD:         $(pwd)"
    echo ""
}

# Main execution
main() {
    echo "========================================"
    echo "RustDesk CI Prerequisites Setup Script"
    echo "========================================"
    echo ""
    
    show_environment
    check_rustdesk_dir
    
    echo ""
    echo "This script will:"
    echo "  1. Install system prerequisites (requires sudo)"
    echo "  2. Setup/verify vcpkg installation"
    echo "  3. Install vcpkg dependencies (libvpx, libyuv, opus, aom, etc.)"
    echo ""
    
    read -p "Continue? [Y/n] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Nn]$ ]]; then
        install_prerequisites
        echo ""
        setup_vcpkg
        echo ""
        install_vcpkg_dependencies
        echo ""
        info "========================================"
        info "Setup complete!"
        info "========================================"
        echo ""
        info "Next steps:"
        echo "  1. Install Rust toolchain (if not already): rustup default stable"
        echo "  2. Build RustDesk: cargo build"
        echo "  3. Or with Flutter: python3 build.py --flutter"
        echo ""
        info "Make sure these environment variables are set for builds:"
        echo "  export VCPKG_ROOT=\"$VCPKG_ROOT\""
    else
        info "Setup cancelled."
    fi
}

# Run main function
main "$@"
#!/bin/bash
#
# RustDesk CI Prerequisites Setup Script
# Emulates .github/workflows/ci.yml lines 113-156
#
# This script sets up the build environment for RustDesk on Ubuntu (24.04)
# Run this from the ~/rustdesk directory
#
# Prerequisites:
#   - Ubuntu 24.04 (or compatible)
#   - vcpkg installed at $HOME/vcpkg (or set VCPKG_ROOT)
#   - VCPKG_ROOT environment variable set
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

# vcpkg commit ID from CI (must match vcpkg.json baseline)
# https://github.com/rustdesk/rustdesk/blob/master/.github/workflows/ci.yml
CI_VCPKG_COMMIT_ID="120deac3062162151622ca4860575a33844ba10b"

# Check if we're in the rustdesk directory
check_rustdesk_dir() {
    if [[ ! -f "Cargo.toml" ]] || [[ ! -f "vcpkg.json" ]]; then
        error "This script must be run from the rustdesk directory (e.g., ~/rustdesk)"
    fi
    info "Running from: $(pwd)"
}

# Step 1: Install Ubuntu prerequisites (ci.yml lines 113-145)
install_prerequisites() {
    info "Installing system prerequisites..."
    
    sudo apt-get -y update
    sudo apt-get install -y \
        clang \
        cmake \
        curl \
        gcc \
        git \
        g++ \
        libpam0g-dev \
        libasound2-dev \
        libunwind-dev \
        libgstreamer1.0-dev \
        libgstreamer-plugins-base1.0-dev \
        libgtk-3-dev \
        libpulse-dev \
        libva-dev \
        libvdpau-dev \
        libxcb-randr0-dev \
        libxcb-shape0-dev \
        libxcb-xfixes0-dev \
        libxdo-dev \
        libxfixes-dev \
        nasm \
        wget \
        pkg-config \
        ninja-build \
        libssl-dev \
        autoconf \
        automake \
        libtool
    
    info "System prerequisites installed successfully."
}

# Step 2: Setup vcpkg (emulates lukka/run-vcpkg@v11)
# The GitHub Action does:
#   1. Clones/updates vcpkg to a specific commit
#   2. Bootstraps vcpkg if needed
#   3. Sets VCPKG_ROOT environment variable
#   4. Configures binary caching (GitHub Actions specific, skipped locally)
setup_vcpkg() {
    info "Setting up vcpkg..."
    
    # Check VCPKG_ROOT
    if [[ -z "$VCPKG_ROOT" ]]; then
        if [[ -d "$HOME/vcpkg" ]]; then
            export VCPKG_ROOT="$HOME/vcpkg"
            warn "VCPKG_ROOT not set, using $VCPKG_ROOT"
        else
            error "VCPKG_ROOT environment variable is not set and ~/vcpkg doesn't exist"
        fi
    fi
    
    if [[ ! -d "$VCPKG_ROOT" ]]; then
        error "VCPKG_ROOT directory does not exist: $VCPKG_ROOT"
    fi
    
    info "Using VCPKG_ROOT: $VCPKG_ROOT"
    
    # Check if vcpkg is a git repository
    if [[ -d "$VCPKG_ROOT/.git" ]]; then
        # Get current commit
        current_commit=$(git -C "$VCPKG_ROOT" rev-parse HEAD 2>/dev/null || echo "unknown")
        info "Current vcpkg commit: $current_commit"
        info "CI vcpkg commit:      $CI_VCPKG_COMMIT_ID"
        
        if [[ "$current_commit" != "$CI_VCPKG_COMMIT_ID" ]]; then
            warn "vcpkg commit differs from CI. You can optionally checkout the exact CI commit:"
            warn "  cd $VCPKG_ROOT && git fetch && git checkout $CI_VCPKG_COMMIT_ID"
            echo ""
            read -p "Do you want to checkout the CI vcpkg commit? [y/N] " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                info "Checking out vcpkg commit $CI_VCPKG_COMMIT_ID..."
                git -C "$VCPKG_ROOT" fetch origin
                git -C "$VCPKG_ROOT" checkout "$CI_VCPKG_COMMIT_ID"
                info "vcpkg checked out to CI commit."
            else
                warn "Continuing with current vcpkg version (may cause build differences)"
            fi
        else
            info "vcpkg is already at CI commit."
        fi
    else
        warn "VCPKG_ROOT is not a git repository, cannot verify commit."
    fi
    
    # Bootstrap vcpkg if needed
    if [[ ! -x "$VCPKG_ROOT/vcpkg" ]]; then
        info "Bootstrapping vcpkg..."
        "$VCPKG_ROOT/bootstrap-vcpkg.sh" -disableMetrics
    else
        info "vcpkg executable found: $VCPKG_ROOT/vcpkg"
    fi
    
    # Verify vcpkg works
    "$VCPKG_ROOT/vcpkg" version
    
    info "vcpkg setup complete."
}

# Step 3: Install vcpkg dependencies (ci.yml lines 153-156)
# Uses vcpkg.json manifest mode with overlay-ports from res/vcpkg
install_vcpkg_dependencies() {
    info "Installing vcpkg dependencies..."
    
    # The CI uses: $VCPKG_ROOT/vcpkg install --x-install-root="$VCPKG_ROOT/installed"
    # In manifest mode, vcpkg reads vcpkg.json from the current directory
    # The overlay-ports are defined in vcpkg.json under vcpkg-configuration
    
    # Create installed directory if it doesn't exist
    mkdir -p "$VCPKG_ROOT/installed"
    
    # Determine the triplet for x86_64 Linux
    # For static linking (default for RustDesk), use x64-linux
    VCPKG_DEFAULT_TRIPLET="${VCPKG_DEFAULT_TRIPLET:-x64-linux}"
    export VCPKG_DEFAULT_TRIPLET
    
    info "Using vcpkg triplet: $VCPKG_DEFAULT_TRIPLET"
    info "Using overlay-ports from: $(pwd)/res/vcpkg"
    
    # Run vcpkg install in manifest mode
    # The vcpkg.json in the current directory defines dependencies
    # overlay-ports is configured in vcpkg.json's vcpkg-configuration section
    "$VCPKG_ROOT/vcpkg" install \
        --x-install-root="$VCPKG_ROOT/installed"
    
    info "vcpkg dependencies installed successfully."
    
    # Show what was installed
    info "Installed packages:"
    "$VCPKG_ROOT/vcpkg" list --x-install-root="$VCPKG_ROOT/installed" || true
}

# Show environment info
show_environment() {
    info "Environment Information:"
    echo "  OS:          $(lsb_release -d 2>/dev/null | cut -f2 || cat /etc/os-release | grep PRETTY_NAME | cut -d'"' -f2)"
    echo "  Kernel:      $(uname -r)"
    echo "  Architecture: $(uname -m)"
    echo "  VCPKG_ROOT:  ${VCPKG_ROOT:-'(not set)'}"
    echo "  PWD:         $(pwd)"
    echo ""
}

# Main execution
main() {
    echo "something"
    echo $VCPKG_ROOT
    echo "========================================"
    echo "RustDesk CI Prerequisites Setup Script"
    echo "========================================"
    echo ""
    
    show_environment
    check_rustdesk_dir
    
    echo ""
    echo "This script will:"
    echo "  1. Install system prerequisites (requires sudo)"
    echo "  2. Setup/verify vcpkg installation"
    echo "  3. Install vcpkg dependencies (libvpx, libyuv, opus, aom, etc.)"
    echo ""
    
    read -p "Continue? [Y/n] " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Nn]$ ]]; then
        install_prerequisites
        echo ""
        setup_vcpkg
        echo ""
        install_vcpkg_dependencies
        echo ""
        info "========================================"
        info "Setup complete!"
        info "========================================"
        echo ""
        info "Next steps:"
        echo "  1. Install Rust toolchain (if not already): rustup default stable"
        echo "  2. Build RustDesk: cargo build"
        echo "  3. Or with Flutter: python3 build.py --flutter"
        echo ""
        info "Make sure these environment variables are set for builds:"
        echo "  export VCPKG_ROOT=\"$VCPKG_ROOT\""
    else
        info "Setup cancelled."
    fi
}

# Run main function
main "$@"

