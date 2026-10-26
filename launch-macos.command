#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/macos/common.sh"
cd "$COSMIC_ROOT"
./macos/database.sh start
export DB_URL="jdbc:mysql://127.0.0.1:$COSMIC_DB_PORT/cosmic"
export DB_USER=root
export DB_PASS=''
echo "Starting Cosmic. Wait for 'Cosmic is now online', then open the client."
echo "Press Control-C to stop the game server."
exec ./launch.sh
