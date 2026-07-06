#!/bin/bash
set -euo pipefail

WORKSPACE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$WORKSPACE_DIR/output"
ASSETS_DIR="$WORKSPACE_DIR/mobile/app/src/main/assets"
TEMP_DIR="$WORKSPACE_DIR/mobile/build/tmp_runtime"

BOOTSTRAP_ZIP="$OUTPUT_DIR/stapk-bootstrap-aarch64.zip"

if [ ! -f "$BOOTSTRAP_ZIP" ]; then
    echo "ERROR: Missing $BOOTSTRAP_ZIP"
    exit 1
fi

echo "Cleaning and preparing temp directory..."
rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR/runtime/bin"
mkdir -p "$TEMP_DIR/runtime/lib"
mkdir -p "$ASSETS_DIR"

echo "Extracting node binary..."
unzip -q -j "$BOOTSTRAP_ZIP" "bin/node" -d "$TEMP_DIR/runtime/bin/" || true
# Some termux-packages versions might include usr/ prefix in zip
if [ ! -f "$TEMP_DIR/runtime/bin/node" ]; then
    unzip -q -j "$BOOTSTRAP_ZIP" "usr/bin/node" -d "$TEMP_DIR/runtime/bin/" || true
fi

if [ ! -f "$TEMP_DIR/runtime/bin/node" ]; then
    echo "ERROR: Failed to extract node binary from bootstrap zip."
    exit 1
fi

chmod +x "$TEMP_DIR/runtime/bin/node"

echo "Detecting Node.js version..."
NODE_VERSION_FULL=$(strings "$TEMP_DIR/runtime/bin/node" | grep -oP '^v[0-9]+\.[0-9]+\.[0-9]+' | head -n 1)
if [ -z "$NODE_VERSION_FULL" ]; then
    echo "ERROR: Could not detect Node.js version from binary."
    exit 1
fi

NODE_MAJOR=$(echo "$NODE_VERSION_FULL" | sed -E 's/^v([0-9]+).*/\1/')
echo "Detected Node.js version: $NODE_VERSION_FULL (Major: $NODE_MAJOR)"

if [ "$NODE_MAJOR" -lt 20 ]; then
    echo "ERROR: Node.js version $NODE_VERSION_FULL is less than required >= v20."
    exit 1
fi

echo "Detecting required dynamic libraries via readelf..."
NEEDED_LIBS=$(readelf -d "$TEMP_DIR/runtime/bin/node" | grep "NEEDED" | awk -F'[][]' '{print $2}')

# Note: We filter out basic Android system libraries that are provided by the OS.
SYS_LIBS="libc.so|libm.so|libdl.so|liblog.so"

echo "Extracting dependencies..."
LIBRARIES_JSON="["

# Read list of files in the zip to find the exact paths
ZIP_FILE_LIST=$(unzip -Z1 "$BOOTSTRAP_ZIP")

FIRST_LIB=true
for lib in $NEEDED_LIBS; do
    if echo "$lib" | grep -qE "^($SYS_LIBS)$"; then
        continue
    fi
    
    echo " - Missing check for $lib..."
    
    # Find the library in the zip file
    LIB_PATH=$(echo "$ZIP_FILE_LIST" | grep -E "/$lib$" | head -n 1)
    if [ -z "$LIB_PATH" ]; then
        echo "ERROR: Could not find required library $lib in bootstrap zip!"
        exit 1
    fi
    
    echo "   Extracting $LIB_PATH"
    unzip -q -j "$BOOTSTRAP_ZIP" "$LIB_PATH" -d "$TEMP_DIR/runtime/lib/"
    
    LIB_SHA256=$(sha256sum "$TEMP_DIR/runtime/lib/$lib" | awk '{print $1}')
    
    if [ "$FIRST_LIB" = true ]; then
        FIRST_LIB=false
    else
        LIBRARIES_JSON+=","
    fi
    LIBRARIES_JSON+="{\"name\": \"$lib\", \"sha256\": \"$LIB_SHA256\"}"
done

LIBRARIES_JSON+="]"

echo "Generating runtime-manifest.json..."
SCRIPT_SHA256=$(sha256sum "${BASH_SOURCE[0]}" | awk '{print $1}')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat > "$TEMP_DIR/runtime-manifest.json" <<EOF
{
  "schema_version": 1,
  "node_version": "$NODE_VERSION_FULL",
  "abi": "arm64-v8a",
  "min_sdk": 24,
  "libc": "bionic",
  "source": {
    "kind": "termux-packages",
    "termux_packages_commit": "unknown",
    "package": "nodejs-lts",
    "package_version": "unknown"
  },
  "build": {
    "extractor_script": "scripts/build-runtime-archive.sh",
    "extractor_sha256": "$SCRIPT_SHA256",
    "built_at": "$TIMESTAMP"
  },
  "libraries": $LIBRARIES_JSON
}
EOF

ARCHIVE_NAME="runtime-android-arm64-node${NODE_MAJOR}.zip"
ARCHIVE_PATH="$ASSETS_DIR/$ARCHIVE_NAME"

echo "Packing archive $ARCHIVE_NAME..."
cd "$TEMP_DIR"
zip -q "$ARCHIVE_PATH" runtime-manifest.json
zip -qr "$ARCHIVE_PATH" runtime/

echo "Calculating archive SHA256..."
cd "$WORKSPACE_DIR"
sha256sum "$ARCHIVE_PATH" | awk '{print $1}' > "${ARCHIVE_PATH}.sha256"

echo "Cleaning up..."
rm -rf "$TEMP_DIR"

echo "SUCCESS! Created $ARCHIVE_PATH"
