import os
import subprocess
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from log_processor import process_line, block_latencies
from log_utils import tail_log_file

def plot_histogram_with_percentiles(mining_times):
    """
    Plot histogram of mining times with percentiles in an independent window
    """
    percentiles = [50, 75, 90]
    percentile_values = np.percentile(mining_times, percentiles)
    
    plt.figure()
    bins = np.arange(0, 302, 2)  # Bins every 2 seconds from 0 to 300
    plt.hist(mining_times, bins=bins, edgecolor='black', alpha=0.7)
    plt.title('Histogram of Mining Times with Percentiles')
    plt.xlabel('Block Time (seconds)')
    plt.ylabel('Number of Blocks')
    
    colors = ['r', 'g', 'b']
    for percentile, value, color in zip(percentiles, percentile_values, colors):
        plt.axvline(value, color=color, linestyle='dashed', linewidth=1, label=f'{percentile}th: {value:.2f}s')
    
    plt.xlim(0, 300)  # Limit x-axis to 0-300 seconds
    plt.legend(loc='upper right')
    plt.show()

def window_variance_analysis(block_latencies, min_window=1, max_window=256):
    if len(block_latencies) < min_window:
        print("Not enough data.")
        return

    print("Analyzing windows...")
    overall_avg = np.mean(block_latencies)
    results = []

    for window_size in range(min_window, min(max_window, len(block_latencies)) + 1):
        window_avgs = []
        for start in range(0, len(block_latencies) - window_size + 1):
            window = block_latencies[start:start + window_size]
            window_avg = np.mean(window)
            window_avgs.append(window_avg)
        # Calculate variance of the difference from overall average
        diffs = [avg - overall_avg for avg in window_avgs]
        variance = np.std(diffs)
        results.append((window_size, variance))

    # Sort by variance (ascending)
    results.sort(key=lambda x: x[1])

    print("Window size analysis (sorted by variance of window avg error):")
    for window_size, variance in results:
        print(f"Window size: {window_size}, Variance of avg error: {variance:.6f}")

# Example usage:
# window_variance_analysis(block_latencies)

if __name__ == "__main__":
    #log_file = "../logs/rsk.log"  rskj/simulation/
    log_file = "./samples/rskj-2025-01-15.0.log"
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    WINDOW_SIZE = 64
    window_start = 0

    while True:
        while not q.empty():
            line = q.get()
            process_line(line)
        # Only plot if we have enough new data for the next window
        if len(block_latencies) >= window_start + WINDOW_SIZE:
            window_variance_analysis(block_latencies)
            window = block_latencies[window_start:window_start + WINDOW_SIZE]
            plot_histogram_with_percentiles(window)
            print(f"Plotted histogram for mining times {window_start} to {window_start + WINDOW_SIZE - 1}")
            window_start += WINDOW_SIZE  # Move to the next window

        plt.pause(0.1)