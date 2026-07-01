#!/bin/sh
set -eu

CONTAINER_NAME="${1:-rskj-miner2}"
SAMPLE_SECONDS="${2:-10}"
CSV_PATH="${3:-}"
MAX_SAMPLES="${4:-0}"

if [ -z "$CSV_PATH" ]; then
  OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/tmp/nmt-snapshots"
  mkdir -p "$OUT_DIR"
  CSV_PATH="$OUT_DIR/${CONTAINER_NAME}-nmt-$(date +%Y%m%d-%H%M%S).csv"
else
  mkdir -p "$(dirname "$CSV_PATH")"
fi

if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo "Container ${CONTAINER_NAME} is not running"
  exit 1
fi

if ! echo "$SAMPLE_SECONDS" | grep -Eq '^[0-9]+$' || [ "$SAMPLE_SECONDS" -le 0 ]; then
  echo "SAMPLE_SECONDS must be a positive integer"
  exit 1
fi

if ! echo "$MAX_SAMPLES" | grep -Eq '^[0-9]+$'; then
  echo "MAX_SAMPLES must be a non-negative integer"
  exit 1
fi

find_java_pid() {
  docker exec "$CONTAINER_NAME" sh -lc "ps -eo pid,comm,args | awk '/[j]ava/ {print \$1; exit}'" 2>/dev/null | tr -d '[:space:]'
}

collect_nmt_summary() {
  PID="$1"

  if docker exec "$CONTAINER_NAME" sh -lc "command -v jcmd >/dev/null 2>&1" >/dev/null 2>&1; then
    docker exec "$CONTAINER_NAME" sh -lc "jcmd $PID VM.native_memory summary | cat" 2>/dev/null || true
    return
  fi

  if docker exec "$CONTAINER_NAME" sh -lc "command -v jattach >/dev/null 2>&1" >/dev/null 2>&1; then
    docker exec "$CONTAINER_NAME" sh -lc "jattach $PID jcmd 'VM.native_memory summary' | sed '/^Connected to remote JVM$/d; /^JVM response code =/d' | cat" 2>/dev/null || true
    return
  fi

  echo ""
}

extract_total_pair_kb() {
  echo "$1" | sed -n 's/^Total: reserved=\([0-9][0-9]*\)KB, committed=\([0-9][0-9]*\)KB.*/\1,\2/p' | head -n 1
}

extract_category_committed_kb() {
  NMT_TEXT="$1"
  LABEL="$2"
  echo "$NMT_TEXT" | sed -n "s/.*${LABEL} (reserved=[0-9][0-9]*KB, committed=\([0-9][0-9]*\)KB).*/\1/p" | head -n 1
}

kb_to_mb() {
  awk -v kb="$1" 'BEGIN { if (kb == "") kb = 0; printf "%.2f", kb / 1024.0 }'
}

if [ ! -f "$CSV_PATH" ]; then
  echo "epoch,total_reserved_mb,total_committed_mb,rss_mb,non_nmt_estimated_mb,java_heap_mb,class_mb,thread_mb,code_mb,gc_mb,compiler_mb,internal_mb,other_mb,symbol_mb,nmt_mb,arena_chunk_mb,module_mb,safepoint_mb,synchronization_mb,serviceability_mb,metaspace_mb,string_dedup_mb,object_monitors_mb,unknown_mb" > "$CSV_PATH"
fi

echo "Container:      $CONTAINER_NAME"
echo "Sample seconds: $SAMPLE_SECONDS"
echo "CSV:            $CSV_PATH"

auto_pid="$(find_java_pid)"
if [ -z "$auto_pid" ]; then
  echo "Could not find a Java PID in ${CONTAINER_NAME}"
  exit 1
fi

echo "Java PID:       $auto_pid"

samples=0
while :; do
  if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Container ${CONTAINER_NAME} stopped"
    exit 1
  fi

  PID="$(find_java_pid)"
  if [ -z "$PID" ]; then
    echo "Could not find Java PID in ${CONTAINER_NAME}"
    exit 1
  fi

  NMT_OUTPUT="$(collect_nmt_summary "$PID")"
  if [ -z "$NMT_OUTPUT" ]; then
    echo "Could not collect NMT summary (requires jcmd or jattach in container)"
    exit 1
  fi

  TOTAL_PAIR="$(extract_total_pair_kb "$NMT_OUTPUT")"
  if [ -z "$TOTAL_PAIR" ]; then
    echo "NMT output not parseable. Ensure JVM started with -XX:NativeMemoryTracking=summary"
    echo "$NMT_OUTPUT" | head -n 20
    exit 1
  fi

  TOTAL_RESERVED_KB="$(echo "$TOTAL_PAIR" | cut -d, -f1)"
  TOTAL_COMMITTED_KB="$(echo "$TOTAL_PAIR" | cut -d, -f2)"
  RSS_KB="$(docker exec "$CONTAINER_NAME" sh -lc "ps -o rss= -p $PID | tr -d ' '" 2>/dev/null || echo 0)"

  JAVA_HEAP_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Java Heap")"
  CLASS_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Class")"
  THREAD_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Thread")"
  CODE_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Code")"
  GC_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "GC")"
  COMPILER_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Compiler")"
  INTERNAL_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Internal")"
  OTHER_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Other")"
  SYMBOL_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Symbol")"
  NMT_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Native Memory Tracking")"
  ARENA_CHUNK_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Arena Chunk")"
  MODULE_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Module")"
  SAFEPOINT_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Safepoint")"
  SYNCHRONIZATION_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Synchronization")"
  SERVICEABILITY_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Serviceability")"
  METASPACE_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Metaspace")"
  STRING_DEDUP_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "String Deduplication")"
  OBJECT_MONITORS_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Object Monitors")"
  UNKNOWN_KB="$(extract_category_committed_kb "$NMT_OUTPUT" "Unknown")"

  TOTAL_RESERVED_MB="$(kb_to_mb "$TOTAL_RESERVED_KB")"
  TOTAL_COMMITTED_MB="$(kb_to_mb "$TOTAL_COMMITTED_KB")"
  RSS_MB="$(kb_to_mb "$RSS_KB")"
  NON_NMT_MB="$(awk -v rss="$RSS_MB" -v committed="$TOTAL_COMMITTED_MB" 'BEGIN { v=rss-committed; if (v<0) v=0; printf "%.2f", v }')"

  EPOCH="$(date +%s)"

  echo "${EPOCH},${TOTAL_RESERVED_MB},${TOTAL_COMMITTED_MB},${RSS_MB},${NON_NMT_MB},$(kb_to_mb "$JAVA_HEAP_KB"),$(kb_to_mb "$CLASS_KB"),$(kb_to_mb "$THREAD_KB"),$(kb_to_mb "$CODE_KB"),$(kb_to_mb "$GC_KB"),$(kb_to_mb "$COMPILER_KB"),$(kb_to_mb "$INTERNAL_KB"),$(kb_to_mb "$OTHER_KB"),$(kb_to_mb "$SYMBOL_KB"),$(kb_to_mb "$NMT_KB"),$(kb_to_mb "$ARENA_CHUNK_KB"),$(kb_to_mb "$MODULE_KB"),$(kb_to_mb "$SAFEPOINT_KB"),$(kb_to_mb "$SYNCHRONIZATION_KB"),$(kb_to_mb "$SERVICEABILITY_KB"),$(kb_to_mb "$METASPACE_KB"),$(kb_to_mb "$STRING_DEDUP_KB"),$(kb_to_mb "$OBJECT_MONITORS_KB"),$(kb_to_mb "$UNKNOWN_KB")" >> "$CSV_PATH"

  printf "[%s] committed=%sMB rss=%sMB nonNmt=%sMB internal=%sMB other=%sMB unknown=%sMB\n" \
    "$(date '+%Y-%m-%d %H:%M:%S')" \
    "$TOTAL_COMMITTED_MB" \
    "$RSS_MB" \
    "$NON_NMT_MB" \
    "$(kb_to_mb "$INTERNAL_KB")" \
    "$(kb_to_mb "$OTHER_KB")" \
    "$(kb_to_mb "$UNKNOWN_KB")"

  samples=$((samples + 1))
  if [ "$MAX_SAMPLES" -gt 0 ] && [ "$samples" -ge "$MAX_SAMPLES" ]; then
    break
  fi

  sleep "$SAMPLE_SECONDS"
done

echo "Done. Wrote ${samples} sample(s) to ${CSV_PATH}"

