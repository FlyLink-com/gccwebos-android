#!/usr/bin/env sh
# First-party bootstrap; retrieves only the checksum-pinned official Gradle wrapper.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JAR="$ROOT/gradle/wrapper/gradle-wrapper.jar"
EXPECTED=55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c
URL=https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar
hash_file() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
    else echo 'A SHA-256 verifier is required (sha256sum/shasum).' >&2; exit 1; fi
}
if [ -f "$JAR" ]; then
    [ "$(hash_file "$JAR")" = "$EXPECTED" ] || { echo 'Wrapper checksum mismatch. Refusing to execute or overwrite.' >&2; exit 1; }
    exit 0
fi
mkdir -p "$(dirname -- "$JAR")"
TEMP=$(mktemp "${JAR}.XXXXXXXX.tmp")
trap 'rm -f "$TEMP"' EXIT HUP INT TERM
printf '%s\n' 'Fetching Gradle 9.4.1 wrapper JAR from the official repository...'
if command -v curl >/dev/null 2>&1; then
    curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 90 --retry 1 --output "$TEMP" "$URL"
else
    echo 'curl is required for first-time wrapper bootstrap. No files were executed.' >&2
    exit 1
fi
[ "$(hash_file "$TEMP")" = "$EXPECTED" ] || { echo 'Downloaded wrapper checksum mismatch. Nothing will be executed.' >&2; exit 1; }
mv "$TEMP" "$JAR"
printf '%s\n' 'Official wrapper JAR SHA-256 verified.'
