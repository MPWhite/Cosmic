#!/bin/bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
require_brew
MYSQL_BIN="$(brew --prefix mysql@8.4)/bin"
STATE="$COSMIC_ROOT/.local"
SOCKET="$STATE/mysql.sock"

if [ ! -x "$MYSQL_BIN/mysqld" ]; then
    echo "MySQL 8.4 is missing. Run ./setup-macos.sh first." >&2
    exit 1
fi

mysql_admin() {
    "$MYSQL_BIN/mysqladmin" --no-defaults --protocol=SOCKET --socket="$SOCKET" --user=root "$@"
}

case "${1:-start}" in
    start)
        mkdir -p "$STATE"
        if mysql_admin ping >/dev/null 2>&1; then
            actual_port="$("$MYSQL_BIN/mysql" --no-defaults --protocol=SOCKET --socket="$SOCKET" --user=root --batch --skip-column-names --execute='SELECT @@port;')"
            if [ "$actual_port" != "$COSMIC_DB_PORT" ]; then
                echo "Cosmic's database is running on port $actual_port, but COSMIC_DB_PORT is $COSMIC_DB_PORT." >&2
                echo "Use the running port, or stop the database before changing its port." >&2
                exit 1
            fi
            echo "Cosmic's database is already running (socket: $SOCKET)."
            exit 0
        fi
        if nc -z 127.0.0.1 "$COSMIC_DB_PORT" 2>/dev/null; then
            echo "Port $COSMIC_DB_PORT is in use. Set COSMIC_DB_PORT to another port." >&2
            exit 1
        fi
        if [ ! -d "$STATE/mysql/mysql" ]; then
            echo "Initializing Cosmic's local database..."
            "$MYSQL_BIN/mysqld" --no-defaults --initialize-insecure \
                --datadir="$STATE/mysql" --log-error="$STATE/mysql.log"
        fi
        "$MYSQL_BIN/mysqld" --no-defaults --datadir="$STATE/mysql" \
            --socket="$SOCKET" --pid-file="$STATE/mysql.pid" \
            --port="$COSMIC_DB_PORT" --bind-address=127.0.0.1 --mysqlx=OFF \
            --log-error="$STATE/mysql.log" --daemonize
        for ((attempt = 0; attempt < 30; attempt++)); do
            if mysql_admin ping >/dev/null 2>&1; then
                "$MYSQL_BIN/mysql" --no-defaults --protocol=SOCKET --socket="$SOCKET" --user=root \
                    --execute='CREATE DATABASE IF NOT EXISTS cosmic CHARACTER SET utf8mb4;'
                echo "Cosmic's database is ready on 127.0.0.1:$COSMIC_DB_PORT."
                exit 0
            fi
            sleep 1
        done
        echo "Database startup failed. See $STATE/mysql.log." >&2
        exit 1
        ;;
    stop)
        if mysql_admin ping >/dev/null 2>&1; then
            mysql_admin shutdown
            echo "Cosmic's database stopped. Saved accounts and characters are preserved."
        else
            echo "Cosmic's database is not running."
        fi
        ;;
    status) mysql_admin ping ;;
    *) echo "Usage: $0 {start|stop|status}" >&2; exit 2 ;;
esac
