import re
from statistics import mean, median, stdev
import numpy as np
from datetime import datetime

def analyze_process_times(filename):
    # Store processing times with block numbers
    process_data = []  # List of tuples (block_number, process_time)
    block_times = {}   # Dictionary to store block timestamps by hash
    mining_times = []  # List of tuples (block_number, mining_time)
    last_block_number = None  # Track the last block number
    
    # Regular expressions
    time_pattern = r'processed after: \[(\d+\.\d+)\]seconds'
    block_pattern = r'block: num: \[(\d+)\]'
    hash_pattern = r'hash: \[([a-f0-9]+)\]'
    parent_pattern = r'parentHash:\[([a-f0-9]+)\]'
    timestamp_pattern = r'(\d{4}-\d{2}-\d{2}-\d{2}:\d{2}:\d{2}\.\d{4})'
    
    try:
        with open(filename, 'r') as file:
            for line in file:
                # Only process lines with IMPORTED_BEST result
                if 'result IMPORTED_BEST' not in line:
                    continue
                    
                time_match = re.search(time_pattern, line)
                block_match = re.search(block_pattern, line)
                hash_match = re.search(hash_pattern, line)
                parent_match = re.search(parent_pattern, line)
                timestamp_match = re.search(timestamp_pattern, line)
                
                if all([time_match, block_match, hash_match, parent_match, timestamp_match]):
                    block_number = int(block_match.group(1))
                    
                    # Check if this block increments the block number by 1
                    if last_block_number is not None and block_number != last_block_number + 1:
                        continue
                    
                    process_time = float(time_match.group(1))
                    block_hash = hash_match.group(1)
                    parent_hash = parent_match.group(1)
                    timestamp = datetime.strptime(timestamp_match.group(1), '%Y-%m-%d-%H:%M:%S.%f')
                    
                    # Store block timestamp
                    block_times[block_hash] = timestamp
                    
                    # Calculate mining time if parent block exists
                    if parent_hash in block_times:
                        mining_time = (timestamp - block_times[parent_hash]).total_seconds()
                        mining_times.append((block_number, mining_time))
                    
                    process_data.append((block_number, process_time))
                    last_block_number = block_number
        
        if not process_data:
            print("No valid blocks found in the log file.")
            return
        
        # Create paired data (only entries with both times)
        complete_data = []
        for block_num, proc_time in process_data:
            mining_time = next((t[1] for t in mining_times if t[0] == block_num), None)
            if mining_time is not None:  # Only include if we have both times
                complete_data.append((block_num, proc_time, mining_time))

        if not complete_data:
            print("No complete block data found in the log file.")
            return

        # Unzip the complete data
        block_numbers, process_times, mining_times_list = zip(*complete_data)

        # Calculate statistics from complete data only
        stats = {
            'count': len(complete_data),
            'mean': mean(process_times),
            'median': median(process_times),
            'min': min(complete_data, key=lambda x: x[1]),  # (block_num, proc_time, mining_time)
            'max': max(complete_data, key=lambda x: x[1]),
            'std_dev': stdev(process_times) if len(process_times) > 1 else 0,
            'percentiles': {
                '25th': np.percentile(process_times, 25),
                '75th': np.percentile(process_times, 75),
                '90th': np.percentile(process_times, 90),
                '95th': np.percentile(process_times, 95),
                '99th': np.percentile(process_times, 99)
            }
        }

        mining_stats = {
            'min': min(complete_data, key=lambda x: x[2]),
            'max': max(complete_data, key=lambda x: x[2]),
            'mean': mean(mining_times_list),
            'median': median(mining_times_list),
            'std_dev': stdev(mining_times_list) if len(mining_times_list) > 1 else 0
        }

        # Print results
        print("\nBlock Processing Time Statistics (in seconds):")
        print("-" * 50)
        print(f"Total Complete Blocks: {stats['count']}")
        print(f"Mean Time:            {stats['mean']:.6f}")
        print(f"Median Time:          {stats['median']:.6f}")
        print(f"Minimum Time:         {stats['min'][1]:.6f} (Block #{stats['min'][0]}, Mining Time: {stats['min'][2]:.6f}s)")
        print(f"Maximum Time:         {stats['max'][1]:.6f} (Block #{stats['max'][0]}, Mining Time: {stats['max'][2]:.6f}s)")
        print(f"Standard Deviation:   {stats['std_dev']:.6f}")

        # Add top 10 longest processing times
        print("\nTop 10 Longest Processing Times:")
        print("-" * 50)
        print("   Block #    Process Time (s)  Mining Time (s)")
        print("-" * 50)
        
        # Sort by processing time
        sorted_by_process = sorted(complete_data, key=lambda x: x[1], reverse=True)
        for i, (block_num, proc_time, mining_time) in enumerate(sorted_by_process[:10], 1):
            print(f"{i:2d}. #{block_num:<8d} {proc_time:>13.6f}  {mining_time:>13.6f}")

        print("\nBlock Mining Time Statistics (in seconds):")
        print("-" * 50)
        print(f"Total Complete Blocks: {stats['count']}")
        print(f"Mean Time:            {mining_stats['mean']:.6f}")
        print(f"Median Time:          {mining_stats['median']:.6f}")
        print(f"Minimum Time:         {mining_stats['min'][2]:.6f} (Block #{mining_stats['min'][0]}, Process Time: {mining_stats['min'][1]:.6f}s)")
        print(f"Maximum Time:         {mining_stats['max'][2]:.6f} (Block #{mining_stats['max'][0]}, Process Time: {mining_stats['max'][1]:.6f}s)")
        print(f"Standard Deviation:   {mining_stats['std_dev']:.6f}")
        
        # Add top 10 longest mining times
        print("\nTop 10 Longest Mining Times:")
        print("-" * 50)
        print("   Block #    Mining Time (s)  Process Time (s)")
        print("-" * 50)
        
        # Sort by mining time
        sorted_by_mining = sorted(complete_data, key=lambda x: x[2], reverse=True)
        for i, (block_num, proc_time, mining_time) in enumerate(sorted_by_mining[:10], 1):
            print(f"{i:2d}. #{block_num:<8d} {mining_time:>13.6f}  {proc_time:>13.6f}")

        print("\nPercentiles (Processing Time):")
        print(f"25th Percentile:      {stats['percentiles']['25th']:.6f}")
        print(f"75th Percentile:      {stats['percentiles']['75th']:.6f}")
        print(f"90th Percentile:      {stats['percentiles']['90th']:.6f}")
        print(f"95th Percentile:      {stats['percentiles']['95th']:.6f}")
        print(f"99th Percentile:      {stats['percentiles']['99th']:.6f}")
        
        # Calculate percentage of blocks processed within certain thresholds
        thresholds = [0.005, 0.01, 0.05, 0.1, 0.5]
        print("\nProcessing Time Distribution:")
        for threshold in thresholds:
            count = sum(1 for t in process_times if t <= threshold)
            percentage = (count / len(process_times)) * 100
            print(f"≤ {threshold:.3f}s:           {percentage:.2f}%")

    except FileNotFoundError:
        print(f"Error: File '{filename}' not found.")
    except Exception as e:
        print(f"Error processing file: {e}")

if __name__ == "__main__":
    log_file = "../logs/rsk.log"  # Adjust path as needed
    analyze_process_times(log_file) 