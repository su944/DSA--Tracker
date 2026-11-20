#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tools_dir="$repo_dir/.tools"
node_bin="$tools_dir/node/node_modules/.bin"
maven_version=3.9.16
maven_checksum=ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3
archive_name="apache-maven-$maven_version-bin.zip"

mkdir -p "$tools_dir"
if [ ! -x "$node_bin/node" ] || [ ! -x "$node_bin/npm" ]; then
    if ! command -v npm >/dev/null 2>&1; then
        echo "Use an existing Node.js and npm installation once to bootstrap the project-local tools." >&2
        exit 1
    fi
    npm install --prefix "$tools_dir/node" --save-exact --no-audit --no-fund node@24.22.3 npm@11.21.3
fi

verify_maven() {
    actual=$(shasum -a 512 "$1")
    [ "${actual%% *}" = "$maven_checksum" ]
}

if [ ! -x "$tools_dir/maven/bin/mvn" ]; then
    if [ -e "$tools_dir/maven" ]; then
        echo "Move the incomplete .tools/maven directory aside and rerun setup." >&2
        exit 1
    fi
    scratch=$(mktemp -d "$tools_dir/.maven.XXXXXX")
    trap 'rm -rf -- "$scratch"' EXIT
    cached=""
    if [ -d "$HOME/.m2/wrapper/dists" ]; then
        cached=$(find "$HOME/.m2/wrapper/dists" -name "$archive_name" -type f -print -quit)
    fi
    if [ -n "$cached" ] && verify_maven "$cached"; then
        archive="$cached"
    else
        archive="$scratch/$archive_name"
        curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$maven_version/$archive_name" -o "$archive"
        if ! verify_maven "$archive"; then
            echo "Maven archive checksum does not match. Setup stopped." >&2
            exit 1
        fi
    fi
    unzip -q "$archive" -d "$scratch"
    mv "$scratch/apache-maven-$maven_version" "$tools_dir/maven"
fi

echo "Project-local Node $("$node_bin/node" --version) and Maven $maven_version are ready."
echo "Start ./scripts/dev-backend.sh and ./scripts/dev-frontend.sh in separate terminals."
