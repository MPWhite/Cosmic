#!/bin/bash
# Shared by the macOS entry points. Compatible with macOS's Bash 3.2.
set -euo pipefail

COSMIC_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

require_brew() {
    if ! command -v brew >/dev/null 2>&1; then
        echo "Install Homebrew from https://brew.sh, then run ./setup-macos.sh." >&2
        exit 1
    fi
}

use_java21() {
    require_brew
    local jdk_prefix
    jdk_prefix="$(brew --prefix openjdk@21)"
    export JAVA_HOME="${COSMIC_JAVA_HOME:-$jdk_prefix/libexec/openjdk.jdk/Contents/Home}"
    if [ ! -x "$JAVA_HOME/bin/java" ]; then
        echo "Java 21 is missing. Run ./setup-macos.sh first." >&2
        exit 1
    fi
    export PATH="$JAVA_HOME/bin:$PATH"
}

COSMIC_DB_PORT="${COSMIC_DB_PORT:-3307}"
case "$COSMIC_DB_PORT" in
    ''|*[!0-9]*) echo "COSMIC_DB_PORT must be a TCP port number." >&2; exit 1 ;;
esac
if [ "$COSMIC_DB_PORT" -lt 1024 ] || [ "$COSMIC_DB_PORT" -gt 65535 ]; then
    echo "COSMIC_DB_PORT must be between 1024 and 65535." >&2
    exit 1
fi
