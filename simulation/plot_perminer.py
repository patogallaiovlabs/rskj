import matplotlib.pyplot as plt
from datetime import datetime
import re
import subprocess
from collections import defaultdict, deque

# Data storage
miner_times = defaultdict(list)  # Stores block times per miner
new_block_times = deque()  # Stores times between new blocks
block_counts = defaultdict(int)  # Stores block counts per miner
last_block_number = 0  # Tracks the last seen block number
last_block_time = {}  # Tracks the timestamp of the last new block
miner_labels = {
    "12d3178a62ef1f520944534ed04504609f7307a1": "F2Pool",
    "4e5dabc28e4a0f5e5b19fcb56b28c5a1989352c1": "AntPool",
    "5aee2975e2ed688f231ccb40e20ee6c10a98d507": "Sec Pool",
    "08f6c90cfc462db10d4dd41fb1f2162ff854a462": "ViaBTC",
    "cf5072f792246690c75c63638e3d98bb2554ff2c": "Luxor",
    "0fd9b9b567a459c6c9645ab0847785aef13dfe1b": "SpiderPool",
}
all_block_times = deque()  # Stores times between all blocks (including siblings)
main_chain_times = deque()  # Stores times between main chain blocks only
all_block_averages = deque()  # Store running averages for plotting
main_chain_averages = deque()  # Store running averages for plotting
last_main_chain_time = None  # Tracks the timestamp of the last main chain block
start_time = None  # Track when we started collecting data
total_blocks = 0   # Track total number of blocks seen

# Initialize main plots
plt.ion()
fig1, (ax1, ax2, ax3, ax4) = plt.subplots(4, 1, figsize=(12, 24))

# Initialize averages plot in separate window
fig2, ax5 = plt.subplots(figsize=(12, 6))
fig2.canvas.manager.set_window_title('Block Time Averages')  # Set window title

def update_plot():
    """
    Updates the combined plot with the latest statistics.
    """
    # Calculate average block times
    average_block_times = {miner: sum(times) / len(times) for miner, times in miner_times.items() if times}

    # Calculate total blocks and percentages
    total_blocks = sum(block_counts.values())
    block_percentages = {miner: (count / total_blocks) * 100 for miner, count in block_counts.items()}

    # Clear plots
    ax1.clear()
    ax2.clear()
    ax3.clear()
    ax4.clear()

    # Plot average block time as bars
    miners = [miner_labels.get(miner, miner) for miner in average_block_times.keys()]
    times = list(average_block_times.values())
    bars = ax1.bar(miners, times, color="blue", alpha=0.7, label="Average Block Time (s)")
    ax1.set_title("Average Block Time per Miner")
    ax1.set_ylabel("Average Block Time (seconds)")
    ax1.set_xlabel("Miner")
    ax1.tick_params(axis="x", rotation=45)

    # Add average block time labels on top of bars
    for bar, avg_time in zip(bars, times):
        height = bar.get_height()
        ax1.text(
            bar.get_x() + bar.get_width() / 2.0,
            height,
            f"{avg_time:.1f}s",
            ha="center",
            va="bottom",
            fontsize=8
        )

    # Plot percentage of blocks per miner
    percentages = [block_percentages[miner] for miner in average_block_times.keys()]
    bars2 = ax2.bar(miners, percentages, color="green", alpha=0.7, label="Percentage of Blocks (%)")
    ax2.set_title("Percentage of Blocks per Miner")
    ax2.set_ylabel("Percentage (%)")
    ax2.set_xlabel("Miner")
    ax2.tick_params(axis="x", rotation=45)

    # Add percentage labels on top of bars
    for bar, percentage in zip(bars2, percentages):
        height = bar.get_height()
        ax2.text(
            bar.get_x() + bar.get_width() / 2.0,
            height,
            f"{percentage:.1f}%",
            ha="center",
            va="bottom",
            fontsize=8
        )

    # Plot histogram of block times per miner
    bins = range(0, 205, 5)  # 5-second bins up to 200 seconds
    for miner, times in miner_times.items():
        if times:  # Only plot if there are block times
            ax3.hist(times, bins=bins, alpha=0.7, label=miner_labels.get(miner, miner), histtype="step", linewidth=1.5)
    ax3.set_title("Histogram of Block Times per Miner")
    ax3.set_xlabel("Block Time (seconds)")
    ax3.set_ylabel("Frequency")
    ax3.legend()

    # Plot histogram for new block times
    ax4.hist(new_block_times, bins=bins, color="purple", alpha=0.7, label="New Block Times")
    ax4.set_title("Histogram of New Block Times")
    ax4.set_xlabel("New Block Time (seconds)")
    ax4.set_ylabel("Frequency")
    ax4.legend()

    # Calculate averages
    avg_all_blocks = sum(all_block_times) / len(all_block_times) if all_block_times else 0
    avg_main_chain = sum(main_chain_times) / len(main_chain_times) if main_chain_times else 0
    
    # Store the averages for plotting
    all_block_averages.append(avg_all_blocks)
    main_chain_averages.append(avg_main_chain)

    # Clear the averages plot
    ax5.clear()

    # Plot the averages
    x_range = range(len(all_block_averages))
    ax5.plot(x_range, all_block_averages, label='All Blocks Avg', color='blue', linewidth=2)
    ax5.plot(x_range, main_chain_averages, label='Main Chain Avg', color='red', linewidth=2)
    
    # Add current values as text
    ax5.text(0.02, 0.95, f'Current All Blocks Avg: {avg_all_blocks:.2f}s', 
             transform=ax5.transAxes, fontsize=10)
    ax5.text(0.02, 0.90, f'Current Main Chain Avg: {avg_main_chain:.2f}s', 
             transform=ax5.transAxes, fontsize=10)

    # Calculate elapsed time
    current_time = datetime.now()
    elapsed_seconds = (current_time - start_time).total_seconds() if start_time else 0
    
    # Add elapsed time and total blocks to the averages plot
    ax5.text(0.02, 0.85, f'Total time: {elapsed_seconds:.2f} seconds', 
             transform=ax5.transAxes, fontsize=10)
    ax5.text(0.02, 0.80, f'Total blocks: {total_blocks}', 
             transform=ax5.transAxes, fontsize=10)

    ax5.set_title("Block Time Averages Over Time")
    ax5.set_xlabel("Updates")
    ax5.set_ylabel("Average Time (seconds)")
    ax5.legend()
    ax5.grid(True)

    # Redraw both figures
    fig1.tight_layout()
    fig2.tight_layout()
    plt.draw()
    plt.pause(0.01)

def process_line(line):
    global last_block_number, last_main_chain_time, start_time, total_blocks
    
    match = re.search(
        r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*block: num: \[(\d+)\].*coinbase:\[([0-9a-fA-F]+)\],.*timestamp:(\d+),',
        line
    )
    
    if match:
        # Initialize start_time if this is the first block
        if start_time is None:
            start_time = datetime.strptime(match.group(1), "%Y-%m-%d-%H:%M:%S.%f")
            
        log_time_str = match.group(1)
        block_number = int(match.group(2))
        miner = match.group(3)
        timestamp = int(match.group(4))

        # Convert log timestamp to datetime
        log_time = datetime.strptime(log_time_str, "%Y-%m-%d-%H:%M:%S.%f")

        # Calculate time difference for all blocks
        if last_block_time:  # If we have any previous blocks
            last_any_block_time = max(last_block_time.values())  # Get the most recent block time
            time_diff = (log_time - last_any_block_time).total_seconds()
            all_block_times.append(time_diff)

        # Check if the miner is listed in miner_labels
        if miner not in miner_labels:
            print(f"WARNING: Unlisted coinbase detected! Coinbase: {miner}")

        # Count the block for the miner
        block_counts[miner] += 1

        # Calculate block time if this miner has a previous block
        if miner in last_block_time:
            block_time_diff = (log_time - last_block_time[miner]).total_seconds()
            miner_times[miner].append(block_time_diff)
        last_block_time[miner] = log_time

        # Calculate new block time (block number increments by 1)
        if last_block_number is not None and block_number > last_block_number:
            new_block_time_diff = (log_time - last_main_chain_time).total_seconds() if last_main_chain_time else 0
            if new_block_time_diff > 0:  # Only append if we have a valid time difference
                new_block_times.append(new_block_time_diff)
                main_chain_times.append(new_block_time_diff)
            last_main_chain_time = log_time  # Update the last main chain block time
            last_block_time[block_number] = log_time
            last_block_number = block_number
        

        # Increment total blocks
        total_blocks += 1

def read_history(file_path, lines=100000):
    """
    Reads the last `lines` lines of the log file and processes them.
    """
    process = subprocess.Popen(['tail', '-n', str(lines), file_path], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    for line in process.stdout:
        process_line(line)
    update_plot()

def tail_log_file(file_path):
    """
    Tails the log file and processes lines in real time.
    """
    process = subprocess.Popen(['tail', '-F', file_path], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    while True:
        line = process.stdout.readline()
        if line:
            process_line(line)
            update_plot()

if __name__ == "__main__":
    # Log file path
        #log_file = "../logs/rsk.log"
    log_file = "samples/rskj-2025-01-15.0.log"

    # Read history first
    read_history(log_file, lines=100000)

    # Start tailing the log file
    tail_log_file(log_file)