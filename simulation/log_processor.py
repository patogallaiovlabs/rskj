import re
from datetime import datetime, timezone, timedelta
import networkx as nx
from collections import defaultdict

# Initialize global variables
G = nx.DiGraph()
block_times = []
new_block_times = []
block_latencies = []
block_numbers = []
transaction_counts = []
uncle_counts = []
difficulties = []
average_new_block_times = []
coinbase_dict = {}
coinbase_counter = 0
coinbase_counts = defaultdict(int)
main_blocks_per_miner = defaultdict(int)
sibling_blocks_per_miner = defaultdict(int)
last_block_number = None
# Define the coinbase label map
coinbase_labels = {
    "12d3178a62ef1f520944534ed04504609f7307a1": "F2Pool",
    "4e5dabc28e4a0f5e5b19fcb56b28c5a1989352c1": "AntPool",
    "5aee2975e2ed688f231ccb40e20ee6c10a98d507": "Sec Pool",
    "08f6c90cfc462db10d4dd41fb1f2162ff854a462": "ViaBTC",
    "cf5072f792246690c75c63638e3d98bb2554ff2c": "Luxor",
    "0fd9b9b567a459c6c9645ab0847785aef13dfe1b": "SpiderPool",
}

def prune_old_blocks(current_block_number):
    blocks_to_remove = [node for node, data in G.nodes(data=True) if data['block_number'] < current_block_number - 9]
    for node in blocks_to_remove:
        G.remove_node(node)

def process_line(line):
    global coinbase_counter, last_block_number
    if 'IMPORTED' in line:
        match = re.search(r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*block: num: \[(\d+)\].*hash:\s*\[([0-9a-fA-F]+)\],\s*parentHash:\[(\w+)\],\s*coinbase:\[(\w+)\],\s*uncles:\[([0-9a-fA-F, ]*)\],\s*difficulty:\[(\d+)\],\s*txs:\[(\d+)\],\s*timestamp:(\d+),.*result (.*)', line)
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