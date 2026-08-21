#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

easylist_url="https://easylist.to/easylist/easylist.txt"
china_url="https://easylist-downloads.adblockplus.org/easylistchina.txt"
curl --fail --location --silent --show-error "$easylist_url" --output "$temp_dir/easylist.txt"
curl --fail --location --silent --show-error "$china_url" --output "$temp_dir/easylistchina.txt"

cosmetic_count="$(awk 'index($0,"##") || index($0,"#@#") { n++ } END { print n + 0 }' "$temp_dir/easylist.txt" "$temp_dir/easylistchina.txt")"
if [ "$cosmetic_count" -lt 20000 ]; then
    echo "refusing suspicious EasyList snapshots with only $cosmetic_count cosmetic rules" >&2
    exit 1
fi

gzip -9 -n -c "$temp_dir/easylist.txt" > app/src/main/assets/adblock/easylist.dat
gzip -9 -n -c "$temp_dir/easylistchina.txt" > app/src/main/assets/adblock/easylistchina.dat
sha256sum "$temp_dir/easylist.txt" "$temp_dir/easylistchina.txt"
echo "updated EasyList assets ($cosmetic_count cosmetic-like rules)"
