import os
import threading
import queue
import matplotlib.pyplot as plt
from log_processor import process_line, uncle_counts
from log_utils import tail_log_file

def plot_uncle_distribution(uncle_counts):
    fig, ax = plt.subplots()
    ax.clear()

    # Ensure there are uncle counts to plot
    if uncle_counts:
        bins = range(0, max(uncle_counts) + 2)
        ax.hist(uncle_counts, bins=bins, color='purple', alpha=0.7)
        ax.set_xlabel('Number of Uncles')
        ax.set_ylabel('Frequency')
        ax.set_title('Distribution of Uncle Counts')
    else:
        print("No data available for uncle counts.")

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
        
        if uncle_counts:
            plot_uncle_distribution(uncle_counts)
            print("Plotted uncle distribution")
        
        plt.pause(0.1)