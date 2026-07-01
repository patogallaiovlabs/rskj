#!/bin/sh
set -eu

PID="${1:-auto}"
SAMPLE_SECONDS="${2:-5}"
HISTORY_POINTS="${3:-40}"

if ! command -v jcmd >/dev/null 2>&1; then
  echo "jcmd not found in PATH"
  exit 1
fi

if [ "$PID" = "auto" ]; then
  PID="$(jcmd | awk '/co.rsk.cli.tools.Secp256k1NativeMemoryProbe/ { print $1; exit }')"
  if [ -z "$PID" ]; then
    echo "Could not auto-detect a running Secp256k1NativeMemoryProbe process."
    echo "Run: jcmd"
    exit 1
  fi
  echo "Auto-detected probe PID: $PID"
fi

if ! ps -p "$PID" >/dev/null 2>&1; then
  echo "Process $PID is not running"
  echo "Available JVM processes:"
  jcmd | cat
  exit 1
fi

CSV_PATH="${4:-/tmp/secp256k1-nmt-${PID}.csv}"
CSV_HEADER="epoch,total_reserved_mb,total_committed_mb,java_heap_committed_mb,thread_committed_mb,code_committed_mb,gc_committed_mb,internal_committed_mb,other_committed_mb,rss_mb,non_nmt_estimated_mb,unknown_committed_mb,arena_chunk_committed_mb,jni_likely_estimated_mb"

mkdir -p "$(dirname "$CSV_PATH")"
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
  echo "Live NMT monitor (pid=$PID, sample=${SAMPLE_SECONDS}s, csv=$CSV_PATH)"
  echo

  tail -n "$HISTORY_POINTS" "$CSV_PATH" | awk -F, '
    BEGIN { max=0 }
    $1 == "epoch" { next }
    NF < 14 { next }
    {
      t[++n]=$1
      v[n]=$3+0
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
  NMT_OUTPUT="$(jcmd "$PID" VM.native_memory summary 2>/dev/null || true)"

  if [ -z "$NMT_OUTPUT" ]; then
    echo "Could not collect NMT from pid $PID. Process may have exited."
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

  RSS_KB="$(ps -o rss= -p "$PID" | tr -d ' ' || echo 0)"
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

