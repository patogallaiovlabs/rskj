#!/usr/bin/env bash
set -euo pipefail

IMAGE="rskj-local:latest"
NETWORK="rsknet"

CLEAN_BUILD=false
WIPE_VOLUMES=false
NO_BUILD=false
ONLY_STOP=false
ROLLING=false
MINERS=2
NODES=0

ENABLE_JMX=false
JMX_BASE=9100
JMX_HOSTNAME="127.0.0.1"

usage() {
  cat <<EOF
Usage: $0 [options]

Options:
  --miners N         Number of miners (default: $MINERS)
  --nodes N          Number of regular nodes (default: $NODES)
  --clean-build      Prune build cache + build with --no-cache
  --wipe-volumes     Remove miner/node volumes (DATA LOSS)
  --no-build         Skip docker build
  --stop             Only stop and remove containers
  --rolling          Rolling deploy: stop/start one by one
  --enable-jmx       Expose JMX for VisualVM/JConsole
  --jmx-base PORT    Base JMX port (default: $JMX_BASE). Miner i uses (PORT+i)
  --jmx-hostname H   java.rmi.server.hostname (default: $JMX_HOSTNAME)
  -h, --help         Show help
EOF
}

while [ "${#@}" -gt 0 ]; do
  case "$1" in
    --clean-build)  CLEAN_BUILD=true; shift ;;
    --wipe-volumes) WIPE_VOLUMES=true; shift ;;
    --no-build)     NO_BUILD=true; shift ;;
    --enable-jmx)   ENABLE_JMX=true; shift ;;
    --miners)
      shift
      if [ -z "${1:-}" ] || ! [[ "$1" =~ ^[0-9]+$ ]] || [ "$1" -lt 1 ]; then
        echo "Error: --miners requires a positive integer" >&2
        usage; exit 1
      fi
      MINERS="$1"; shift ;;
    --nodes)
      shift
      if [ -z "${1:-}" ] || ! [[ "$1" =~ ^[0-9]+$ ]]; then
        echo "Error: --nodes requires a non-negative integer" >&2
        usage; exit 1
      fi
      NODES="$1"; shift ;;
    --stop)         ONLY_STOP=true; shift ;;
    --rolling)      ROLLING=true; shift ;;
    --jmx-base)
      shift
      if [ -z "${1:-}" ] || ! [[ "$1" =~ ^[0-9]+$ ]] || [ "$1" -lt 1 ]; then
        echo "Error: --jmx-base requires a positive integer port" >&2
        usage; exit 1
      fi
      JMX_BASE="$1"; shift ;;
    --jmx-hostname)
      shift
      if [ -z "${1:-}" ]; then
        echo "Error: --jmx-hostname requires a value" >&2
        usage; exit 1
      fi
      JMX_HOSTNAME="$1"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [ "$ROLLING" = false ]; then
  echo "Stopping any running rskj containers..."
  for name in $(docker ps -a --format '{{.Names}}' | grep -E '^rskj-(miner|node)[0-9]+$' || true); do
    docker stop "$name" >/dev/null 2>&1 || true
    docker rm   "$name" >/dev/null 2>&1 || true
  done
else
  echo "Rolling deploy: removing excess containers..."
  for name in $(docker ps -a --format '{{.Names}}' | grep -E '^rskj-miner[0-9]+$' || true); do
    idx=$(echo "$name" | sed 's/rskj-miner//')
    if [ "$idx" -gt "$MINERS" ]; then
      echo "  Stopping excess miner: $name"
      docker stop "$name" >/dev/null 2>&1 || true
      docker rm   "$name" >/dev/null 2>&1 || true
    fi
  done
  for name in $(docker ps -a --format '{{.Names}}' | grep -E '^rskj-node[0-9]+$' || true); do
    idx=$(echo "$name" | sed 's/rskj-node//')
    if [ "$idx" -gt "$NODES" ]; then
      echo "  Stopping excess node: $name"
      docker stop "$name" >/dev/null 2>&1 || true
      docker rm   "$name" >/dev/null 2>&1 || true
    fi
  done
fi

if [ "$ONLY_STOP" = true ]; then
  echo "Stopped all rskj containers. Exiting."
  exit 0
fi

if [ "$WIPE_VOLUMES" = true ]; then
  echo "Removing rskj volumes..."
  for vol in $(docker volume ls -q | grep -E '^rskj-data-(miner|node)[0-9]+$' || true); do
    docker volume rm "$vol" >/dev/null 2>&1 || true
  done
fi

for i in $(seq 1 "$MINERS"); do
  docker volume create "rskj-data-miner$i" >/dev/null
done
for i in $(seq 1 "$NODES"); do
  docker volume create "rskj-data-node$i" >/dev/null
done

if [ "$CLEAN_BUILD" = true ]; then
  echo "Cleaning Docker build cache..."
  docker builder prune -f >/dev/null || true
fi

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

docker network create "$NETWORK" >/dev/null 2>&1 || true

CPUS="2.0"
MEM="8g"
MEMSWAP="8g"

HTTP_PORT_CONTAINER=4444
WS_PORT_CONTAINER=4445

# Host RPC ports (stable scheme)
HTTP_HOST_MINER_BASE=4443   # miner1=4444
WS_HOST_MINER_BASE=4454     # miner1=4455

HTTP_HOST_NODE_BASE=4463    # node1=4464
WS_HOST_NODE_BASE=4474      # node1=4475

start_node() {
  local i=$1
  local type=$2 # "miner" or "node"
  local is_miner=$3 # "true" or "false"

  local NAME="rskj-$type$i"
  local VOL="rskj-data-$type$i"

  local P2P_PORT
  local HTTP_HOST_PORT
  local WS_HOST_PORT

  if [ "$is_miner" = "true" ]; then
    P2P_PORT="5050${i}"
    HTTP_HOST_PORT=$((HTTP_HOST_MINER_BASE + i))
    WS_HOST_PORT=$((WS_HOST_MINER_BASE + i))
  else
    P2P_PORT="5060${i}"
    HTTP_HOST_PORT=$((HTTP_HOST_NODE_BASE + i))
    WS_HOST_PORT=$((WS_HOST_NODE_BASE + i))
  fi

  # JMX port (stable scheme)
  # Miner i: JMX_BASE + i
  # Node i: JMX_BASE + 100 + i (just to avoid conflict)
  local JMX_PORT
  if [ "$is_miner" = "true" ]; then
    JMX_PORT=$((JMX_BASE + i))
  else
    JMX_PORT=$((JMX_BASE + 100 + i))
  fi

  if [ "$ROLLING" = true ]; then
    if docker ps -a --format '{{.Names}}' | grep -q "^$NAME$"; then
      echo "Stopping $NAME for rolling deploy..."
      docker stop "$NAME" >/dev/null 2>&1 || true
      docker rm   "$NAME" >/dev/null 2>&1 || true
    fi
  fi

  echo "Starting $NAME (MINER_ID=$i, IS_MINER=$is_miner) P2P=$P2P_PORT HTTP=$HTTP_HOST_PORT WS=$WS_HOST_PORT" \
       "$( [ "$ENABLE_JMX" = true ] && echo "JMX=$JMX_PORT" )"

  # Build env args
  local ENV_ARGS=(-e "MINER_ID=$i" -e "IS_MINER=$is_miner")
  if [ "$ENABLE_JMX" = true ]; then
    ENV_ARGS+=(-e "ENABLE_JMX=true" -e "JMX_PORT=$JMX_PORT" -e "JMX_HOSTNAME=$JMX_HOSTNAME")
  fi

  # Build port args
  local PORT_ARGS=(-p "${P2P_PORT}:${P2P_PORT}" -p "${HTTP_HOST_PORT}:${HTTP_PORT_CONTAINER}" -p "${WS_HOST_PORT}:${WS_PORT_CONTAINER}")
  if [ "$ENABLE_JMX" = true ]; then
    PORT_ARGS+=(-p "${JMX_PORT}:${JMX_PORT}")
  fi

  docker run -d \
    --name "$NAME" \
    --network "$NETWORK" \
    --cpus="$CPUS" \
    --memory="$MEM" \
    --memory-swap="$MEMSWAP" \
    "${ENV_ARGS[@]}" \
    -v "$VOL":/var/lib/rsk \
    "${PORT_ARGS[@]}" \
    "$IMAGE"
}

if [ "$MINERS" -gt 0 ]; then
  echo "Starting $MINERS miners..."
  for i in $(seq 1 "$MINERS"); do
    start_node "$i" "miner" "true"
    if [ "$ROLLING" = true ] && [ "$i" -lt "$MINERS" ]; then
      echo "Waiting for miner $i to initialize..."
      sleep 5
    fi
  done
fi

if [ "$NODES" -gt 0 ]; then
  echo "Starting $NODES regular nodes..."
  for i in $(seq 1 "$NODES"); do
    start_node "$i" "node" "false"
    if [ "$ROLLING" = true ] && [ "$i" -lt "$NODES" ]; then
      echo "Waiting for node $i to initialize..."
      sleep 5
    fi
  done
fi

echo
echo "Done."
if [ "$ENABLE_JMX" = true ]; then
  echo "JMX enabled. In VisualVM: Add JMX Connection to:"
  for i in $(seq 1 "$MINERS"); do
    echo "  localhost:$((JMX_BASE + i))  (miner $i)"
  done
  for i in $(seq 1 "$NODES"); do
    echo "  localhost:$((JMX_BASE + 100 + i))  (node $i)"
  done
fi
