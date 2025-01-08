#!/bin/bash

# Path to the log file
log_file="../logs/rsk.log"

# Get the first line that contains IMPORTED_BEST and extract the block number
first_block_num=$(grep -m 1 'IMPORTED_BEST' "$log_file" | \
awk -F'block: num: \\[' '{print $2}' | \
awk -F'\\]' '{print $1}')

# Get the last line that contains IMPORTED_BEST and extract the block number
last_block_num=$(grep 'IMPORTED_BEST' "$log_file" | \
awk -F'block: num: \\[' '{print $2}' | \
awk -F'\\]' '{print $1}' | \
tail -n 1)

# Calculate the total number of blocks
total_blocks=$((last_block_num - first_block_num + 1))

# Extract, sort, count, and filter block numbers
reorgs=$(grep 'IMPORTED_BEST' "$log_file" | \
grep 'block: num:' | \
awk -F'block: num: \\[' '{print $2}' | \
awk -F'\\]' '{print $1}' | \
sort | \
uniq -c | \
awk '$1 > 1 {count += 1} END {print count}')

# Print the final result
echo "There has been $reorgs reorgs in $total_blocks blocks."