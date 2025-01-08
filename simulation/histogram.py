import matplotlib.pyplot as plt
from datetime import datetime
import re
import subprocess
from collections import deque

# Data storage
new_block_times = deque()  # Stores times between new blocks
last_block_number = None  # Tracks the last seen block number
last_block_time = None  # Tracks the timestamp of the last new block globally

# Initialize plot
plt.ion()
fig, ax = plt.subplots(figsize=(12, 6))

def update_plot():
    """
    Updates the histogram plot for new block times.
    """
    ax.clear()

    # Plot histogram for new block times
    bins = range(0, 205, 2)  # 5-second bins up to 200 seconds
    ax.hist(new_block_times, bins=bins, color="purple", alpha=0.7, label="New Block Times")
    ax.set_title("Histogram of New Block Times")
    ax.set_xlabel("New Block Time (seconds)")
    ax.set_ylabel("Frequency")
    ax.legend()

    # Add a vertical line and label for the average block time
    if new_block_times:
        overall_avg_time = sum(new_block_times) / len(new_block_times)
        ax.axvline(overall_avg_time, color='red', linestyle='--', linewidth=1)
        ax.text(
            overall_avg_time, ax.get_ylim()[1] * 0.95,
            f"Avg: {overall_avg_time:.1f}s",
            color='red', ha='center', va='bottom', fontsize=10
        )

    # Redraw plot
    plt.tight_layout()
    plt.draw()
    plt.pause(0.01)

def process_line(line):
    """
    Processes a single line of the log file to extract block information.
    """
    global last_block_number, last_block_time

    match = re.search(
        r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*block: num: \[(\d+)\].*coinbase:\[([0-9a-fA-F]+)\],.*timestamp:(\d+),',
        line
    )
    if match:
        log_time_str = match.group(1)
        block_number = int(match.group(2))

        # Convert log timestamp to datetime
        log_time = datetime.strptime(log_time_str, "%Y-%m-%d-%H:%M:%S.%f")

        # Calculate new block time (block number increments by 1)
        if last_block_number is not None and block_number == last_block_number + 1:
            if last_block_time is not None:
                new_block_time_diff = (log_time - last_block_time).total_seconds()
                new_block_times.append(new_block_time_diff)

        # Update last block time and number
        if last_block_number is None or block_number > last_block_number:
            last_block_time = log_time
        last_block_number = block_number

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
    log_file = "../logs/rsk.log"

    # Read history first
    read_history(log_file, lines=100000)

    # Start tailing the log file
    tail_log_file(log_file)