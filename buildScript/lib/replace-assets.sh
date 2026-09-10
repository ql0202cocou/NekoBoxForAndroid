#!/bin/bash

# Stage on the destination filesystem, so publishing uses directory renames
# rather than a cross-filesystem copy after the previous assets were removed.
set -e

SOURCE=$1
DESTINATION=$2
PARENT=$(dirname "$DESTINATION")
mkdir -p "$PARENT"

# Two builds replacing the same directory would interleave the renames below and
# end up with "new/" nested inside the destination. mkdir is atomic on every
# runner; a lock whose recorded pid is gone is stale (SIGKILL) and reclaimed.
LOCK="$PARENT/.asset-replacement.lock"
for _ in $(seq 1 600); do
  if mkdir "$LOCK" 2>/dev/null; then
    echo $$ > "$LOCK/pid"
    break
  fi
  owner=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$owner" ] && ! kill -0 "$owner" 2>/dev/null; then
    rm -rf "$LOCK"
    continue
  fi
  sleep 1
done
[ "$(cat "$LOCK/pid" 2>/dev/null)" = "$$" ] || { echo "asset replacement lock held by another build" >&2; exit 1; }

# A previous run killed between its two renames (SIGKILL, power loss) leaves the
# destination missing and the old assets in its staging dir: put them back before
# doing anything else, then drop every stale staging dir.
for stale in "$PARENT"/.asset-replacement.*; do
  [ -d "$stale" ] && [ "$stale" != "$LOCK" ] || continue
  if [ -d "$stale/previous" ] && [ ! -e "$DESTINATION" ]; then
    mv "$stale/previous" "$DESTINATION"
  fi
  rm -rf "$stale"
done

STAGING=$(mktemp -d "$PARENT/.asset-replacement.XXXXXX")

cleanup() {
  local status=$?
  if [ -d "$STAGING/previous" ] && [ ! -e "$DESTINATION" ]; then
    if ! mv "$STAGING/previous" "$DESTINATION"; then
      echo "failed to restore assets; previous files retained at $STAGING/previous" >&2
      # Never delete the only surviving copy when rollback itself fails.
      rm -rf "$LOCK"
      exit 1
    fi
  fi
  rm -rf "$STAGING" "$LOCK"
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
