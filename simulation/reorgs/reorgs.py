import matplotlib.pyplot as plt
import networkx as nx
import subprocess
import threading
import re
import os
import queue
from datetime import datetime, timedelta, timezone
from collections import defaultdict, deque
import numpy as np

# Initialize a directed graph
G = nx.DiGraph()
coinbase_dict = {}
coinbase_counter = 0
coinbase_counts = defaultdict(int)
main_blocks_per_miner = defaultdict(int)
sibling_blocks_per_miner = defaultdict(int)
shapes = ['o', 's', '^', 'D', 'v', 'h', 'p', '8']  # Different shapes for different coinbases
# Define the coinbase label map
coinbase_labels = {
    "12d3178a62ef1f520944534ed04504609f7307a1": "F2Pool",
    "4e5dabc28e4a0f5e5b19fcb56b28c5a1989352c1": "AntPool",
    "5aee2975e2ed688f231ccb40e20ee6c10a98d507": "Sec Pool",
    "08f6c90cfc462db10d4dd41fb1f2162ff854a462": "ViaBTC",
    "cf5072f792246690c75c63638e3d98bb2554ff2c": "Luxor"
}

NODE_SIZE = 4000

# For tracking block arrival times
block_times = deque(maxlen=10000)  # Keeps a history of the last 1000 blocks
new_block_times = deque(maxlen=10000)
last_block_number = None

# For tracking block latencies and new block times
block_latencies = deque(maxlen=5000)
block_numbers = deque(maxlen=5000)
average_new_block_times = deque(maxlen=5000)

# For tracking transaction counts and uncle counts
transaction_counts = deque(maxlen=5000)
uncle_counts = deque(maxlen=5000)

# For tracking difficulties
difficulties = deque(maxlen=5000)

# For tracking reorgs
reorg_counts = defaultdict(int)
new_block_count = 0
reorg_count = 0

# Create a figure for the plot
fig4, ax6 = plt.subplots()
fig4.canvas.manager.set_window_title('Main and Sibling Blocks per Miner')  # Set title for the window

# Create a new figure for new blocks and reorgs
fig5, ax7 = plt.subplots()
fig5.canvas.manager.set_window_title('New Blocks vs Reorgs')  # Set title for the window

# Enable interactive mode
plt.ion()

def plot_miner_blocks():
    ax6.clear()

    # Combine main and sibling blocks for sorting
    combined_blocks = {miner: main_blocks_per_miner[miner] + sibling_blocks_per_miner[miner] for miner in main_blocks_per_miner.keys()}

    # Sort miners by combined blocks in descending order
    sorted_miners = sorted(combined_blocks.keys(), key=lambda miner: combined_blocks[miner], reverse=True)

    main_blocks = [main_blocks_per_miner[miner] for miner in sorted_miners]
    sibling_blocks = [sibling_blocks_per_miner[miner] for miner in sorted_miners]

    x = np.arange(len(sorted_miners))
    width = 0.35

    bars1 = ax6.bar(x - width/2, main_blocks, width, label='Main Blocks')
    bars2 = ax6.bar(x + width/2, sibling_blocks, width, label='Sibling Blocks')

    ax6.set_xlabel('Miners')
    ax6.set_ylabel('Number of Blocks')
    ax6.set_title('Main and Sibling Blocks per Miner')
    ax6.set_xticks(x)
    ax6.set_xticklabels([coinbase_labels.get(miner, 'Unknown') for miner in sorted_miners], rotation=45, ha='right')
    ax6.legend()

    # Add labels to the bars
    for bar in bars1:
        height = bar.get_height()
        ax6.text(bar.get_x() + bar.get_width() / 2, height, f'{height}', ha='center', va='bottom')

    for bar in bars2:
        height = bar.get_height()
        ax6.text(bar.get_x() + bar.get_width() / 2, height, f'{height}', ha='center', va='bottom')

    fig4.canvas.draw()
    plt.draw()

def plot_new_blocks_vs_reorgs():
    ax7.clear()
    categories = ['New Blocks', 'Reorgs']
    values = [new_block_count, reorg_count]

    bars = ax7.bar(categories, values, color=['blue', 'red'])
    ax7.set_ylabel('Count')
    ax7.set_title('New Blocks vs Reorgs')

    # Add labels to the bars
    for bar in bars:
        height = bar.get_height()
        ax7.text(bar.get_x() + bar.get_width() / 2, height, f'{height}', ha='center', va='bottom')

    fig5.canvas.draw()
    plt.draw()

def process_line(line):
    global coinbase_counter, last_block_number, new_block_count, reorg_count
    if 'IMPORTED' in line:
        match = re.search(r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*block: num: \[(\d+)\].*hash:\s*\[([0-9a-fA-F]+)\],\s*parentHash:\[(\w+)\],\s*coinbase:\[(\w+)\],\s*uncles:\[([0-9a-fA-F, ]*)\],\s*difficulty:\[(\d+)\],\s*txs:\[(\d+)\],\s*timestamp:(\d+),.*result (IMPORTED_BEST|IMPORTED_NOT_BEST)', line)
        if match:
            log_time_str = match.group(1)
            block_number = int(match.group(2))
            hash_id = match.group(3)
            parent_hash_id = match.group(4)
            coinbase = match.group(5)
            uncles = [u.strip() for u in match.group(6).split(',') if u.strip()]
            difficulty = int(match.group(7))
            tx_count = int(match.group(8))
            timestamp = int(match.group(9))
            status = match.group(10)

            # Convert log timestamp to datetime and make it timezone-aware (GMT+3)
            log_time = datetime.strptime(log_time_str, "%Y-%m-%d-%H:%M:%S.%f")
            log_time = log_time.replace(tzinfo=timezone(timedelta(hours=3)))

            # Convert block timestamp to datetime and adjust from UTC to GMT+3
            block_time = datetime.utcfromtimestamp(timestamp)
            block_time_gmt3 = block_time - timedelta(hours=3)

            # Calculate mining time as the difference between log time and parent log time
            if parent_hash_id in G:
                parent_log_time = G.nodes[parent_hash_id]['log_time']
                mining_time = (log_time - parent_log_time).total_seconds()
            else:
                mining_time = 0

            # Track new block time when block number increments
            block_times.append(log_time)
            if last_block_number is None or block_number > last_block_number:
                new_block_times.append(log_time)
                last_block_number = block_number
                main_blocks_per_miner[coinbase] += 1
                new_block_count += 1
            else:
                sibling_blocks_per_miner[coinbase] += 1

            if coinbase not in coinbase_dict:
                coinbase_dict[coinbase] = coinbase_counter
                coinbase_counter += 1

            coinbase_index = coinbase_dict[coinbase]
            coinbase_counts[coinbase] += 1

            if hash_id not in G:
                G.add_node(hash_id, block_number=block_number, status=status, coinbase_index=coinbase_index, coinbase=coinbase, log_time=log_time, block_time_gmt3=block_time_gmt3, mining_time=mining_time, uncles=uncles, tx_count=tx_count)
            if parent_hash_id not in G:
                G.add_node(parent_hash_id, block_number=block_number-1, status='IMPORTED_NOT_BEST', coinbase_index=coinbase_index, coinbase=coinbase, log_time=log_time, block_time_gmt3=block_time_gmt3, mining_time=0, tx_count=0)  # Assuming parent block is one less
            G.add_edge(parent_hash_id, hash_id)

            # Add uncle nodes if they do not exist
            for uncle in uncles:
                if uncle not in G:
                    G.add_node(uncle, block_number=block_number, status='UNCLE', coinbase_index=coinbase_index, coinbase=coinbase, log_time=log_time, block_time_gmt3=block_time_gmt3, mining_time=mining_time, is_uncle=True, tx_count=0)
                G.add_edge(uncle, hash_id)

            prune_old_blocks(block_number)

            # Update block latencies, block numbers, transaction counts, uncle counts, and difficulties for the new graph
            block_latencies.append(mining_time)
            block_numbers.append(block_number)
            transaction_counts.append(tx_count)
            uncle_counts.append(len(uncles))
            difficulties.append(difficulty)
            if len(new_block_times) > 1:
                avg_new_block_time = sum((new_block_times[i] - new_block_times[i - 1]).total_seconds() for i in range(1, len(new_block_times))) / (len(new_block_times) - 1)
                average_new_block_times.append(avg_new_block_time)

            # Track reorgs
            if status == 'IMPORTED_BEST':
                reorg_counts[block_number] += 1
                if reorg_counts[block_number] > 1:
                    reorg_count += 1

            return True
    return False

def prune_old_blocks(current_block_number):
    blocks_to_remove = [node for node, data in G.nodes(data=True) if data['block_number'] < current_block_number - 9]
    for node in blocks_to_remove:
        G.remove_node(node)

def tail_log_file(file_path, q):
    process = subprocess.Popen(['tail', '-200000000000F', file_path], 
                             stdout=subprocess.PIPE, 
                             stderr=subprocess.PIPE, 
                             text=True)
    try:
        while True:
            line = process.stdout.readline()
            if not line:  # Si no hay línea, el archivo podría haberse cerrado
                break
            q.put(line)
    except Exception as e:
        print(f"Error leyendo el archivo de log: {e}")
    finally:
        process.terminate()  # Asegura que el proceso 'tail' se cierre correctamente

if __name__ == "__main__":
    # log_file = "../../logs/rsk.log"
    log_file = "../samples/rskj-2025-01-15.0.log"
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
        plot_miner_blocks()
        plot_new_blocks_vs_reorgs()
        plt.pause(0.1)