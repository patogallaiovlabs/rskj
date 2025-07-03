import os
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from log_processor import process_line, main_blocks_per_miner, sibling_blocks_per_miner, coinbase_labels
from log_utils import tail_log_file

# Set this constant to True or False to include or exclude the hashing power bar
INCLUDE_HASHING_POWER_BAR = True

def plot_main_vs_sibling_blocks_percentage(ax, main_blocks, sibling_blocks, coinbase_labels, include_hashing_power):
    """
    Plot main vs sibling blocks as a percentage of total blocks mined by each miner
    """
    ax.clear()  # Clear the previous plot

    miners = list(main_blocks.keys())
    main_counts = [main_blocks[miner] for miner in miners]
    sibling_counts = [sibling_blocks[miner] for miner in miners]
    total_counts = [main_blocks[miner] + sibling_blocks[miner] for miner in miners]
    total_blocks = sum(total_counts)
    
    # Translate coinbases
    translated_miners = [coinbase_labels.get(miner, miner) for miner in miners]
    
    # Calculate percentages
    main_percentages = [mc / tc * 100 if tc > 0 else 0 for mc, tc in zip(main_counts, total_counts)]
    sibling_percentages = [sc / tc * 100 if tc > 0 else 0 for sc, tc in zip(sibling_counts, total_counts)]
    total_percentages = [tc / total_blocks * 100 if total_blocks > 0 else 0 for tc in total_counts]
    
    # Combine and sort by percentage of main blocks
    combined_counts = [(m, tc, mp, sp, tp) for m, tc, mp, sp, tp in zip(translated_miners, total_counts, main_percentages, sibling_percentages, total_percentages)]
    combined_counts.sort(key=lambda x: x[2], reverse=True)
    
    sorted_miners, sorted_total_counts, sorted_main_percentages, sorted_sibling_percentages, sorted_total_percentages = zip(*combined_counts) if combined_counts else ([], [], [], [], [])
    
    x = np.arange(len(sorted_miners))  # the label locations
    width = 0.3  # the width of the bars

    rects1 = ax.bar(x - width, sorted_main_percentages, width, label='Main Blocks (%)')
    rects2 = ax.bar(x, sorted_sibling_percentages, width, label='Sibling Blocks (%)')
    
    rects3 = ax.bar(x + width, sorted_total_percentages, width, label='Hashing power (%)') if include_hashing_power else tuple()

    # Add some text for labels, title and custom x-axis tick labels, etc.
    ax.set_xlabel('Miners')
    ax.set_ylabel('Percentage of Blocks')
    ax.set_title('Main vs Sibling Blocks by Miner (Percentage)')
    ax.set_xticks(x)
    ax.set_xticklabels(sorted_miners, rotation=25, ha='right')
    ax.legend()

    # Add labels to the bars
    for rect in rects1 + rects2 + rects3:
        height = rect.get_height()
        ax.annotate(f'{height:.1f}%',
                    xy=(rect.get_x() + rect.get_width() / 2, height),
                    xytext=(0, 3),  # 3 points vertical offset
                    textcoords="offset points",
                    ha='center', va='bottom', fontsize=8)

    fig.tight_layout()
    plt.draw()
    plt.pause(0.1)

if __name__ == "__main__":
    # Log file path
    log_file = "../logs/rsk.log"  # Adjust path as needed
    #log_file = "samples/rskj-2025-01-29.0.log"  # Adjust path as needed
    #log_file = "samples/rskj-2025-01-15.0.log"
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
            print("Plotting main vs sibling blocks percentage")
            plot_main_vs_sibling_blocks_percentage(ax, main_blocks_per_miner, sibling_blocks_per_miner, coinbase_labels, INCLUDE_HASHING_POWER_BAR)
            result = False
            print("Plotted main vs sibling blocks percentage")
        plt.pause(0.1)