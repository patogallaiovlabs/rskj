import os
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from collections import defaultdict
from log_processor import process_line, block_heights, coinbase_labels, sibling_blocks_per_miner
from log_utils import tail_log_file

def plot_histograms_per_miner(figs, axes_list, block_heights, coinbase_labels, sibling_blocks_per_miner):
    """
    Plot histograms for each miner showing the distribution of the number of blocks mined at the same height
    as a percentage of the total sibling blocks mined by each miner
    """
    # Clear the previous plots
    for axes in axes_list:
        for ax in axes:
            ax.clear()

    # Create a dictionary to store the block counts per height for each miner
    blocks_per_height_per_miner = defaultdict(list)

    # Populate the dictionary with block counts
    for height, miners in block_heights.items():
        for miner, count in miners.items():
            blocks_per_height_per_miner[miner].append(count)

    # Include all miners from coinbase_labels, even if they have no blocks
    for miner in coinbase_labels.keys():
        if miner not in blocks_per_height_per_miner:
            blocks_per_height_per_miner[miner] = []

    # Create a histogram for each miner
    for i, (miner, counts) in enumerate(blocks_per_height_per_miner.items()):
        fig_index = i // 4
        ax_index = i % 4
        ax = axes_list[fig_index][ax_index]
        translated_miner = coinbase_labels.get(miner, miner)
        total_sibling_blocks = sibling_blocks_per_miner.get(miner, 0)
        
        if counts and total_sibling_blocks > 0:  # If miner has blocks and sibling blocks
            hist, bins = np.histogram(counts, bins=np.arange(1.5, 6.5, 1))
            percentages = hist / total_sibling_blocks * 100
            ax.bar(bins[:-1], percentages, width=0.5, edgecolor='black', alpha=0.7)
        else:  # If miner has no blocks or no sibling blocks, show empty histogram
            ax.bar([], [], width=0.5, edgecolor='black', alpha=0.7)
            
        ax.set_xlabel('Self siblings', fontsize=10)
        ax.set_ylabel('Frequency (%)', fontsize=10)
        ax.set_title(f'{translated_miner}', fontsize=12)
        ax.grid(True, alpha=0.3)
        ax.set_xticks(np.arange(2, 6))
        ax.set_xlim(1, 5)
        ax.set_ylim(0, 50)
        ax.yaxis.set_major_locator(plt.MaxNLocator(integer=True))

    # Hide any unused subplots and remove empty figures
    for fig_index, axes in enumerate(axes_list):
        empty_axes = True
        for i in range(len(axes)):
            if i >= len(blocks_per_height_per_miner) - fig_index * 4:
                if axes[i] in figs[fig_index].axes:  # Check if the axis exists in the figure
                    figs[fig_index].delaxes(axes[i])
            elif axes[i].has_data():
                empty_axes = False
        if empty_axes:
            plt.close(figs[fig_index])

    for fig in figs:
        fig.tight_layout()
        fig.canvas.draw()
        fig.canvas.flush_events()

if __name__ == "__main__":
    # Log file path
    log_file = "../logs/rsk.log"  # Adjust path as needed
    #log_file = "../logs/rskj-2025-01-22.0.log"  # Adjust path as needed
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    plt.ion()  # Turn on interactive mode

    # Create the figure and axes outside the loop
    num_miners = len(coinbase_labels)
    num_cols = 2
    num_rows = 2  # Maximum 4 plots per figure
    num_figs = (num_miners + 3) // 4  # Calculate the number of figures needed

    figs = []
    axes_list = []
    for _ in range(num_figs):
        fig_hist, axes_hist = plt.subplots(num_rows, num_cols, figsize=(15, 10))
        axes_hist = axes_hist.flatten()  # Flatten in case of single row
        figs.append(fig_hist)
        axes_list.append(axes_hist)

    # Keep the plot open and process log updates in the main thread
    result = False
    while True: 
        while not q.empty():
            line = q.get()
            result = result | process_line(line)
        if result:
            print("Plotting histograms per miner")
            plot_histograms_per_miner(figs, axes_list, block_heights, coinbase_labels, sibling_blocks_per_miner)
            result = False
            print("Plotted histograms per miner")
        plt.pause(0.1)