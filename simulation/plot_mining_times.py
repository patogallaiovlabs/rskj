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

if __name__ == "__main__":
    #log_file = "../logs/rsk.log"
    log_file = "samples/rskj-2025-01-15.0.log"
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    # Keep the plot open and process log updates in the main thread
    while True:
        while not q.empty():
            line = q.get()
            process_line(line)
        
        if block_latencies:
            plot_histogram_with_percentiles(block_latencies)
            print("Plotted histogram")
        
        plt.pause(0.1)