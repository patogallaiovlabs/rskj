#!/bin/sh
set -eu

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="${OUT_DIR:-${REPO_DIR}/tmp/secp256k1-nmt}"
IMAGE="${IMAGE:-eclipse-temurin:17}"
PLATFORM="${PLATFORM:-linux/amd64}"
DOCKER_MEMORY_LIMIT="${DOCKER_MEMORY_LIMIT:-4g}"
THREADS="${THREADS:-200}"
OPS_PER_THREAD="${OPS_PER_THREAD:-0}"
HOLD_SECONDS="${HOLD_SECONDS:-0}"
REPORT_EVERY_SECONDS="${REPORT_EVERY_SECONDS:-5}"
JVM_HEAP_MAX="${JVM_HEAP_MAX:-3g}"
INVALID_RATE_PERCENT="${INVALID_RATE_PERCENT:-50}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-5}"
HISTORY_POINTS="${HISTORY_POINTS:-40}"
KEEP_CONTAINER="${KEEP_CONTAINER:-0}"
WAIT_FOR_PID_SECONDS="${WAIT_FOR_PID_SECONDS:-300}"

CONTAINER_NAME="secp256k1-nmt-$(date +%s)"
CSV_PATH="${OUT_DIR}/nmt.csv"
PROBE_LOG="${OUT_DIR}/probe.log"
CONTAINER_OUT_DIR="/out"
CONTAINER_PROBE_LOG="${CONTAINER_OUT_DIR}/probe.log"
CSV_HEADER="epoch,total_reserved_mb,total_committed_mb,java_heap_committed_mb,thread_committed_mb,code_committed_mb,gc_committed_mb,internal_committed_mb,other_committed_mb,rss_mb,non_nmt_estimated_mb,unknown_committed_mb,arena_chunk_committed_mb,jni_likely_estimated_mb"

mkdir -p "$OUT_DIR"

echo "Using image: $IMAGE"
echo "Platform:    $PLATFORM"
echo "Container mem limit: $DOCKER_MEMORY_LIMIT"
echo "JVM max heap:        $JVM_HEAP_MAX"
echo "Ops per thread:      $OPS_PER_THREAD (0 means non-stop work)"
echo "Invalid rate:        $INVALID_RATE_PERCENT%"
echo "PID wait timeout:    ${WAIT_FOR_PID_SECONDS}s"
echo "Output dir:  $OUT_DIR"

cleanup() {
  if [ "$KEEP_CONTAINER" = "1" ]; then
    echo "Keeping container $CONTAINER_NAME (KEEP_CONTAINER=1)"
    return
  fi
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

print_probe_log() {
  if [ -f "$PROBE_LOG" ]; then
    tail -n 100 "$PROBE_LOG" || true
    return
  fi
  echo "Probe log not found at $PROBE_LOG"
}

CONTAINER_CMD='set -eu
if ! command -v git >/dev/null 2>&1; then
  apt-get update >> "$CONTAINER_PROBE_LOG" 2>&1
  apt-get install -y git >> "$CONTAINER_PROBE_LOG" 2>&1
fi

./gradlew --no-daemon --no-watch-fs -Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false :rskj-core:clean :rskj-core:classes :rskj-core:fatJar >> "$CONTAINER_PROBE_LOG" 2>&1

NATIVE_JAR=$(find /root/.gradle /home -path "*/co.rsk/native/*/native-*.jar" 2>/dev/null | grep "/native-[0-9].*\\.jar$" | head -n 1)
if [ -z "$NATIVE_JAR" ]; then
  echo "Could not find co.rsk:native jar in Gradle cache" >> "$CONTAINER_PROBE_LOG"
  exit 1
fi

FAT_JAR=$(ls -1t /work/rskj-core/build/libs/rskj-core-*-all.jar 2>/dev/null | head -n 1)
if [ -z "$FAT_JAR" ]; then
  echo "Could not resolve fat jar under /work/rskj-core/build/libs" >> "$CONTAINER_PROBE_LOG"
  ls -1 /work/rskj-core/build/libs >> "$CONTAINER_PROBE_LOG" 2>&1
  exit 1
fi

if ! jar tf "$FAT_JAR" | grep -q "co/rsk/cli/tools/Secp256k1NativeMemoryProbe.class"; then
  echo "Selected fat jar does not contain probe class: $FAT_JAR" >> "$CONTAINER_PROBE_LOG"
  ls -1 /work/rskj-core/build/libs >> "$CONTAINER_PROBE_LOG" 2>&1
  exit 1
fi

echo "Using FAT_JAR=$FAT_JAR" >> "$CONTAINER_PROBE_LOG"
echo "Using NATIVE_JAR=$NATIVE_JAR" >> "$CONTAINER_PROBE_LOG"

LIB_DIR=/tmp/rsk-native-lib
mkdir -p "$LIB_DIR"
cd "$LIB_DIR"
jar xf "$NATIVE_JAR" org/bitcoin/native/Linux/x86_64/libsecp256k1.so >> "$CONTAINER_PROBE_LOG" 2>&1 || true
if [ -f org/bitcoin/native/Linux/x86_64/libsecp256k1.so ]; then
  mv org/bitcoin/native/Linux/x86_64/libsecp256k1.so "$LIB_DIR/libsecp256k1.so"
fi
if [ ! -f "$LIB_DIR/libsecp256k1.so" ]; then
  echo "Could not extract JNI libsecp256k1.so from co.rsk:native jar" >> "$CONTAINER_PROBE_LOG"
  jar tf "$NATIVE_JAR" >> "$CONTAINER_PROBE_LOG" 2>&1
  exit 1
fi

java -Xms$JVM_HEAP_MAX -Xmx$JVM_HEAP_MAX -Djava.library.path=$LIB_DIR -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -cp "$FAT_JAR" co.rsk.cli.tools.Secp256k1NativeMemoryProbe --threads=$THREADS --ops-per-thread=$OPS_PER_THREAD --hold-seconds=$HOLD_SECONDS --report-every-seconds=$REPORT_EVERY_SECONDS --invalid-rate-percent=$INVALID_RATE_PERCENT >> "$CONTAINER_PROBE_LOG" 2>&1'

docker run -d --rm \
  --platform "$PLATFORM" \
  --memory "$DOCKER_MEMORY_LIMIT" \
  --memory-swap "$DOCKER_MEMORY_LIMIT" \
  --name "$CONTAINER_NAME" \
  -e THREADS="$THREADS" \
  -e OPS_PER_THREAD="$OPS_PER_THREAD" \
  -e HOLD_SECONDS="$HOLD_SECONDS" \
  -e REPORT_EVERY_SECONDS="$REPORT_EVERY_SECONDS" \
  -e INVALID_RATE_PERCENT="$INVALID_RATE_PERCENT" \
  -e JVM_HEAP_MAX="$JVM_HEAP_MAX" \
  -e CONTAINER_PROBE_LOG="$CONTAINER_PROBE_LOG" \
  -v "$REPO_DIR":/work \
  -v "$OUT_DIR":"$CONTAINER_OUT_DIR" \
  -w /work \
  "$IMAGE" \
  sh -lc "$CONTAINER_CMD" >/dev/null

echo "Container started: $CONTAINER_NAME"

echo "Waiting for Java PID..."
PID=""
for _ in $(seq 1 "$WAIT_FOR_PID_SECONDS"); do
  PID="$(docker exec "$CONTAINER_NAME" sh -lc "jcmd | awk '/co.rsk.cli.tools.Secp256k1NativeMemoryProbe/ {print \$1; exit}'" 2>/dev/null || true)"
  if [ -n "$PID" ]; then
    break
  fi
  if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Container exited early. Last logs:"
    print_probe_log
    exit 1
  fi
  sleep 1
done

if [ -z "$PID" ]; then
  echo "Could not find probe PID in container within ${WAIT_FOR_PID_SECONDS}s."
  print_probe_log
  exit 1
fi

echo "Probe PID inside container: $PID"

if [ ! -f "$CSV_PATH" ]; then
  echo "$CSV_HEADER" > "$CSV_PATH"
else
  CURRENT_HEADER="$(head -n 1 "$CSV_PATH" | tr -d '\r')"
  if [ "$CURRENT_HEADER" != "$CSV_HEADER" ]; then
    BACKUP_CSV="${CSV_PATH}.bak.$(date +%s)"
    mv "$CSV_PATH" "$BACKUP_CSV"
    echo "$CSV_HEADER" > "$CSV_PATH"
    echo "Archived incompatible CSV schema to $BACKUP_CSV"
  fi
fi

extract_total_kb() {
  echo "$1" | sed -n 's/.*Total: reserved=\([0-9][0-9]*\)KB, committed=\([0-9][0-9]*\)KB.*/\1,\2/p' | head -n 1
}

extract_category_committed_kb() {
  NMT_TEXT="$1"
  LABEL="$2"
  echo "$NMT_TEXT" | sed -n "s/.*${LABEL} (reserved=[0-9][0-9]*KB, committed=\([0-9][0-9]*\)KB).*/\1/p" | head -n 1
}

kb_to_mb() {
  awk -v kb="$1" 'BEGIN { if (kb == "") kb = 0; printf "%.2f", kb / 1024.0 }'
}

sub_floor_zero() {
  awk -v a="$1" -v b="$2" 'BEGIN { v=a-b; if (v < 0) v=0; printf "%.2f", v }'
}

sum_values() {
  awk -v a="$1" -v b="$2" -v c="$3" -v d="$4" 'BEGIN { printf "%.2f", a+b+c+d }'
}

render_plot() {
  clear
  echo "Docker NMT monitor (container=$CONTAINER_NAME pid=$PID sample=${SAMPLE_SECONDS}s csv=$CSV_PATH)"
  echo

  tail -n "$HISTORY_POINTS" "$CSV_PATH" | awk -F, '
    BEGIN { max=0 }
    $1 == "epoch" { next }
    NF < 14 { next }
    {
      v[++n]=$3+0
      if (v[n] > max) max=v[n]
      lastReserved=$2
      lastCommitted=$3
      lastHeap=$4
      lastThread=$5
      lastCode=$6
      lastGc=$7
      lastInternal=$8
      lastOther=$9
      lastRss=$10
      lastNonNmt=$11
      lastUnknown=$12
      lastArena=$13
      lastJniLikely=$14
      nmt[++m]=$3+0
      non[m]=$11+0
      jni[m]=$14+0
      if (nmt[m] > nmtMax) nmtMax=nmt[m]
      if (non[m] > nonMax) nonMax=non[m]
      if (jni[m] > jniMax) jniMax=jni[m]
    }
    END {
      if (n == 0) {
        print "Waiting for first sample..."
        exit
      }

      printf "last total reserved:  %.2f MB\n", lastReserved
      printf "last total committed: %.2f MB\n", lastCommitted
      printf "last rss:             %.2f MB\n", lastRss
      printf "last java heap:       %.2f MB\n", lastHeap
      printf "last thread:          %.2f MB\n", lastThread
      printf "last code:            %.2f MB\n", lastCode
      printf "last gc:              %.2f MB\n", lastGc
      printf "last internal:        %.2f MB\n", lastInternal
      printf "last other:           %.2f MB\n", lastOther
      printf "last unknown:         %.2f MB\n", lastUnknown
      printf "last arena chunk:     %.2f MB\n", lastArena
      printf "last jni-likely est.: %.2f MB\n", lastJniLikely
      printf "last non-nmt (est.):  %.2f MB\n", lastNonNmt
      printf "window max committed: %.2f MB\n", max
      print ""
      print "NMT committed MB trend (last samples):"

      if (max <= 0) max = 1
      width = 60
      for (i = 1; i <= n; i++) {
        len = int((v[i] / max) * width)
        bar = ""
        for (j = 0; j < len; j++) {
          bar = bar "#"
        }
        printf "%2d | %-60s %.2f\n", i, bar, v[i]
      }

      print ""
      print "Non-NMT estimated MB trend (rss - nmt committed):"
      if (nonMax <= 0) nonMax = 1
      for (i = 1; i <= m; i++) {
        len = int((non[i] / nonMax) * width)
        bar = ""
        for (j = 0; j < len; j++) {
          bar = bar "*"
        }
        printf "%2d | %-60s %.2f\n", i, bar, non[i]
      }

      print ""
      print "JNI-likely estimated MB trend (internal + other + unknown + arena chunk):"
      if (jniMax <= 0) jniMax = 1
      for (i = 1; i <= m; i++) {
        len = int((jni[i] / jniMax) * width)
        bar = ""
        for (j = 0; j < len; j++) {
          bar = bar "@"
        }
        printf "%2d | %-60s %.2f\n", i, bar, jni[i]
      }
    }
  '
}

while :; do
  if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Container stopped."
    print_probe_log
    exit 1
  fi

  NMT_OUTPUT="$(docker exec "$CONTAINER_NAME" sh -lc "jcmd $PID VM.native_memory summary | cat" 2>/dev/null || true)"
  if [ -z "$NMT_OUTPUT" ]; then
    echo "Could not collect NMT output."
    print_probe_log
    exit 1
  fi

  TOTAL_PAIR="$(extract_total_kb "$NMT_OUTPUT")"
  if [ -z "$TOTAL_PAIR" ]; then
    echo "NMT output not parseable. Ensure process started with -XX:NativeMemoryTracking=summary"
    echo "$NMT_OUTPUT" | head -n 20
    exit 1
  fi

  TOTAL_RESERVED_KB="$(echo "$TOTAL_PAIR" | cut -d, -f1)"
  TOTAL_COMMITTED_KB="$(echo "$TOTAL_PAIR" | cut -d, -f2)"
  JAVA_HEAP_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Java Heap")"
  THREAD_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Thread")"
  CODE_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Code")"
  GC_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "GC")"
  INTERNAL_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Internal")"
  OTHER_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Other")"
  UNKNOWN_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Unknown")"
  ARENA_CHUNK_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Arena Chunk")"

  RSS_KB="$(docker exec "$CONTAINER_NAME" sh -lc "ps -o rss= -p $PID | tr -d ' '" 2>/dev/null || echo 0)"
  RSS_MB="$(kb_to_mb "$RSS_KB")"
  TOTAL_COMMITTED_MB="$(kb_to_mb "$TOTAL_COMMITTED_KB")"
  INTERNAL_MB="$(kb_to_mb "$INTERNAL_KB")"
  OTHER_MB="$(kb_to_mb "$OTHER_KB")"
  UNKNOWN_MB="$(kb_to_mb "$UNKNOWN_KB")"
  ARENA_CHUNK_MB="$(kb_to_mb "$ARENA_CHUNK_KB")"
  JNI_LIKELY_MB="$(sum_values "$INTERNAL_MB" "$OTHER_MB" "$UNKNOWN_MB" "$ARENA_CHUNK_MB")"
  NON_NMT_MB="$(sub_floor_zero "$RSS_MB" "$TOTAL_COMMITTED_MB")"

  EPOCH="$(date +%s)"

  echo "${EPOCH},$(kb_to_mb "$TOTAL_RESERVED_KB"),${TOTAL_COMMITTED_MB},$(kb_to_mb "$JAVA_HEAP_KB"),$(kb_to_mb "$THREAD_KB"),$(kb_to_mb "$CODE_KB"),$(kb_to_mb "$GC_KB"),${INTERNAL_MB},${OTHER_MB},${RSS_MB},${NON_NMT_MB},${UNKNOWN_MB},${ARENA_CHUNK_MB},${JNI_LIKELY_MB}" >> "$CSV_PATH"

  render_plot
  sleep "$SAMPLE_SECONDS"
done

