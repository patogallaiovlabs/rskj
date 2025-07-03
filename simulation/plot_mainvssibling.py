import os
import threading
import queue
import matplotlib.pyplot as plt
from log_processor import process_line, main_blocks_per_miner, sibling_blocks_per_miner, coinbase_dict
from log_utils import tail_log_file

def plot_total_blocks(main_blocks, sibling_blocks):
    fig, ax = plt.subplots()
    ax.clear()

    # Calculate totals
    total_main = sum(main_blocks.values())
    total_siblings = sum(sibling_blocks.values())

    # Create bar plot
    bars = ax.bar(['Main Chain', 'Siblings'], [total_main, total_siblings])
    
    # Add value labels on top of bars
    for bar in bars:
        height = bar.get_height()
        ax.text(bar.get_x() + bar.get_width()/2., height,
                 f'{int(height)}',
                 ha='center', va='bottom')

    ax.set_title('Total Main Chain vs Sibling Blocks')
    ax.set_ylabel('Number of Blocks')

    # Add percentage labels
    total_blocks = total_main + total_siblings
    if total_blocks > 0:
        main_percentage = (total_main / total_blocks) * 100
        sibling_percentage = (total_siblings / total_blocks) * 100
        ax.text(0, height, f'{main_percentage:.1f}%', ha='center', va='bottom', transform=ax.get_xaxis_transform())
        ax.text(1, height, f'{sibling_percentage:.1f}%', ha='center', va='bottom', transform=ax.get_xaxis_transform())

    fig.tight_layout()
    plt.show()

if __name__ == "__main__":
    log_file = "samples/rskj-2025-01-15.0.log"  # Update this with the actual log file path
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
            plot_total_blocks(main_blocks_per_miner, sibling_blocks_per_miner)
            print("Plotted total main chain vs sibling blocks")
        
        plt.pause(0.1)