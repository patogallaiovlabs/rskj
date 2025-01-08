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
    "cf5072f792246690c75c63638e3d98bb2554ff2c": "Luxor",
    "0fd9b9b567a459c6c9645ab0847785aef13dfe1b": "SpiderPool",
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

# Create separate figures for each plot
fig1, ax1 = plt.subplots()
fig2, (ax2, ax3, ax4) = plt.subplots(3, 1, figsize=(10, 15))
fig3, ax5 = plt.subplots()  # New figure for uncle distribution
fig4, ax6 = plt.subplots()  # New figure for main and sibling blocks per miner
fig5, ax7 = plt.subplots()  # New figure for total main vs sibling blocks

# Set window titles
fig1.canvas.manager.set_window_title('Mining graph')
fig2.canvas.manager.set_window_title('Stats')
fig3.canvas.manager.set_window_title('Uncle Distribution')  # Set title for new window
fig4.canvas.manager.set_window_title('Main and Sibling Blocks per Miner')  # Set title for new window
fig5.canvas.manager.set_window_title('Total Main vs Sibling Blocks')

# Enable interactive mode
plt.ion()

def calculate_average_block_time():
    """
    Calculates the average block time from the block_times deque.
    """
    if len(block_times) < 2:
        return None
    total_time = sum((block_times[i] - block_times[i - 1]).total_seconds() for i in range(1, len(block_times)))
    average_time = total_time / (len(block_times) - 1)
    return average_time

def plot_graph():
    ax1.clear()
    pos = {}
    row_indices = {}
    total_blocks = sum(coinbase_counts.values())

    # Compute positions such that each column represents a block number
    for node, data in G.nodes(data=True):
        block_number = data['block_number']
        if block_number not in row_indices:
            row_indices[block_number] = 0
        else:
            row_indices[block_number] += 1
        pos[node] = (-block_number, row_indices[block_number] * 2)

    labels = {node: f"{data['block_number']}\n{coinbase_labels.get(data['coinbase'], 'Unknown')}\nlog {data['log_time'].strftime('%H:%M:%S')}\nBlock Time: {data['block_time_gmt3'].strftime('%H:%M:%S')}\nMining Time: {data['mining_time']:.2f}s\nTxs: {data['tx_count']}" for node, data in G.nodes(data=True)}

    # Draw nodes with different shapes and colors
    for coinbase_index in set(data['coinbase_index'] for node, data in G.nodes(data=True)):
        nodes = [node for node, data in G.nodes(data=True) if data['coinbase_index'] == coinbase_index]
        colors = ['white' if G.nodes[node]['status'] == 'IMPORTED_BEST' else 'gray' for node in nodes]
        edgecolors = ['black' if G.nodes[node]['status'] == 'IMPORTED_BEST' else 'none' for node in nodes]
        nx.draw_networkx_nodes(G, pos, nodelist=nodes, node_shape=shapes[coinbase_index % len(shapes)], node_color=colors, edgecolors=edgecolors, node_size=NODE_SIZE, ax=ax1)

    # Draw yellow nodes for uncles
    uncle_nodes = [node for node, data in G.nodes(data=True) if data.get('is_uncle', False)]
    nx.draw_networkx_nodes(G, pos, nodelist=uncle_nodes, node_color='yellow', node_size=NODE_SIZE, ax=ax1)

    nx.draw_networkx_edges(G, pos, ax=ax1)
    nx.draw_networkx_labels(G, pos, labels=labels, font_size=6, ax=ax1)

    # Draw dashed lines for uncles
    edges = [(uncle, node) for node in G.nodes for uncle in G.nodes[node].get('uncles', []) if uncle in G.nodes]
    nx.draw_networkx_edges(G, pos, edgelist=edges, ax=ax1, style='dashed', edge_color='blue')

    # Add legend for coinbase dictionary with counts and percentages
    legend_text = "\n".join([f"{coinbase_labels.get(coinbase, 'Unknown')}: {coinbase} ({coinbase_counts[coinbase]} blocks, {coinbase_counts[coinbase] / total_blocks:.2%})" for coinbase, index in coinbase_dict.items()])
    props = dict(boxstyle='round', facecolor='wheat', alpha=0.5)
    ax1.text(0.75, 1, legend_text, transform=ax1.transAxes, fontsize=8, verticalalignment='top', bbox=props)

    # Calculate and display average block arrival time
    average_block_time = calculate_average_block_time()
    if average_block_time is not None:
        ax1.text(0.01, 0.01, f"Avg Block Time: {average_block_time:.2f}s", transform=ax1.transAxes, fontsize=8, verticalalignment='bottom')

    # Calculate and display average new block time (when block number increments)
    if len(new_block_times) > 1:
        total_new_block_time = sum((new_block_times[i] - new_block_times[i - 1]).total_seconds() for i in range(1, len(new_block_times)))
        average_new_block_time = total_new_block_time / (len(new_block_times) - 1)
        ax1.text(0.01, 0.06, f"Avg New Block Time: {average_new_block_time:.2f}s", transform=ax1.transAxes, fontsize=8, verticalalignment='bottom')

    fig1.canvas.draw()
    plt.draw()

def plot_time_graph():
    ax2.clear()
    ax3.clear()
    ax4.clear()
    ax5.clear()

    # Ensure all lists have the same length and are not empty
    block_nums = list(block_numbers)
    latencies = list(block_latencies)
    avg_new_block_times = list(average_new_block_times)
    tx_counts = list(transaction_counts)
    uncle_counts_list = list(uncle_counts)
    difficulty_vals = list(difficulties)

    # Ensure all lists have the same length
    min_length = min(len(block_nums), len(latencies), len(avg_new_block_times), 
                    len(tx_counts), len(uncle_counts_list), len(difficulty_vals))
    
    if min_length == 0:
        return  # Exit if no data

    block_nums = block_nums[:min_length]
    latencies = latencies[:min_length]
    avg_new_block_times = avg_new_block_times[:min_length]
    tx_counts = tx_counts[:min_length]
    uncle_counts_list = uncle_counts_list[:min_length]
    difficulty_vals = difficulty_vals[:min_length]

    # Create smoothed curves using exponential moving average
    def exp_moving_average(data, alpha=0.1):
        if not data:
            return []
        result = [data[0]]
        for n in range(1, len(data)):
            result.append(alpha * data[n] + (1 - alpha) * result[n-1])
        return result

    # Ensure we have positive values for log scale
    min_positive = 0.1  # Minimum positive value
    plot_latencies = [max(l, min_positive) for l in latencies]
    
    # Plot original data with low opacity
    ax2.plot(block_nums, plot_latencies, label='Block Latency', color='red', alpha=0.2)
    
    # Plot smoothed data with full opacity
    smoothed_latencies = exp_moving_average(plot_latencies)
    if smoothed_latencies:
        ax2.plot(block_nums, smoothed_latencies, label='Block Latency (trend)', 
                color='red', linewidth=2)

    # Set logarithmic scale and ensure positive values
    ax2.set_yscale('log')
    ax2.plot(block_nums, [max(t, min_positive) for t in avg_new_block_times], 
             label='Avg New Block Time', color='green')
    ax2.plot(block_nums, [max(t, min_positive) for t in tx_counts], 
             label='Transaction Count', color='blue')
    ax2.plot(block_nums, [max(t, min_positive) for t in uncle_counts_list], 
             label='Uncle Count', color='purple')

    ax2.set_xlabel('Block Number')
    ax2.set_ylabel('Time (seconds) / Count (log scale)')
    ax2.legend()
    ax2.grid(True, which="both", ls="-", alpha=0.2)

    # Plot difficulty values with matching dimensions
    if difficulty_vals:
        ax4.plot(block_nums, [int(str(d)[:4]) for d in difficulty_vals], 
                 label='Difficulty', color='orange')
        ax4.set_xlabel('Block Number')
        ax4.set_ylabel('Difficulty (first 4 digits)')
        ax4.set_title('Block Difficulty Over Time')
        ax4.legend()

    # Calculate histogram for mining times
    mining_times = np.array(latencies)
    if mining_times.size > 0:
        bins = range(0, int(mining_times.max()) + 2, 2)
        hist, bin_edges = np.histogram(mining_times, bins=bins)

        # Plot histogram
        ax3.bar(bin_edges[:-1], hist, width=2, color='blue', alpha=0.7)

        # Calculate percentiles
        percentiles = [50, 70, 90]
        percentile_values = np.percentile(mining_times, percentiles)

        # Add vertical lines for percentiles
        colors = ['red', 'green', 'orange']
        for perc, value, color in zip(percentiles, percentile_values, colors):
            ax3.axvline(value, color=color, linestyle='dashed', linewidth=1, label=f'{perc}th Percentile')

        ax3.legend()
    else:
        print("No data available for mining times.")
    ax3.set_xlabel('Mining Time (seconds)')
    ax3.set_ylabel('Number of Blocks')
    ax3.set_title('Distribution of Mining Times')

    # Plot uncle distribution in the new window
    if uncle_counts_list:
        bins = range(0, max(uncle_counts_list) + 2)
        ax5.hist(uncle_counts_list, bins=bins, color='purple', alpha=0.7)
        ax5.set_xlabel('Number of Uncles')
        ax5.set_ylabel('Frequency')
        ax5.set_title('Distribution of Uncle Counts')
    else:
        print("No data available for uncle counts.")

    fig2.canvas.draw()
    fig3.canvas.draw()  # Draw the new figure
    plt.draw()

def plot_miner_blocks():
    ax6.clear()

    # Combine main and sibling blocks for sorting
    combined_blocks = {miner: main_blocks_per_miner[miner] + sibling_blocks_per_miner[miner] for miner in main_blocks_per_miner.keys()}

    # Sort miners by combined blocks in descending order
    sorted_miners = sorted(combined_blocks.keys(), key=lambda miner: combined_blocks[miner], reverse=True)

    main_blocks = [main_blocks_per_miner[miner] for miner in sorted_miners]
    sibling_blocks = [sibling_blocks_per_miner[miner] for miner in sorted_miners]

    x = np.arange(len(sorted_miners))
    width = 0.25

    bars1 = ax6.bar(x - width/2, main_blocks, width, label='Main Blocks')
    bars2 = ax6.bar(x + width/2, sibling_blocks, width, label='Sibling Blocks')

    ax6.set_xlabel('Miners')
    ax6.set_ylabel('Number of Blocks')
    ax6.set_title('Main and Sibling Blocks per Miner')
    ax6.set_xticks(x)
    ax6.set_xticklabels([coinbase_labels.get(miner, 'Unknown') for miner in sorted_miners], rotation=20, ha='right')
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

def plot_total_blocks():
    ax7.clear()

    # Calculate totals
    total_main = sum(main_blocks_per_miner.values())
    total_siblings = sum(sibling_blocks_per_miner.values())

    # Create bar plot
    bars = ax7.bar(['Main Chain', 'Siblings'], [total_main, total_siblings])
    
    # Add value labels on top of bars
    for bar in bars:
        height = bar.get_height()
        ax7.text(bar.get_x() + bar.get_width()/2., height,
                 f'{int(height)}',
                 ha='center', va='bottom')

    ax7.set_title('Total Main Chain vs Sibling Blocks')
    ax7.set_ylabel('Number of Blocks')

    # Add percentage labels
    total_blocks = total_main + total_siblings
    if total_blocks > 0:
        main_percentage = (total_main / total_blocks) * 100
        sibling_percentage = (total_siblings / total_blocks) * 100
        ax7.text(0, height, f'{main_percentage:.1f}%', ha='center', va='bottom', transform=ax7.get_xaxis_transform())
        ax7.text(1, height, f'{sibling_percentage:.1f}%', ha='center', va='bottom', transform=ax7.get_xaxis_transform())

    fig5.canvas.draw()
    plt.draw()

def process_line(line):
    global coinbase_counter, last_block_number
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

            return True
    return False

def prune_old_blocks(current_block_number):
    blocks_to_remove = [node for node, data in G.nodes(data=True) if data['block_number'] < current_block_number - 9]
    for node in blocks_to_remove:
        G.remove_node(node)

def tail_log_file(file_path, q):
    process = subprocess.Popen(['tail', '-100000F', file_path], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    while True:
        line = process.stdout.readline()
        if line:
            q.put(line)

if __name__ == "__main__":
    log_file = "../logs/rsk.log"
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
            if process_line(line):
                plot_graph()
                plot_time_graph()
                plot_miner_blocks()
                plot_total_blocks()
        plt.pause(0.1)