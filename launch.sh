#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
# Optional local credentials/configuration for resident chat and Jev. This file is ignored by git.
if [ -f .local/resident-ai.env ]; then
    set -a
    source .local/resident-ai.env
    set +a
fi
if [ "$(uname -s)" = Darwin ]; then
    source ./macos/common.sh
    use_java21
fi
if [ ! -f target/Cosmic.jar ]; then
    echo "Build the server first: ./mvnw package (or ./setup-macos.sh on macOS)." >&2
    exit 1
fi
# Maven replaces target/Cosmic.jar while packaging. Java loads classes lazily,
# so a running server needs its own immutable copy throughout the session.
mkdir -p .local/runtime
runtime_copy=$(mktemp .local/runtime/copy.XXXXXX)
trap 'rm -f "$runtime_copy"' EXIT
cp target/Cosmic.jar "$runtime_copy"
if command -v sha256sum >/dev/null 2>&1; then
    runtime_digest=$(sha256sum "$runtime_copy" | cut -d ' ' -f 1)
else
    runtime_digest=$(shasum -a 256 "$runtime_copy" | cut -d ' ' -f 1)
fi
runtime_jar=".local/runtime/Cosmic-$runtime_digest.jar"
mv -n "$runtime_copy" "$runtime_jar"
rm -f "$runtime_copy"
trap - EXIT
exec java "-Xmx${COSMIC_HEAP:-2048m}" -Dwz-path=wz -jar "$runtime_jar" "$@"
