#!/bin/sh
set -eu
umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
    exec "$JAVA_HOME/bin/java" --source 17 "$script_dir/signing/SigningBundle.java" "$script_dir/.." "$@"
fi
exec java --source 17 "$script_dir/signing/SigningBundle.java" "$script_dir/.." "$@"
