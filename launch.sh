#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$(uname -s)" = Darwin ]; then
    source ./macos/common.sh
    use_java21
fi
if [ ! -f target/Cosmic.jar ]; then
    echo "Build the server first: ./mvnw package (or ./setup-macos.sh on macOS)." >&2
    exit 1
fi
exec java "-Xmx${COSMIC_HEAP:-2048m}" -Dwz-path=wz -jar target/Cosmic.jar "$@"
