import os
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from log_processor import process_line, main_blocks_per_miner, sibling_blocks_per_miner, coinbase_labels,block_times
from log_utils import tail_log_file

def plot_main_vs_sibling_blocks(main_blocks, sibling_blocks, coinbase_labels):
    """
    Plot main vs sibling blocks in an independent window
    """
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

    fig, ax = plt.subplots()
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
        if main_blocks_per_miner or sibling_blocks_per_miner:
            plot_main_vs_sibling_blocks(main_blocks_per_miner, sibling_blocks_per_miner, coinbase_labels)
            print("Plotted main vs sibling blocks")
        
        plt.pause(0.1)