#!/bin/sh
set -eu

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    echo "Usage: $0 /path/to/existing/tracker.sqlite" >&2
    exit 2
fi

source_dir=$(cd "$(dirname "$1")" && pwd -P)
source_name=$(basename "$1")
repo_dir=$(cd "$(dirname "$0")/.." && pwd -P)
cd "$repo_dir"

if [ -n "$(docker compose ps --status running -q backend)" ]; then
    echo "Stop the Compose backend before migrating." >&2
    exit 1
fi

docker compose run --rm --no-deps --user 0:0 \
    --volume "$source_dir:/migration-source:ro" \
    -e "MIGRATION_FILENAME=$source_name" --entrypoint /bin/sh backend -ec '
    target=$TRACKER_DB_PATH
    for suffix in "" -wal -shm -journal; do
        if [ -e "$target$suffix" ] || [ -L "$target$suffix" ]; then
            echo "Docker storage already contains a database or a sidecar. Migration stopped." >&2
            exit 1
        fi
    done

    staged=$(mktemp /data/.migration.XXXXXX)
    trap "rm -f \"\$staged\"" EXIT
    sqlite3 -bail -readonly "/migration-source/$MIGRATION_FILENAME" ".backup \"$staged\""
    [ "$(sqlite3 -bail -readonly "$staged" "PRAGMA integrity_check;")" = ok ]
    violations=$(sqlite3 -bail -readonly "$staged" "PRAGMA foreign_key_check;")
    [ -z "$violations" ]
    [ "$(sqlite3 -bail -readonly "$staged" "SELECT count(*) FROM sqlite_master WHERE type = '\''table'\'' AND name IN ('\''flyway_schema_history'\'', '\''problems'\'');")" = 2 ]
    chown 10001:10001 "$staged"
    chmod 600 "$staged"
    ln -T "$staged" "$target"
    echo "Tracker snapshot installed in Docker storage."
'
