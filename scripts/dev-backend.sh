#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null) || JAVA_HOME=
    if [ -n "$JAVA_HOME" ]; then export JAVA_HOME; fi
fi

if [ -r "$repo_dir/.env" ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            TRACKER_DB_PATH=*|TRACKER_TIME_ZONE=*|TRACKER_BACKUP_ROOT=*)
                key=${line%%=*}
                if ! printenv "$key" >/dev/null 2>&1; then
                    export "$line"
                fi
                ;;
        esac
    done < "$repo_dir/.env"
fi

cd "$repo_dir/backend"
if [ -x "$repo_dir/.tools/maven/bin/mvn" ]; then
    exec "$repo_dir/.tools/maven/bin/mvn" "-Dmaven.repo.local=$repo_dir/.tools/maven-repository" spring-boot:run "$@"
fi
if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven is missing. Run ./scripts/setup-local.sh from the repository root." >&2
    exit 1
fi
exec mvn spring-boot:run "$@"
