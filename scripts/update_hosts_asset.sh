#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

commit="${1:-4731c9c341b13b9a4c8282a02eb551ab76090811}"
source_url="https://raw.githubusercontent.com/StevenBlack/hosts/${commit}/hosts"
asset="app/src/main/assets/adblock/stevenblack-hosts.dat"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT

curl --fail --location --silent --show-error "$source_url" --output "$temp_dir/hosts"
entries="$(awk '$1 == "0.0.0.0" || $1 == "127.0.0.1" || $1 == "::" { n += NF - 1 } END { print n + 0 }' "$temp_dir/hosts")"
if [ "$entries" -lt 50000 ]; then
    echo "refusing suspicious hosts snapshot with only $entries entries" >&2
    exit 1
fi

gzip -9 -n -c "$temp_dir/hosts" > "$asset"
echo "updated $asset from $commit ($entries host entries)"
