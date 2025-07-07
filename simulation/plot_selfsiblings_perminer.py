import os
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from collections import defaultdict
from log_processor import process_line, block_heights, coinbase_labels
from log_utils import tail_log_file

def plot_max_blocks_per_height(ax, block_heights, coinbase_labels):
    """
    Plot the maximum amount of blocks mined at the same height, separated by miner
    """
    ax.clear()  # Clear the previous plot

    # Dictionary to store the maximum blocks per height for each miner
    max_blocks_per_height = defaultdict(int)

    # Calculate the maximum blocks per height for each miner
    for height, miners in block_heights.items():
        for miner, count in miners.items():
            max_blocks_per_height[miner] = max(max_blocks_per_height[miner], count)

    # Include all miners from coinbase_labels, even if they have 0 blocks
    for miner in coinbase_labels.keys():
        if miner not in max_blocks_per_height:
            max_blocks_per_height[miner] = 0

    # Translate coinbases
    translated_miners = [coinbase_labels.get(miner, miner) for miner in max_blocks_per_height.keys()]
    max_blocks = list(max_blocks_per_height.values())

    # Sort by maximum blocks
    sorted_data = sorted(zip(translated_miners, max_blocks), key=lambda x: x[1], reverse=True)
    sorted_miners, sorted_max_blocks = zip(*sorted_data) if sorted_data else ([], [])

    x = np.arange(len(sorted_miners))  # the label locations
    width = 0.2  # the width of the bars

    rects = ax.bar(x, sorted_max_blocks, width, label='Max Blocks per Height')

    # Add some text for labels, title and custom x-axis tick labels, etc.
    ax.set_xlabel('Miners', fontsize=10)
    ax.set_ylabel('Max Blocks per Height', fontsize=10)
    ax.set_title('Max Blocks Mined at the Same Height by Miner', fontsize=12)
    ax.set_xticks(x)
    ax.set_xticklabels(sorted_miners, rotation=25, ha='right', fontsize=8)
    ax.legend(fontsize=8)

    # Add labels to the bars
    for rect in rects:
        height = rect.get_height()
        ax.annotate(f'{height}',
                    xy=(rect.get_x() + rect.get_width() / 2, height),
                    xytext=(0, 3),  # 3 points vertical offset
                    textcoords="offset points",
                    ha='center', va='bottom', fontsize=8)

    fig.tight_layout()
    plt.draw()
    plt.pause(0.1)

def plot_histograms_per_miner(figs, axes_list, block_heights, coinbase_labels):
    """
    Plot histograms for each miner showing the distribution of the number of blocks mined at the same height
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

    # Determine the maximum Y-axis value for all histograms
    max_y = 0
    for counts in blocks_per_height_per_miner.values():
        if counts:  # Only process if there are counts
            hist, _ = np.histogram(counts, bins=np.arange(1.5, 6.5, 1))
            max_y = max(max_y, max(hist))

    # Create a histogram for each miner
    for i, (miner, counts) in enumerate(blocks_per_height_per_miner.items()):
        fig_index = i // 4
        ax_index = i % 4
        ax = axes_list[fig_index][ax_index]
        translated_miner = coinbase_labels.get(miner, miner)
        
        if counts:  # If miner has blocks, create histogram
            ax.hist(counts, bins=np.arange(1.5, 6.5, 1), edgecolor='black', alpha=0.7, width=0.5)
        else:  # If miner has no blocks, show empty histogram
            ax.hist([], bins=np.arange(1.5, 6.5, 1), edgecolor='black', alpha=0.7, width=0.5)
            
        ax.set_xlabel('Max blocks at same height', fontsize=10)
        ax.set_ylabel('Frequency', fontsize=10)
        ax.set_title(f'{translated_miner}', fontsize=12)
        ax.grid(True, alpha=0.3)
        ax.set_xticks(np.arange(2, 6))
        ax.set_xlim(1, 5)
        ax.set_ylim(0, max_y + 2)
        ax.yaxis.set_major_locator(plt.MaxNLocator(integer=True))

    # Hide any unused subplots and remove empty figures
    for fig_index, axes in enumerate(axes_list):
        empty_axes = True
        # Only try to delete axes that actually exist and are unused
        for i in range(len(blocks_per_height_per_miner) - fig_index * 4, len(axes)):
            if i < len(axes) and axes[i] in figs[fig_index].axes:
                figs[fig_index].delaxes(axes[i])
        for ax in axes:
            if ax.has_data():
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
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    plt.ion()  # Turn on interactive mode

    # Create the figure and axes outside the loop
    fig, ax = plt.subplots()
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
            print("Plotting max blocks per height")
            plot_max_blocks_per_height(ax, block_heights, coinbase_labels)
            plot_histograms_per_miner(figs, axes_list, block_heights, coinbase_labels)
            result = False
            print("Plotted max blocks per height and histograms per miner")
        plt.pause(0.1)