#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -x "$ROOT/runtime/node/bin/node" ]; then
  NODE="$ROOT/runtime/node/bin/node"
elif [ -x "$ROOT/runtime/node/node" ]; then
  NODE="$ROOT/runtime/node/node"
else
  NODE="node"
fi
if [ -x "$ROOT/runtime/java/bin/java" ]; then
  CODEJ_JAVA="$ROOT/runtime/java/bin/java"
elif [ -n "${JAVA_HOME:-}" ]; then
  CODEJ_JAVA="$JAVA_HOME/bin/java"
else
  CODEJ_JAVA="java"
fi
export CODEJ_JAVA
exec "$NODE" "$ROOT/codej-launcher.mjs" "$@"
