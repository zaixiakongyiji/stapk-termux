#!/bin/bash
# stAPK Bootstrap Build Script
# Builds a custom aarch64 Termux bootstrap with Node.js, Git, and dependencies.
# Target Node.js major version >= 20.
#
# Prerequisites:
#   - Linux x86_64 host (WSL2 Ubuntu 24.04 OK)
#   - Docker (recommended) or full build dependencies
#   - ~20GB free disk space for termux-packages build environment
#   - Stable internet connection for initial setup
#
# Usage: ./scripts/build-bootstrap-aarch64.sh [output-dir]
# Default output-dir: ./output
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="${1:-$PROJECT_ROOT/output}"
PACKAGES_DIR="$PROJECT_ROOT/upstream/termux-packages"

# Packages to include in the custom bootstrap
BOOTSTRAP_PACKAGES=(
    bash
    busybox
    coreutils
    tar
    gzip
    termux-tools
    git
    nodejs-lts
    npm
    openssl
    ca-certificates
    # Node.js 运行时依赖
    c-ares
    libicu
    libsqlite
    zlib
)

echo "=== stAPK Bootstrap Builder ==="
echo "Target: aarch64 (arm64)"
echo "Packages: ${BOOTSTRAP_PACKAGES[*]}"
echo ""

# Check prerequisites
for cmd in git docker; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "WARNING: $cmd not found. Docker is recommended for building."
    fi
done

# Clone termux-packages if needed
if [ ! -d "$PACKAGES_DIR" ]; then
    echo "Cloning termux-packages..."
    git clone --depth=1 https://github.com/termux/termux-packages.git "$PACKAGES_DIR"
else
    echo "termux-packages already exists at $PACKAGES_DIR"
fi

cd "$PACKAGES_DIR"

# Method 1: Use termux-packages' bootstrap generation (recommended)
# The termux-packages repo has scripts/generate-bootstraps.sh or similar.
# We configure which packages to include, then build.

echo "Verifying Node.js versions in termux-packages..."
NODEJS_LTS_VERSION=$(grep -oP '(?<=TERMUX_PKG_VERSION=)[0-9]+' "$PACKAGES_DIR/packages/nodejs-lts/build.sh" | head -n 1 || echo "0")
NODEJS_CURRENT_VERSION=$(grep -oP '(?<=TERMUX_PKG_VERSION=)[0-9]+' "$PACKAGES_DIR/packages/nodejs/build.sh" | head -n 1 || echo "0")

if [ "$NODEJS_LTS_VERSION" -ge 20 ]; then
    echo "Check passed: nodejs-lts major version is $NODEJS_LTS_VERSION (>= 20)"
    # Keep nodejs-lts in BOOTSTRAP_PACKAGES
elif [ "$NODEJS_CURRENT_VERSION" -ge 20 ]; then
    echo "Check passed: nodejs major version is $NODEJS_CURRENT_VERSION (>= 20). Switching from nodejs-lts to nodejs."
    # Replace nodejs-lts with nodejs in the array
    BOOTSTRAP_PACKAGES=( "${BOOTSTRAP_PACKAGES[@]/nodejs-lts/nodejs}" )
else
    echo "ERROR: Neither nodejs-lts ($NODEJS_LTS_VERSION) nor nodejs ($NODEJS_CURRENT_VERSION) satisfies >= 20 requirement."
    exit 1
fi


echo ""
echo "=== Build Instructions ==="
echo ""
echo "The bootstrap build requires the termux-packages build environment."
echo "Two methods are available:"
echo ""
echo "Method A: Docker (recommended)"
echo "  cd $PACKAGES_DIR"
echo "  ./scripts/run-docker.sh ./build-package.sh -a aarch64 ${BOOTSTRAP_PACKAGES[*]}"
echo "  ./scripts/generate-bootstrap.sh"
echo ""
echo "Method B: Native build"
echo "  cd $PACKAGES_DIR"
echo "  ./scripts/setup-ubuntu.sh"
echo "  ./build-package.sh -a aarch64 ${BOOTSTRAP_PACKAGES[*]}"
echo "  ./scripts/generate-bootstrap.sh"
echo ""
echo "After building, copy the bootstrap to:"
echo "  cp bootstrap-*.zip $PROJECT_ROOT/upstream/termux-app/app/src/main/cpp/"
echo ""
echo "Then modify the Termux App build.gradle to use the local bootstrap"
echo "instead of downloading from GitHub."
echo ""
echo "After generation, verify the output archive format:"
echo "  unzip -p bootstrap-*.zip bin/node | file -"
echo "Ensure it reports as aarch64 ELF."
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Generate a package list file for reference
cat > "$OUTPUT_DIR/bootstrap-packages.txt" <<EOF
# stAPK Custom Bootstrap Packages
# Target: aarch64
# Generated: $(date -u '+%Y-%m-%dT%H:%M:%SZ')

$(printf '%s\n' "${BOOTSTRAP_PACKAGES[@]}")
EOF

echo "Package list written to $OUTPUT_DIR/bootstrap-packages.txt"
echo ""
echo "Note: This is a heavy build process (~20GB disk, ~30-60 min)."
echo "Consider using a CI/CD pipeline for reproducible builds."
