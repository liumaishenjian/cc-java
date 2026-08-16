#!/bin/sh
set -eu

VERSION="${CODEJ_VERSION:-}"
INSTALL_ROOT="${CODEJ_INSTALL_ROOT:-$HOME/.local/share/codej}"
BIN_DIR="${CODEJ_BIN_DIR:-$HOME/.local/bin}"
ARCHIVE_NAME="codej-linux-x64.tar.gz"
DOWNLOAD_BASE="${CODEJ_DOWNLOAD_BASE:-}"

if [ "${1:-}" = "--uninstall" ]; then
  if [ -f "$BIN_DIR/codej" ] && grep -q CODEJ_PUBLIC_INSTALL_SHIM "$BIN_DIR/codej"; then rm -f "$BIN_DIR/codej"; fi
  rm -rf "$INSTALL_ROOT"
  printf '%s\n' 'codej uninstalled; the PATH entry was left in place because it may contain other commands.'
  exit 0
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/codej-install.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT HUP INT TERM
ARCHIVE="$TMP/$ARCHIVE_NAME"
CHECKSUM="$ARCHIVE.sha256"
if [ -n "${CODEJ_ARCHIVE_PATH:-}" ]; then
  cp "$CODEJ_ARCHIVE_PATH" "$ARCHIVE"
  cp "$CODEJ_ARCHIVE_PATH.sha256" "$CHECKSUM"
else
  if [ -z "$DOWNLOAD_BASE" ]; then
    if [ -z "$VERSION" ]; then
      DOWNLOAD_BASE='https://github.com/liumaishenjian/cc-java/releases/latest/download'
    else
      DOWNLOAD_BASE="https://github.com/liumaishenjian/cc-java/releases/download/v$VERSION"
    fi
  fi
  curl -fL --retry 3 --proto '=https' --tlsv1.2 "$DOWNLOAD_BASE/$ARCHIVE_NAME" -o "$ARCHIVE"
  curl -fL --retry 3 --proto '=https' --tlsv1.2 "$DOWNLOAD_BASE/$ARCHIVE_NAME.sha256" -o "$CHECKSUM"
fi
(cd "$TMP" && sha256sum -c "$ARCHIVE_NAME.sha256")
mkdir "$TMP/expanded"
if tar -tzf "$ARCHIVE" | awk '
  /^\// { bad=1 }
  { n=split($0,p,"/"); for (i=1;i<=n;i++) if (p[i]=="..") bad=1 }
  END { exit bad ? 1 : 0 }
'; then :; else
  printf '%s\n' 'codej: archive entry escaped extraction root' >&2
  exit 1
fi
tar -xzf "$ARCHIVE" -C "$TMP/expanded"
MANIFEST="$TMP/expanded/release-manifest.json"
[ -f "$MANIFEST" ] || { printf '%s\n' 'codej: release manifest missing' >&2; exit 1; }
grep -q '"schema"[[:space:]]*:[[:space:]]*"cc-java-release-manifest-v1"' "$MANIFEST" || { printf '%s\n' 'codej: incompatible manifest schema' >&2; exit 1; }
grep -q '"platform"[[:space:]]*:[[:space:]]*"linux-x64"' "$MANIFEST" || { printf '%s\n' 'codej: incompatible manifest platform' >&2; exit 1; }
RELEASE_VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([0-9A-Za-z.-]*\)".*/\1/p' "$MANIFEST" | head -n 1)"
case "$RELEASE_VERSION" in ''|*[!0-9A-Za-z.-]*) printf '%s\n' 'codej: invalid release version' >&2; exit 1;; esac
if [ -n "$VERSION" ] && [ "$VERSION" != "$RELEASE_VERSION" ]; then
  printf '%s\n' 'codej: downloaded version does not match CODEJ_VERSION' >&2; exit 1
fi
VERSIONS="$INSTALL_ROOT/versions"
DESTINATION="$VERSIONS/$RELEASE_VERSION"
STAGING="$DESTINATION.staging.$$"
mkdir -p "$VERSIONS" "$BIN_DIR"
rm -rf "$STAGING"
mv "$TMP/expanded" "$STAGING"
rm -rf "$DESTINATION"
mv "$STAGING" "$DESTINATION"
chmod +x "$DESTINATION/codej"
printf '%s' "$RELEASE_VERSION" > "$INSTALL_ROOT/current.txt.tmp.$$"
mv "$INSTALL_ROOT/current.txt.tmp.$$" "$INSTALL_ROOT/current.txt"
cat > "$BIN_DIR/codej" <<'EOF'
#!/bin/sh
# CODEJ_PUBLIC_INSTALL_SHIM
set -eu
INSTALL_ROOT="${CODEJ_INSTALL_ROOT:-$HOME/.local/share/codej}"
VERSION="$(cat "$INSTALL_ROOT/current.txt")"
exec "$INSTALL_ROOT/versions/$VERSION/codej" "$@"
EOF
chmod +x "$BIN_DIR/codej"
printf 'codej %s installed. Ensure %s is on PATH, then run: codej\n' "$RELEASE_VERSION" "$BIN_DIR"
