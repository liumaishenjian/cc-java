#!/bin/sh
set -eu
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec "$JAVA" -cp "$ROOT/app/*" io.github.liumaishenjian.ccjava.cli.CcJavaCliMain "$@"
