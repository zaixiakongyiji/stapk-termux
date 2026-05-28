#!/bin/bash
# stAPK Payload Build Script
# Prepares SillyTavern release branch with node_modules for APK bundling.
# Run on Linux x86_64 or aarch64 (WSL2 OK).
#
# Usage: ./scripts/prepare-sillytavern-payload.sh [output-dir]
# Default output-dir: ./payload
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="${1:-$PROJECT_ROOT/payload}"
REPO_URL="https://github.com/SillyTavern/SillyTavern.git"
BRANCH="release"
WORK_DIR="$OUTPUT_DIR/work"

echo "=== stAPK Payload Builder ==="
echo "Output: $OUTPUT_DIR"

# Check dependencies
for cmd in git node npm tar; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "ERROR: $cmd is required but not found"
        exit 1
    fi
done

NODE_VER=$(node --version)
NPM_VER=$(npm --version)
echo "Node: $NODE_VER, npm: $NPM_VER"

# Clean and prepare
mkdir -p "$OUTPUT_DIR" "$WORK_DIR"
rm -rf "$WORK_DIR/SillyTavern"

# Clone SillyTavern (禁用 autocrlf 防止 Windows 换行符污染)
echo "Cloning SillyTavern ($BRANCH)..."
git clone --branch "$BRANCH" --depth=1 --config core.autocrlf=false "$REPO_URL" "$WORK_DIR/SillyTavern"
cd "$WORK_DIR/SillyTavern"

ST_COMMIT=$(git rev-parse HEAD)
ST_BRANCH=$(git branch --show-current)
ST_VERSION=$(grep -o '"version":[[:space:]]*"[^"]*"' package.json | head -1 | cut -d'"' -f4)
echo "SillyTavern $ST_VERSION @ $ST_COMMIT"

# Install production dependencies
echo "Installing production dependencies..."
npm install --no-save --no-audit --no-fund --loglevel=error --no-progress --omit=dev --ignore-scripts

# Native addon scan
echo "Scanning for native addons..."
NATIVE_PATTERNS=("*.node" "binding.gyp" "node-gyp" "prebuild" "node-pre-gyp" "bindings" "nan" "node-addon-api")
HAS_NATIVE=false
FOUND_PATTERNS=()

for pattern in "${NATIVE_PATTERNS[@]}"; do
    if find node_modules -name "$pattern" 2>/dev/null | head -1 | grep -q .; then
        HAS_NATIVE=true
        FOUND_PATTERNS+=("$pattern")
    fi
done

if [ "$HAS_NATIVE" = true ]; then
    echo "WARNING: Native addon patterns found: ${FOUND_PATTERNS[*]}"
    echo "  Payload may not work cross-platform. Consider building on target architecture."
else
    echo "No native addons detected."
fi

# Calculate sizes
PAYLOAD_UNPACKED_SIZE=$(du -sb "$WORK_DIR/SillyTavern" | awk '{print $1}')
REQUIRED_FREE=$(( (PAYLOAD_UNPACKED_SIZE * 3 + 1) / 2 ))

# Generate payload-manifest.json
cat > "$WORK_DIR/payload-manifest.json" <<EOF
{
  "sillytavern_repo": "$REPO_URL",
  "branch": "$ST_BRANCH",
  "commit": "$ST_COMMIT",
  "sillytavern_version": "$ST_VERSION",
  "node_version": "$NODE_VER",
  "npm_version": "$NPM_VER",
  "created_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')",
  "payload_unpacked_size_bytes": $PAYLOAD_UNPACKED_SIZE,
  "required_free_bytes": $REQUIRED_FREE,
  "native_addon_scan": {
    "has_native_addon": $HAS_NATIVE,
    "checked_patterns": [$(printf '"%s",' "${NATIVE_PATTERNS[@]}" | sed 's/,$//')]
  }
}
EOF

# Package
echo "Creating tarball..."
ARCHIVE="$OUTPUT_DIR/SillyTavern.tar.gz"
cd "$WORK_DIR"
tar --numeric-owner --preserve-permissions -czf "$ARCHIVE" SillyTavern payload-manifest.json

ARCHIVE_SIZE=$(stat -c%s "$ARCHIVE" 2>/dev/null || stat -f%z "$ARCHIVE")
echo ""
echo "=== Payload Build Complete ==="
echo "Archive: $ARCHIVE"
echo "Archive size: $(numfmt --to=iec $ARCHIVE_SIZE 2>/dev/null || echo "${ARCHIVE_SIZE} bytes")"
echo "Unpacked size: $(numfmt --to=iec $PAYLOAD_UNPACKED_SIZE 2>/dev/null || echo "${PAYLOAD_UNPACKED_SIZE} bytes")"
echo "Required free: $(numfmt --to=iec $REQUIRED_FREE 2>/dev/null || echo "${REQUIRED_FREE} bytes")"
echo "SillyTavern: $ST_VERSION @ $ST_COMMIT"
echo "Node: $NODE_VER, npm: $NPM_VER"
echo "Native addons: $HAS_NATIVE"
echo ""
echo "Copy $ARCHIVE to your APK assets directory:"
echo "  cp $ARCHIVE upstream/termux-app/app/src/main/assets/"
echo "  cp $WORK_DIR/payload-manifest.json upstream/termux-app/app/src/main/assets/"
