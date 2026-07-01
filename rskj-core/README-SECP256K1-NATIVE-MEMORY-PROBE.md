# Secp256k1 Native Memory Probe

This probe isolates the `org.bitcoin.NativeSecp256k1` path to observe off-heap/direct memory behavior without full node services.

## Build

```zsh
./gradlew :rskj-core:classes
```

## Run

```zsh
java -cp rskj-core/build/classes/java/main:rskj-core/build/resources/main co.rsk.cli.tools.Secp256k1NativeMemoryProbe --threads=200 --ops-per-thread=1000000 --hold-seconds=300 --report-every-seconds=5
```

If running from a fat jar:

```zsh
./gradlew :rskj-core:fatJar
java -cp rskj-core/build/libs/rskj-core-*-all.jar co.rsk.cli.tools.Secp256k1NativeMemoryProbe --threads=200 --ops-per-thread=1000000 --hold-seconds=300 --report-every-seconds=5
```

To run continuously, set `--hold-seconds=0`.

To keep workers doing cryptographic work continuously (no idle phase), set `--ops-per-thread=0`.

The probe now generates a mix of valid and intentionally invalid signatures and keeps running even if native calls fail.
Control the ratio with `--invalid-rate-percent` (default `50`).

## Notes

- The probe requires the native secp256k1 library to load successfully.
- It reports heap, non-heap, direct/mapped buffer pools, and live thread count periodically.
- To inspect native memory categories, run with NMT enabled:

```zsh
java -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -cp rskj-core/build/classes/java/main:rskj-core/build/resources/main co.rsk.cli.tools.Secp256k1NativeMemoryProbe
```

## One-command live monitoring scripts

Local process monitor (requires target PID):

```zsh
sh scripts/secp256k1-nmt-live.sh <pid>
```

Docker runner + monitor in one command:

```zsh
sh scripts/secp256k1-nmt-docker.sh
```

Useful docker overrides:

```zsh
THREADS=300
OPS_PER_THREAD=500000
HOLD_SECONDS=0
SAMPLE_SECONDS=3
HISTORY_POINTS=80
WAIT_FOR_PID_SECONDS=420
OUT_DIR=./tmp/secp256k1-nmt
sh scripts/secp256k1-nmt-docker.sh
```

Both scripts output a live ASCII trend plot and persist samples in CSV for later analysis.

Notes:

- The docker script now waits up to `WAIT_FOR_PID_SECONDS` (default `300`) for the probe JVM to appear.
- If an existing CSV uses an older/incompatible schema, the scripts archive it to `nmt.csv.bak.<epoch>` and start a fresh `nmt.csv`.

## Generate PNG plots from CSV

Install plotting dependency (once):

```zsh
python3 -m pip install -r scripts/requirements-nmt-plot.txt
```

Generate a PNG from a monitor CSV:

```zsh
python3 scripts/nmt_plot.py ./tmp/secp256k1-nmt/nmt.csv --out ./tmp/secp256k1-nmt/nmt-plot.png
```

Plot only the latest window:

```zsh
python3 scripts/nmt_plot.py ./tmp/secp256k1-nmt/nmt.csv --last 300 --out ./tmp/secp256k1-nmt/nmt-plot-last300.png
```

Plot with smoothing to reduce noise/spikes in the graph:

```zsh
python3 scripts/nmt_plot.py ./tmp/secp256k1-nmt/nmt.csv --last 300 --smooth-window 7 --out ./tmp/secp256k1-nmt/nmt-plot-last300-smooth.png
```

Notes:

- `--smooth-window=1` keeps raw plotting (default).
- `--smooth-window>1` overlays smoothed lines (moving average), which helps when the live sampling is bursty.

