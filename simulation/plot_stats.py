import os
import threading
import queue
import matplotlib.pyplot as plt
from log_processor import process_line, block_numbers, block_latencies, average_new_block_times, transaction_counts, uncle_counts
from log_utils import tail_log_file

def plot_stats():
    fig, ax = plt.subplots()
    ax.clear()

    # Ensure all lists have the same length and are not empty
    block_nums = list(block_numbers)
    latencies = list(block_latencies)
    avg_new_block_times = list(average_new_block_times)
    tx_counts = list(transaction_counts)
    uncle_counts_list = list(uncle_counts)

    # Ensure all lists have the same length
    min_length = min(len(block_nums), len(latencies), len(avg_new_block_times), 
                    len(tx_counts), len(uncle_counts_list))
    
    if min_length == 0:
        return  # Exit if no data

    block_nums = block_nums[:min_length]
    latencies = latencies[:min_length]
    avg_new_block_times = avg_new_block_times[:min_length]
    tx_counts = tx_counts[:min_length]
    uncle_counts_list = uncle_counts_list[:min_length]

    # Create smoothed curves using exponential moving average
    def exp_moving_average(data, alpha=0.1):
        if not data:
            return []
        result = [data[0]]
        for n in range(1, len(data)):
            result.append(alpha * data[n] + (1 - alpha) * result[n-1])
        return result

    # Ensure we have positive values for log scale
    min_positive = 0.1  # Minimum positive value
    plot_latencies = [max(l, min_positive) for l in latencies]
    
    # Plot original data with low opacity
    ax.plot(block_nums, plot_latencies, label='Block Latency', color='red', alpha=0.2)
    
    # Plot smoothed data with full opacity
    smoothed_latencies = exp_moving_average(plot_latencies)
    if smoothed_latencies:
        ax.plot(block_nums, smoothed_latencies, label='Block Latency (trend)', 
                color='red', linewidth=2)

    # Set logarithmic scale and ensure positive values
    ax.set_yscale('log')
    ax.plot(block_nums, [max(t, min_positive) for t in avg_new_block_times], 
             label='Avg New Block Time', color='green')
    ax.plot(block_nums, [max(t, min_positive) for t in tx_counts], 
             label='Transaction Count', color='blue')
    ax.plot(block_nums, [max(t, min_positive) for t in uncle_counts_list], 
             label='Uncle Count', color='purple')

    ax.set_xlabel('Block Number')
    ax.set_ylabel('Time (seconds) / Count (log scale)')
    ax.legend()
    ax.grid(True, which="both", ls="-", alpha=0.2)

    fig.tight_layout()
    plt.show()

if __name__ == "__main__":
    log_file = "../logs/rskj-2025-01-15.0.log"  # Update this with the actual log file path
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
        
        if block_numbers and block_latencies and average_new_block_times and transaction_counts and uncle_counts:
            plot_stats()
            print("Plotted stats")
        
        plt.pause(0.1)