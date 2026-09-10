#!/bin/bash

# Stage on the destination filesystem, so publishing uses directory renames
# rather than a cross-filesystem copy after the previous assets were removed.
set -e

SOURCE=$1
DESTINATION=$2
mkdir -p "$(dirname "$DESTINATION")"
STAGING=$(mktemp -d "$(dirname "$DESTINATION")/.asset-replacement.XXXXXX")

cleanup() {
  local status=$?
  if [ -d "$STAGING/previous" ] && [ ! -e "$DESTINATION" ]; then
    if ! mv "$STAGING/previous" "$DESTINATION"; then
      echo "failed to restore assets; previous files retained at $STAGING/previous" >&2
      # Never delete the only surviving copy when rollback itself fails.
      exit 1
    fi
  fi
  rm -rf "$STAGING"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

mkdir "$STAGING/new"
cp -R "$SOURCE/." "$STAGING/new/"
if [ -e "$DESTINATION" ]; then
  mv "$DESTINATION" "$STAGING/previous"
fi
mv "$STAGING/new" "$DESTINATION"
