#!/usr/bin/env bash
# Builds the routing dataset image (ADR 0147, decision 5): Geofabrik's Uzbekistan extract,
# preprocessed for OSRM's MLD algorithm with the car profile, packaged as an image tagged
# with the extract's date.
#
#   deploy/routing/build-dataset.sh [--gate sample.csv] [--push] [--tag 2026-10-01]
#                                   [--pbf path/to/extract.osm.pbf]
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
#
# --gate runs ADR 0147's accuracy gate (platform/tools/routing/accuracy_gate.py) against the
# freshly built files, after the image is built and BEFORE anything is pushed: a map that
# fails the gate (median deviation over 10%, 95th percentile over 25%, over a reference
# sample of 100 trips of 1 to 10 km) never reaches the registry. The script then exits
# non-zero with the gate's own report above it.
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
gate=""
while [ $# -gt 0 ]; do
  case "$1" in
    --push) push=true ;;
    --tag) tag="$2"; shift ;;
    --pbf) pbf="$2"; shift ;;
    --gate) gate="$2"; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
engine_container=""
cleanup() {
  [ -z "$engine_container" ] || docker rm -f "$engine_container" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

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

if [ -n "$gate" ]; then
  echo "== accuracy gate ($gate)"
  engine_container="$(docker run -d -p 127.0.0.1::5000 -v "$work/context/dataset:/data:ro" "$ENGINE_IMAGE" \
    osrm-routed --algorithm mld --ip 0.0.0.0 --port 5000 "/data/$DATASET_NAME.osrm")"
  port="$(docker port "$engine_container" 5000/tcp | head -1 | sed 's/.*://')"
  ready=false
  for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null "http://127.0.0.1:$port/route/v1/driving/69.2401,41.3111;69.2641,41.3309?overview=false"; then
      ready=true
      break
    fi
    sleep 2
  done
  [ "$ready" = true ] || { echo "the engine did not come up on the new dataset" >&2; docker logs "$engine_container" >&2 || true; exit 1; }
  # Under set -e a non-zero exit here (1: the gate does not hold, 2: the sample or the
  # engine is unusable) stops the script before the push below.
  python3 "$here/../../platform/tools/routing/accuracy_gate.py" "$gate" --engine "http://127.0.0.1:$port"
fi

if [ "$push" = true ]; then
  echo "== pushing $image"
  docker push "$image"
fi
echo "done: $image"
