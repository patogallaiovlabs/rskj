import os
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from log_processor import process_line, main_blocks_per_miner, sibling_blocks_per_miner, coinbase_labels, block_times
from log_utils import tail_log_file

def plot_main_vs_sibling_blocks(ax, main_blocks, sibling_blocks, coinbase_labels):
    """
    Plot main vs sibling blocks in an independent window
    """
    ax.clear()  # Clear the previous plot

    miners = list(main_blocks.keys())
    main_counts = [main_blocks[miner] for miner in miners]
    sibling_counts = [sibling_blocks[miner] for miner in miners]
    
    # Translate coinbases
    translated_miners = [coinbase_labels.get(miner, miner) for miner in miners]
    
    # Combine and sort by total blocks
    combined_counts = [(m, mc + sc, mc, sc) for m, mc, sc in zip(translated_miners, main_counts, sibling_counts)]
    combined_counts.sort(key=lambda x: x[1], reverse=True)
    
    sorted_miners, total_counts, main_counts, sibling_counts = zip(*combined_counts)
    
    x = np.arange(len(sorted_miners))  # the label locations
    width = 0.35  # the width of the bars

    rects1 = ax.bar(x - width/2, main_counts, width, label='Main Blocks')
    rects2 = ax.bar(x + width/2, sibling_counts, width, label='Sibling Blocks')

    # Add some text for labels, title and custom x-axis tick labels, etc.
    ax.set_xlabel('Miners')
    ax.set_ylabel('Number of Blocks')
    ax.set_title('Main vs Sibling Blocks by Miner')
    ax.set_xticks(x)
    ax.set_xticklabels(sorted_miners, rotation=25, ha='right')
    ax.legend()

    # Add labels to the bars
    for rect in rects1 + rects2:
        height = rect.get_height()
        ax.annotate(f'{height}',
                    xy=(rect.get_x() + rect.get_width() / 2, height),
                    xytext=(0, 3),  # 3 points vertical offset
                    textcoords="offset points",
                    ha='center', va='bottom')

    fig.tight_layout()
    plt.draw()
    plt.pause(0.1)

if __name__ == "__main__":
    # Log file path
    log_file = "../logs/rsk.log"
    #log_file = "samples/rskj-2025-01-15.0.log"
    #log_file = "../logs/rskj-2025-01-22.0.log"  # Adjust path as needed
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    plt.ion()  # Turn on interactive mode

    # Create the figure and axes outside the loop
    fig, ax = plt.subplots()

    # Keep the plot open and process log updates in the main thread
    result = False
    while True: 
        while not q.empty():
            line = q.get()
            result |= process_line(line)
        if result:
            print("Plotting main vs sibling blocks")
            plot_main_vs_sibling_blocks(ax, main_blocks_per_miner, sibling_blocks_per_miner, coinbase_labels)
            result = False
            print("Plotted main vs sibling blocks")
        plt.pause(0.1)