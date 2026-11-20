#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
local_bin="$repo_dir/.tools/node/node_modules/.bin"
if [ -x "$local_bin/node" ] && [ -x "$local_bin/npm" ]; then
    export PATH="$local_bin:$PATH"
fi
if ! command -v npm >/dev/null 2>&1 || ! node -e '
    const [major, minor] = process.versions.node.split(".").map(Number)
    process.exit(major >= 24 || (major === 22 && minor >= 12) ? 0 : 1)
'; then
    echo "Use Node.js 24 or run ./scripts/setup-local.sh from the repository root." >&2
    exit 1
fi

cd "$repo_dir/frontend"
if [ ! -x node_modules/.bin/vite ]; then
    npm ci --no-audit --no-fund
fi
exec npm run dev -- "$@"
