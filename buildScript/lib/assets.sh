#!/bin/bash

set -eo pipefail

DIR=app/src/main/assets/sing-box

# Download into a temp dir first and only replace the assets once everything
# succeeded, so a failed download cannot leave an empty assets dir behind.
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"

get_latest_release() {
  # Unauthenticated API calls are rate-limited to 60/h per IP, which shared CI
  # runner IPs exhaust constantly — pass GITHUB_TOKEN when the workflow provides it.
  local auth=()
  [ -n "$GITHUB_TOKEN" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fLSs "${auth[@]}" "https://api.github.com/repos/$1/releases/latest" | # Get latest release from GitHub api
    grep '"tag_name":' |                                                          # Get tag line
    sed -E 's/.*"([^"]+)".*/\1/'                                                  # Pluck JSON value
}

# Both repos publish a "<file>.sha256sum" next to the db. Without it a truncated
# or tampered download is xz-compressed straight into the APK and only surfaces
# at runtime as a sing-box asset-load failure.
download_verified() {
  local repo="$1" version="$2" file="$3"
  local base="https://github.com/$repo/releases/download/$version"
  # one invocation for both so curl reuses the connection
  curl -fLSs -o "$file" "$base/$file" -o "$file.sha256sum" "$base/$file.sha256sum"
  # shasum, not sha256sum: the dev machines are macOS (same as plugins.sh)
  local expect=$(awk '{print $1}' "$file.sha256sum")
  [ -n "$expect" ] || { echo "no sha256sum published for $file"; exit 1; }
  local actual=$(shasum -a 256 "$file" | awk '{print $1}')
  [ "$actual" = "$expect" ] || { echo "sha256 mismatch for $file: $actual != $expect"; exit 1; }
  # everything left in $TMP is moved into the assets dir, so drop the checksum
  rm -f "$file.sha256sum"
}

####
VERSION_GEOIP=`get_latest_release "SagerNet/sing-geoip"`
[ -n "$VERSION_GEOIP" ] || { echo "failed to resolve latest sing-geoip release"; exit 1; }
echo VERSION_GEOIP=$VERSION_GEOIP
echo -n $VERSION_GEOIP > geoip.version.txt
download_verified "SagerNet/sing-geoip" "$VERSION_GEOIP" geoip.db
xz -9 geoip.db

####
VERSION_GEOSITE=`get_latest_release "SagerNet/sing-geosite"`
[ -n "$VERSION_GEOSITE" ] || { echo "failed to resolve latest sing-geosite release"; exit 1; }
echo VERSION_GEOSITE=$VERSION_GEOSITE
echo -n $VERSION_GEOSITE > geosite.version.txt
download_verified "SagerNet/sing-geosite" "$VERSION_GEOSITE" geosite.db
xz -9 geosite.db

####
cd "$OLDPWD"
bash buildScript/lib/replace-assets.sh "$TMP" "$DIR"
