#!/bin/sh
set -eu
umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec java --source 17 "$script_dir/signing/SigningBundle.java" "$script_dir/.." "$@"
