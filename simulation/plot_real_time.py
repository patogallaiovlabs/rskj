import matplotlib.pyplot as plt
import networkx as nx
import subprocess
import threading
import re
import os
import queue
from datetime import datetime, timedelta, timezone
from collections import defaultdict, deque
from log_utils import tail_log_file
from log_processor import process_line, main_blocks_per_miner, sibling_blocks_per_miner, coinbase_counter, coinbase_counts, coinbase_dict, coinbase_labels, block_times, new_block_times, last_block_number, block_latencies, block_numbers, average_new_block_times, transaction_counts, uncle_counts, difficulties, G
import numpy as np

shapes = ['o', 's', '^', 'D', 'v', 'h', 'p', '8']  # Different shapes for different coinbases
# Define the coinbase label map

NODE_SIZE = 4000


# Create separate figures for each plot
fig1, ax1 = plt.subplots()
fig2, ax2 = plt.subplots()
fig3, ax5 = plt.subplots()  # New figure for uncle distribution
fig5, ax7 = plt.subplots()  # New figure for total main vs sibling blocks

# Set window titles
fig1.canvas.manager.set_window_title('Mining graph')
fig2.canvas.manager.set_window_title('Stats')
fig3.canvas.manager.set_window_title('Uncle Distribution')  # Set title for new window
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
    nx.draw_networkx_edges(G, pos, edgelist=edges, ax=ax1, style='dashed', edge_color='red')

    # Add legend for coinbase dictionary with counts and percentages
    legend_text = "\n".join([f"{coinbase_labels.get(coinbase, 'Unknown')}: {coinbase} ({coinbase_counts[coinbase]} blocks, {coinbase_counts[coinbase] / total_blocks:.2%})" for coinbase, index in coinbase_dict.items()])
    props = dict(boxstyle='round', facecolor='wheat', alpha=0.5)
    ax1.text(.5, 1.1, legend_text, transform=ax1.transAxes, fontsize=8, verticalalignment='top', bbox=props)

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
    ax5.clear()

    # Ensure all lists have the same length and are not empty
    block_nums = list(block_numbers)
    latencies = list(block_latencies)
    avg_new_block_times = list(average_new_block_times)
    tx_counts = list(transaction_counts)
    uncle_counts_list = list(uncle_counts)

    # Ensure all lists have the same length
    min_length = min(len(block_nums), len(latencies), len(avg_new_block_times), 
                    len(tx_counts), len(uncle_counts_list))
    
    if min_length == 0:
        return  # Exit if no data

    block_nums = block_nums[:min_length]
    latencies = latencies[:min_length]
    avg_new_block_times = avg_new_block_times[:min_length]
    tx_counts = tx_counts[:min_length]
    uncle_counts_list = uncle_counts_list[:min_length]

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

def plot_histogram_with_percentiles(block_times):
    """
    Plot histogram of block times with percentiles in an independent window
    """
    percentiles = [50, 75, 90, 95, 99]
    percentile_values = np.percentile(block_times, percentiles)
    
    plt.figure()
    plt.hist(block_times, bins=50, edgecolor='black', alpha=0.7)
    plt.title('Histogram of Block Times with Percentiles')
    plt.xlabel('Block Time (seconds)')
    plt.ylabel('Frequency')
    
    for percentile, value in zip(percentiles, percentile_values):
        plt.axvline(value, color='r', linestyle='dashed', linewidth=1)
        plt.text(value, plt.ylim()[1] * 0.9, f'{percentile}th: {value:.2f}s', color='r', rotation=90, verticalalignment='center')
    
    plt.show()

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

def prune_old_blocks(current_block_number):
    blocks_to_remove = [node for node, data in G.nodes(data=True) if data['block_number'] < current_block_number - 9]
    for node in blocks_to_remove:
        G.remove_node(node)


if __name__ == "__main__":
    log_file = "../logs/rsk.log"
    #log_file = "samples/rskj-2025-01-15.0.log"
    #log_file = "../logs/rskj-2025-01-22.0.log"  # Adjust path as needed
    
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    # Keep the plot open and process log updates in the main thread
    result = False
    print("Starting main loop")
    while True: 
        while not q.empty():
            line = q.get()
            result |= process_line(line)
        if result:
            print("Plotting graphs")
            plot_graph()
            plot_time_graph()
            plot_total_blocks()
            result = False
        plt.pause(0.1)