#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/macos/common.sh"
if [ "$(uname -s)" != Darwin ]; then
    echo "This setup script is for macOS." >&2
    exit 1
fi
require_brew
brew install openjdk@21 mysql@8.4
use_java21
cd "$COSMIC_ROOT"
./mvnw -B -ntp package
./macos/database.sh start
echo "Setup complete. Double-click launch-macos.command to start Cosmic."
