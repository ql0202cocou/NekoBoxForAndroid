#!/bin/bash

set -eo pipefail

source buildScript/init/verify_sha256.sh

# Pinned upstream releases and sha256 of the downloaded (uncompressed) db
# files, frozen 2026-09-15; bump all four constants together. Upstream
# publishes a "<file>.sha256sum" next to each db, but it sits in the same
# release as the asset it describes, so these pinned values are what actually
# guard against a release asset being swapped under an unchanged tag.
GEOIP_VERSION=20260912
GEOIP_SHA256=9804e7b95787db24831af6471a638b4bf9c4d4f3a94cc9c12993abada20e6a5f
GEOSITE_VERSION=20260914091725
GEOSITE_SHA256=9c0109bd11a25e1053cabf951264593815974e05e435f2af2830871688831f92

DIR=app/src/main/assets/sing-box

# Download into a temp dir first and only replace the assets once everything
# succeeded, so a failed download cannot leave an empty assets dir behind.
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"

# The db is verified against the pinned sha256 above; without that check a
# truncated or tampered download is xz-compressed straight into the APK and only
# surfaces at runtime as a sing-box asset-load failure.
download_verified() {
  local repo="$1" version="$2" file="$3" expect="$4"
  curl -fLSs -o "$file" "https://github.com/$repo/releases/download/$version/$file"
  verify_sha256 "$file" "$expect" || exit 1
}

####
echo VERSION_GEOIP=$GEOIP_VERSION
echo -n $GEOIP_VERSION > geoip.version.txt
download_verified "SagerNet/sing-geoip" "$GEOIP_VERSION" geoip.db "$GEOIP_SHA256"
xz -9 geoip.db

####
echo VERSION_GEOSITE=$GEOSITE_VERSION
echo -n $GEOSITE_VERSION > geosite.version.txt
download_verified "SagerNet/sing-geosite" "$GEOSITE_VERSION" geosite.db "$GEOSITE_SHA256"
xz -9 geosite.db

####
cd "$OLDPWD"
bash buildScript/lib/replace-assets.sh "$TMP" "$DIR"
