# RSK Mining Pool Analysis Tools

This folder contains tools for analyzing mining pool behavior in the RSK network, specifically focusing on block template updates and mining patterns.

## Scripts

### Template Refresh Analysis
- `template_refresh/get_intervals.py`: Monitors mining pools' block template update patterns in real-time
  - Tracks template refresh times for known mining pools
  - Saves timestamps and intervals to files in the results directory
  - Output: `timestamps_{n}.json` and `intervals_{n}.txt`

- `template_refresh/analyze_timestamps.py`: Analyzes the collected template update data
  - Allows selection of specific time windows for analysis
  - Creates visualizations of update patterns
  - Calculates correlations between different pools' update times
  - Output files (in results directory):
    - `timeline_plot_{n}.png`: Visualization of template updates over time
    - `correlation_heatmap_{n}.png`: Heatmap showing correlations between pools
    - `summary_{n}.txt`: Statistical summary of the analysis
    - `miner_stats_{n}.txt`: Detailed statistics for known miners

### Mining Analysis
- `analyze_miners.py`: Analyzes mining patterns from RSK node logs
  - Tracks block production by different miners
  - Identifies known mining pools
  - Shows distribution of blocks among miners
  - Displays statistics about known and unknown miners

### Reorganization Analysis
- `reorgs/reorgs.py`: Analyzes blockchain reorganization events from node logs
  - Identifies chain reorganizations
  - Tracks block numbers involved in reorgs
  - Calculates reorg statistics
  - Helps understand the frequency and impact of reorgs

- `reorgs/reorgs.sh`: Shell script for quick reorg analysis
  - Processes log files to find reorgs
  - Counts total number of reorgs
  - Shows reorg frequency relative to block count
  - Provides a simple command-line interface for reorg analysis

## Known Miners
Currently tracking the following mining pools:
- F2Pool: `0x12d3178a62ef1f520944534ed04504609f7307a1`
- AntPool: `0x4e5dabc28e4a0f5e5b19fcb56b28c5a1989352c1`
- Sec Pool: `0x5aee2975e2ed688f231ccb40e20ee6c10a98d507`
- ViaBTC: `0x08f6c90cfc462db10d4dd41fb1f2162ff854a462`
- Luxor: `0xcf5072f792246690c75c63638e3d98bb2554ff2c`
- SpiderPool: `0x0fd9b9b567a459c6c9645ab0847785aef13dfe1b`

## Visualizations

### Timeline Plot
- Shows when each pool updates their block template
- X-axis: Time (minutes since start)
- Y-axis: Mining pools
- Each point represents a template update
- Helps identify update patterns and synchronization

### Correlation Heatmap
- Shows correlation between pools' update patterns
- Uses 5-second windows to detect related updates
- Values range from -1 (blue) to 1 (red):
  - 1: Perfect correlation (synchronized updates)
  - 0: No correlation (independent updates)
  - -1: Perfect negative correlation (opposite updates)
- Helps identify pools with similar update strategies

## Usage

1. Monitor template updates:
``` 
bash
cd template_refresh
python get_intervals.py
```

2. Analyze collected data:
```bash
python analyze_timestamps.py
```

3. Analyze mining patterns:
```bash
python analyze_miners.py
```

4. Analyze reorganizations:
```bash
cd reorgs
# Using Python script
python reorgs.py

# Using Shell script
./reorgs.sh
```

## Results Directory
All output files are stored in the `results` directory:
- Timestamp data: `timestamps_{n}.json`
- Interval data: `intervals_{n}.txt`
- Visualizations: `timeline_plot_{n}.png`, `correlation_heatmap_{n}.png`
- Analysis summaries: `summary_{n}.txt`, `miner_stats_{n}.txt`
