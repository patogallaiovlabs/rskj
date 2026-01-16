#!/usr/bin/env bash
set -euo pipefail

IMAGE="rskj-local:latest"
NETWORK="rsknet"

CLEAN_BUILD=false     # builder prune + build --no-cache
WIPE_VOLUMES=false    # remove named volumes
NO_BUILD=false        # skip docker build step
MINERS=2

# Port bases
P2P_BASE=50500           # miner i -> 50500+i  (so miner1=50501)
HTTP_HOST_BASE=4443      # miner i -> 4443+i  (miner1=4444)
WS_HOST_BASE=4454        # miner i -> 4454+i  (miner1=4455)

# Container ports (what rskj listens on inside container)
HTTP_PORT_CONTAINER=4444
WS_PORT_CONTAINER=4445

usage() {
  cat <<EOF
Usage: $0 [options]

Options:
  --miners N       Number of miners to start (default: $MINERS)
  --clean-build    Prune Docker build cache + build with --no-cache
  --wipe-volumes   Remove miner volumes (DATA LOSS)
  --no-build       Skip docker build (reuse existing image)
  -h, --help       Show this help

Examples:
  $0
  $0 --miners 5
  $0 --miners 10 --no-build
  $0 --miners 3 --clean-build --wipe-volumes
EOF
}

# ----------------------------
# Parse arguments
# ----------------------------
while [ "${#@}" -gt 0 ]; do
  case "$1" in
    --clean-build)  CLEAN_BUILD=true; shift ;;
    --wipe-volumes) WIPE_VOLUMES=true; shift ;;
    --no-build)     NO_BUILD=true; shift ;;
    --miners)
      shift
      if [ "${1:-}" = "" ] || ! [[ "$1" =~ ^[0-9]+$ ]] || [ "$1" -lt 1 ]; then
        echo "Error: --miners requires a positive integer" >&2
        usage
        exit 1
      fi
      MINERS="$1"
      shift
      ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

# ----------------------------
# STOP any running instances first (hard reset for our miner names)
# ----------------------------
echo "Stopping any running miner containers (rskj-miner1..rskj-miner${MINERS})..."
for i in $(seq 1 "$MINERS"); do
  docker stop "rskj-miner$i" >/dev/null 2>&1 || true
  docker rm   "rskj-miner$i" >/dev/null 2>&1 || true
done

# ----------------------------
# Volumes
# ----------------------------
if [ "$WIPE_VOLUMES" = true ]; then
  echo "Removing miner volumes (data will be lost)..."
  for i in $(seq 1 "$MINERS"); do
    docker volume rm "rskj-data-miner$i" >/dev/null 2>&1 || true
  done
fi

# Ensure volumes exist
for i in $(seq 1 "$MINERS"); do
  docker volume create "rskj-data-miner$i" >/dev/null
done

# ----------------------------
# Optional build cache cleanup
# ----------------------------
if [ "$CLEAN_BUILD" = true ]; then
  echo "Cleaning Docker build cache..."
  docker builder prune -f >/dev/null || true
fi

# ----------------------------
# Build image
# ----------------------------
if [ "$NO_BUILD" = false ]; then
  if [ "$CLEAN_BUILD" = true ]; then
    echo "Building Docker image (no cache)..."
    docker build --no-cache -t "$IMAGE" .
  else
    echo "Building Docker image (using cache)..."
    docker build -t "$IMAGE" .
  fi
else
  echo "Skipping build (--no-build). Using existing image: $IMAGE"
fi

# ----------------------------
# Shared network
# ----------------------------
docker network create "$NETWORK" >/dev/null 2>&1 || true

# ----------------------------
# Resources (simulate limited machine)
# ----------------------------
CPUS="1.0"
MEM="4g"
MEMSWAP="4g"

echo "Starting $MINERS miners on network: $NETWORK"
echo "Port scheme:"
echo "  P2P:  host=container=50500+i"
echo "  HTTP: host=4443+i  -> container=4444"
echo "  WS:   host=4454+i  -> container=4445"
echo

# ----------------------------
# Run miners
# ----------------------------
for i in $(seq 1 "$MINERS"); do
  NAME="rskj-miner$i"
  VOL="rskj-data-miner$i"

  P2P_PORT=$((P2P_BASE + i))
  HTTP_HOST_PORT=$((HTTP_HOST_BASE + i))
  WS_HOST_PORT=$((WS_HOST_BASE + i))

  echo "Starting $NAME (MINER_ID=$i) ..."
  docker run -d \
    --name "$NAME" \
    --network "$NETWORK" \
    --cpus="$CPUS" \
    --memory="$MEM" \
    --memory-swap="$MEMSWAP" \
    -e MINER_ID="$i" \
    -v "$VOL":/var/lib/rsk \
    -p "${P2P_PORT}:${P2P_PORT}" \
    -p "${HTTP_HOST_PORT}:${HTTP_PORT_CONTAINER}" \
    -p "${WS_HOST_PORT}:${WS_PORT_CONTAINER}" \
    "$IMAGE"
done

# ----------------------------
# Summary
# ----------------------------
echo
echo "All miners are running on shared Docker network: $NETWORK"
echo
echo "Container-to-container (inside Docker):"
for i in $(seq 1 "$MINERS"); do
  P2P_PORT=$((P2P_BASE + i))
  echo "  rskj-miner$i:${P2P_PORT}"
done

echo
echo "From your Mac (host):"
for i in $(seq 1 "$MINERS"); do
  P2P_PORT=$((P2P_BASE + i))
  HTTP_HOST_PORT=$((HTTP_HOST_BASE + i))
  WS_HOST_PORT=$((WS_HOST_BASE + i))
  echo "Miner $i:"
  echo "  P2P  -> localhost:${P2P_PORT}"
  echo "  HTTP -> http://localhost:${HTTP_HOST_PORT}"
  echo "  WS   -> ws://localhost:${WS_HOST_PORT}"
done

echo
echo "Logs (example):"
echo "  docker logs -f rskj-miner1"
