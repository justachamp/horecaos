#!/usr/bin/env bash
# Builds the routing dataset image (ADR 0147, decision 5): Geofabrik's Uzbekistan extract,
# preprocessed for OSRM's MLD algorithm with the car profile, packaged as an image tagged
# with the extract's date.
#
#   deploy/routing/build-dataset.sh [--push] [--tag 2026-10-01] [--pbf path/to/extract.osm.pbf]
#
# Runs on a workstation or a CI runner, never on the production box: ADR 0061's rule is
# that nothing is built on the server. Needs Docker and nothing else; the engine's own
# tools run from the pinned engine image, so the files are produced by exactly the
# version that will read them (an .osrm file set built by one OSRM release is not
# guaranteed to load in another).
#
# Without --push the image stays local, which is how the runbook measures the engine's
# memory before anything is published. With --push it is pushed to
# ${HORECAOS_REGISTRY}/horecaos-routing-dataset:<tag>, and the registry login is the
# caller's (CI's `docker/login-action`, or `docker login` by hand).
set -euo pipefail

# The index digest of ghcr.io/project-osrm/osrm-backend:v6.0.0, the same pin
# deploy/compose.production.yml runs. Keep the two identical.
ENGINE_IMAGE="ghcr.io/project-osrm/osrm-backend:v6.0.0@sha256:729461bcc9ae9e6aafa92c0f93db9b060a32e85d5e72092c01ae4a4a9f1eb564"
EXTRACT_URL="https://download.geofabrik.de/asia/uzbekistan-latest.osm.pbf"
DATASET_NAME="uzbekistan-latest"
REGISTRY="${HORECAOS_REGISTRY:-ghcr.io/justachamp/horecaos}"

push=false
tag=""
pbf=""
while [ $# -gt 0 ]; do
  case "$1" in
    --push) push=true ;;
    --tag) tag="$2"; shift ;;
    --pbf) pbf="$2"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/data"
extract_date=""
if [ -z "$pbf" ]; then
  echo "== fetching the extract"
  curl -fsSL -D "$work/headers.txt" -o "$work/data/$DATASET_NAME.osm.pbf" "$EXTRACT_URL"
  # Geofabrik publishes a checksum beside every extract. A truncated download is a
  # routing graph with holes in it, and it still loads.
  curl -fsSL -o "$work/data/$DATASET_NAME.osm.pbf.md5" "$EXTRACT_URL.md5"
  python3 - "$work/data/$DATASET_NAME.osm.pbf" "$work/data/$DATASET_NAME.osm.pbf.md5" <<'PY'
import hashlib, sys
pbf, checksum = sys.argv[1], sys.argv[2]
expected = open(checksum).read().split()[0]
digest = hashlib.md5()
with open(pbf, "rb") as handle:
    for block in iter(lambda: handle.read(1 << 20), b""):
        digest.update(block)
if digest.hexdigest() != expected:
    sys.exit(f"checksum mismatch: {digest.hexdigest()} != {expected}")
print("   checksum ok")
PY
  # The extract's own date, from the server's Last-Modified: a dataset built today from
  # last week's extract is last week's map, and its tag must say so.
  extract_date="$(python3 - "$work/headers.txt" <<'PY'
import email.utils, sys
# The last one: with -L every redirect hop writes its own header block, and the extract's
# date is the final response's.
found = None
for line in open(sys.argv[1], encoding="latin-1"):
    if line.lower().startswith("last-modified:"):
        found = email.utils.parsedate_to_datetime(line.split(":", 1)[1].strip()).strftime("%Y-%m-%d")
if found:
    print(found)
PY
)"
else
  cp "$pbf" "$work/data/$DATASET_NAME.osm.pbf"
  extract_date="$(python3 -c 'import os, sys, datetime; print(datetime.datetime.fromtimestamp(os.path.getmtime(sys.argv[1]), datetime.timezone.utc).strftime("%Y-%m-%d"))' "$pbf")"
fi

if [ -z "$tag" ]; then
  tag="$extract_date"
fi
[ -n "$tag" ] || { echo "could not work out the extract's date; pass --tag" >&2; exit 1; }
echo "== dataset tag: $tag"

echo "== osrm-extract (car profile)"
docker run --rm -v "$work/data:/data" "$ENGINE_IMAGE" \
  osrm-extract -p /opt/car.lua "/data/$DATASET_NAME.osm.pbf"
echo "== osrm-partition"
docker run --rm -v "$work/data:/data" "$ENGINE_IMAGE" osrm-partition "/data/$DATASET_NAME.osrm"
echo "== osrm-customize"
docker run --rm -v "$work/data:/data" "$ENGINE_IMAGE" osrm-customize "/data/$DATASET_NAME.osrm"

# Only the files the engine reads at run time go into the image; the extract and its
# checksum stay behind.
mkdir -p "$work/context/dataset"
cp "$work/data/$DATASET_NAME.osrm"* "$work/context/dataset/"
cat > "$work/context/dataset/BUILD-INFO.txt" <<INFO
dataset:   $tag
extract:   $EXTRACT_URL
engine:    $ENGINE_IMAGE
algorithm: mld
profile:   car
built:     $(date -u +%Y-%m-%dT%H:%M:%SZ)
INFO
cp "$here/Dockerfile.dataset" "$work/context/Dockerfile"

image="$REGISTRY/horecaos-routing-dataset:$tag"
echo "== building $image"
docker build -t "$image" "$work/context"

if [ "$push" = true ]; then
  echo "== pushing $image"
  docker push "$image"
fi
echo "done: $image"
