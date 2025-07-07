import os
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from log_processor import (
    process_line, 
    sibling_time_differences_by_coinbase, 
    coinbase_labels, 
    sibling_block_data
)
from log_utils import tail_log_file

def plot_sibling_time_differences(ax, sibling_time_differences_by_coinbase, coinbase_labels):
    """
    Plot average, minimum, maximum, and count of sibling time differences by coinbase
    """
    ax.clear()  # Clear the previous plot

    # Calculate statistics for each coinbase
    coinbase_stats = {}
    for coinbase, time_diffs in sibling_time_differences_by_coinbase.items():
        if time_diffs:  # Only process if there are sibling blocks
            coinbase_stats[coinbase] = {
                'avg': np.mean(time_diffs),
                'min': np.min(time_diffs),
                'max': np.max(time_diffs),
                'count': len(time_diffs)
            }
    
    if not coinbase_stats:
        ax.text(0.5, 0.5, 'No sibling blocks found yet', 
                ha='center', va='center', transform=ax.transAxes, fontsize=14)
        ax.set_title('Sibling Time Differences by Miner')
        return

    # Sort by average time difference
    sorted_coinbases = sorted(coinbase_stats.keys(), 
                             key=lambda x: coinbase_stats[x]['avg'])
    
    # Get translated miner names
    translated_miners = [coinbase_labels.get(coinbase, coinbase) for coinbase in sorted_coinbases]
    
    # Extract statistics
    averages = [coinbase_stats[coinbase]['avg'] for coinbase in sorted_coinbases]
    minimums = [coinbase_stats[coinbase]['min'] for coinbase in sorted_coinbases]
    maximums = [coinbase_stats[coinbase]['max'] for coinbase in sorted_coinbases]
    counts = [coinbase_stats[coinbase]['count'] for coinbase in sorted_coinbases]
    
    x = np.arange(len(sorted_coinbases))
    width = 0.2  # the width of the bars (reduced to accommodate 4 bars)

    # Create grouped bar chart with 4 bars
    rects1 = ax.bar(x - 1.5*width, averages, width, label='Average', color='blue', alpha=0.7)
    rects2 = ax.bar(x - 0.5*width, minimums, width, label='Minimum', color='green', alpha=0.7)
    rects3 = ax.bar(x + 0.5*width, maximums, width, label='Maximum', color='red', alpha=0.7)
    rects4 = ax.bar(x + 1.5*width, counts, width, label='Count', color='orange', alpha=0.7)

    # Add some text for labels, title and custom x-axis tick labels, etc.
    ax.set_xlabel('Miners')
    ax.set_ylabel('Time Difference from First Block (seconds) / Count')
    ax.set_title('Sibling Block Time Differences by Miner')
    ax.set_xticks(x)
    ax.set_xticklabels(translated_miners, rotation=25, ha='right')
    ax.legend()

    # Add value labels on top of bars
    for rects, values, color in [(rects1, averages, 'blue'), 
                                (rects2, minimums, 'green'), 
                                (rects3, maximums, 'red'),
                                (rects4, counts, 'orange')]:
        for rect, value in zip(rects, values):
            height = rect.get_height()
            if color == 'orange':  # Count values are integers
                ax.annotate(f'{int(value)}',
                            xy=(rect.get_x() + rect.get_width() / 2, height),
                            xytext=(0, 3),  # 3 points vertical offset
                            textcoords="offset points",
                            ha="center", va="bottom",
                            fontsize=8, color=color)
            else:  # Time values are floats
                ax.annotate(f'{value:.1f}s',
                            xy=(rect.get_x() + rect.get_width() / 2, height),
                            xytext=(0, 3),  # 3 points vertical offset
                            textcoords="offset points",
                            ha="center", va="bottom",
                            fontsize=8, color=color)

    # Add total count information as text
    total_siblings = sum(counts)
    ax.text(0.02, 0.98, f'Total sibling blocks: {total_siblings}', 
            transform=ax.transAxes, fontsize=10, va='top',
            bbox=dict(boxstyle="round,pad=0.3", facecolor="white", alpha=0.8))

    fig.tight_layout()
    plt.draw()
    plt.pause(0.1)

def plot_coinbase_distributions(sibling_time_differences_by_coinbase, coinbase_labels):
    """
    Create a new window with distribution plots for each coinbase
    """
    # Filter coinbases that have sibling blocks
    coinbases_with_siblings = {coinbase: time_diffs for coinbase, time_diffs in sibling_time_differences_by_coinbase.items() if time_diffs}
    
    if not coinbases_with_siblings:
        print("No sibling blocks found for distribution plots")
        return
    
    # Calculate global min and max for unified scale
    all_time_diffs = []
    for time_diffs in coinbases_with_siblings.values():
        all_time_diffs.extend(time_diffs)
    
    global_min = min(all_time_diffs) if all_time_diffs else 0
    global_max = max(all_time_diffs) if all_time_diffs else 1
    
    # Calculate global frequency range for unified y-axis scale
    global_freq_max = 0
    bins = np.linspace(global_min, global_max, min(21, len(set(all_time_diffs)) + 1))
    
    # Find the maximum frequency across all histograms
    for time_diffs in coinbases_with_siblings.values():
        hist, _ = np.histogram(time_diffs, bins=bins)
        global_freq_max = max(global_freq_max, np.max(hist))
    
    # Create a new figure with subplots
    num_coinbases = len(coinbases_with_siblings)
    cols = min(4, num_coinbases)  # Maximum 4 columns
    rows = (num_coinbases + cols - 1) // cols  # Calculate rows needed
    
    fig_dist, axes = plt.subplots(rows, cols, figsize=(15, 5 * rows))
    fig_dist.suptitle('Sibling Time Differences Distribution by Miner', fontsize=14)
    
    # Ensure axes is always a 2D array
    if num_coinbases == 1:
        axes = np.array([[axes]])
    elif rows == 1:
        axes = axes.reshape(1, -1)
    else:
        axes = axes.reshape(rows, cols)
    
    # Plot distribution for each coinbase
    for idx, (coinbase, time_diffs) in enumerate(coinbases_with_siblings.items()):
        row = idx // cols
        col = idx % cols
        ax = axes[row, col]
        
        # Get miner name
        miner_name = coinbase_labels.get(coinbase, coinbase)
        
        # Create histogram with unified bins
        ax.hist(time_diffs, bins=bins, alpha=0.7, color='skyblue', edgecolor='black')
        
        # Add statistics
        mean_val = np.mean(time_diffs)
        median_val = np.median(time_diffs)
        std_val = np.std(time_diffs)
        
        # Add vertical lines for mean and median
        ax.axvline(mean_val, color='red', linestyle='--', linewidth=2, label=f'Mean: {mean_val:.2f}s')
        ax.axvline(median_val, color='green', linestyle='--', linewidth=2, label=f'Median: {median_val:.2f}s')
        
        # Add text box with statistics
        stats_text = f'Count: {len(time_diffs)}\nMean: {mean_val:.2f}s\nMedian: {median_val:.2f}s\nStd: {std_val:.2f}s'
        ax.text(0.02, 0.98, stats_text, transform=ax.transAxes, fontsize=8, va='top',
                bbox=dict(boxstyle="round,pad=0.3", facecolor="white", alpha=0.8))
        
        # Set unified x-axis and y-axis limits
        ax.set_xlim(global_min, global_max)
        ax.set_ylim(0, global_freq_max * 1.1)  # Add 10% padding to y-axis
        
        ax.set_xlabel('Time Difference (seconds)', fontsize=9)
        ax.set_ylabel('Frequency', fontsize=9)
        ax.set_title(f'{miner_name}', fontsize=10)
        ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)
    
    # Hide empty subplots
    for idx in range(num_coinbases, rows * cols):
        row = idx // cols
        col = idx % cols
        axes[row, col].set_visible(False)
    
    fig_dist.tight_layout()
    plt.draw()
    plt.pause(0.1)
    
    return fig_dist

if __name__ == "__main__":
    # Log file path
    log_file = "../logs/rsk.log"
    #log_file = "samples/rskj-2025-01-15.0.log"
    #log_file = "../logs/rsk1.log"  # Adjust path as needed
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    plt.ion()  # Turn on interactive mode

    # Create the figure with single plot
    fig, ax = plt.subplots(figsize=(12, 8))
    fig.suptitle('Sibling Block Time Differences Analysis', fontsize=16)
    
    # Initialize distribution figure as None
    fig_dist = None

    # Keep the plot open and process log updates in the main thread
    result = False
    while True: 
        while not q.empty():
            line = q.get()
            result |= process_line(line)
        if result:
            print("Plotting sibling time differences")
            plot_sibling_time_differences(ax, sibling_time_differences_by_coinbase, coinbase_labels)
            
            # Create or update distribution plots
            if fig_dist is None:
                fig_dist = plot_coinbase_distributions(sibling_time_differences_by_coinbase, coinbase_labels)
            else:
                # Close the old distribution window and create a new one
                plt.close(fig_dist)
                fig_dist = plot_coinbase_distributions(sibling_time_differences_by_coinbase, coinbase_labels)
            
            result = False
            print("Plotted sibling time differences and distributions")
        plt.pause(0.1) 