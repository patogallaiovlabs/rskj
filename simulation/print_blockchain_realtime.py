#!/usr/bin/env python3
"""
Real-time blockchain collection printer

This script monitors log files and prints all elements in the blockchain collection
as new blocks are added. It provides a comprehensive view of all blockchain data
in real-time.
"""

import subprocess
import threading
import queue
import os
import sys
from datetime import datetime

# Import the log processor to reuse its logic
from log_processor import (
    process_line, 
    blockchain, 
    coinbase_dict, 
    coinbase_counts, 
    main_blocks_per_miner, 
    sibling_blocks_per_miner,
    coinbase_labels
)

# Color codes for different miners (ANSI escape codes)
miner_colors = {
    "12d3178a62ef1f520944534ed04504609f7307a1": "\033[91m",  # Red - F2Pool
    "4e5dabc28e4a0f5e5b19fcb56b28c5a1989352c1": "\033[92m",  # Green - AntPool
    "5aee2975e2ed688f231ccb40e20ee6c10a98d507": "\033[93m",  # Yellow - Sec Pool
    "93293a100338f54242f05652e137a52e0acdccf0": "\033[94m",  # Blue - ViaBTC
    "cf5072f792246690c75c63638e3d98bb2554ff2c": "\033[95m",  # Magenta - Luxor
    "0fd9b9b567a459c6c9645ab0847785aef13dfe1b": "\033[96m",  # Cyan - SpiderPool
    "ce7864a8b5bf360b01099502a163810cec845d4a": "\033[97m",  # White - Foundry USA
}

# Icons for different miners
miner_icons = {
    "12d3178a62ef1f520944534ed04504609f7307a1": "🔥",  # F2Pool
    "4e5dabc28e4a0f5e5b19fcb56b28c5a1989352c1": "🐜",  # AntPool
    "5aee2975e2ed688f231ccb40e20ee6c10a98d507": "🛡️",  # Sec Pool
    "93293a100338f54242f05652e137a52e0acdccf0": "🌊",  # ViaBTC
    "cf5072f792246690c75c63638e3d98bb2554ff2c": "💎",  # Luxor
    "0fd9b9b567a459c6c9645ab0847785aef13dfe1b": "🕷️",  # SpiderPool
    "ce7864a8b5bf360b01099502a163810cec845d4a": "🏭",  # Foundry USA
}

# Reset color code
RESET_COLOR = "\033[0m"
# Bold formatting
BOLD = "\033[1m"

def get_coinbase_label(coinbase):
    """Get human-readable label for coinbase address"""
    return coinbase_labels.get(coinbase, f"Unknown ({coinbase[:8]}...)")

def get_miner_display(coinbase):
    """Get colored and iconized miner display"""
    color = miner_colors.get(coinbase, "\033[90m")  # Gray for unknown miners
    icon = miner_icons.get(coinbase, "❓")  # Question mark for unknown miners
    label = get_coinbase_label(coinbase)
    return f"{color}{icon} {label}{RESET_COLOR}"

def format_blockchain_summary():
    """Format a summary of the current blockchain collection"""
    if not blockchain:
        return "No blocks in blockchain collection yet."
    
    summary = []
    summary.append(f"\n{'='*80}")
    summary.append(f"BLOCKCHAIN COLLECTION SUMMARY - {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    summary.append(f"{'='*80}")
    summary.append(f"Total blocks in collection: {len(blockchain)}")
    
    # Group blocks by block number
    from collections import defaultdict
    blocks_by_number = defaultdict(list)
    for hash_id, block_data in blockchain.items():
        blocks_by_number[block_data['block_number']].append(block_data)
    
    # Print blocks organized by block number
    for block_number in sorted(blocks_by_number.keys()):
        blocks = blocks_by_number[block_number]
        summary.append(f"\nBlock Number: {block_number} ({len(blocks)} block(s) at this height)")
        summary.append("-" * 60)
        
        for i, block in enumerate(blocks):
            miner_display = get_miner_display(block['coinbase'])
            status_indicator = "🌟 MAIN" if block['is_first_block_for_height'] else "📄 SIBLING"
            
            summary.append(f"  {i+1}. {status_indicator} - Hash: {block['hash_id'][:16]}...")
            summary.append(f"      Parent: {block['parent_hash_id'][:16]}...")
            summary.append(f"      Miner: {miner_display}")
            summary.append(f"      Status: {block['status']}")
            summary.append(f"      Log Time: {block['log_time'].strftime('%Y-%m-%d %H:%M:%S.%f')}")
            summary.append(f"      Block Time: {block['mining_time'].strftime('%Y-%m-%d %H:%M:%S')}")
            summary.append(f"      Mining Latency: {block['block_latency']:.2f}s")
            summary.append(f"      Difficulty: {block['difficulty']:,}")
            summary.append(f"      Transactions: {block['tx_count']}")
            summary.append(f"      Uncles: {len(block['uncles'])}")
            
            if block['time_diff_from_first'] > 0:
                summary.append(f"      {BOLD}Time from first: +{block['time_diff_from_first']:.2f}s{RESET_COLOR}")
            
            if block['uncles']:
                summary.append(f"      Uncle hashes: {', '.join([u[:8] + '...' for u in block['uncles']])}")
            
            if block['tx_hashes']:
                summary.append(f"      Transaction hashes:")
                for i, tx_hash in enumerate(block['tx_hashes'], 1):
                    summary.append(f"        {i:2d}. {tx_hash}")
            
            summary.append("")
    
    # Add statistics
    summary.append(f"\n{'='*80}")
    summary.append("STATISTICS")
    summary.append(f"{'='*80}")
    
    # Miner statistics
    summary.append("\nMiner Statistics:")
    for coinbase, count in coinbase_counts.items():
        miner_display = get_miner_display(coinbase)
        main_count = main_blocks_per_miner[coinbase]
        sibling_count = sibling_blocks_per_miner[coinbase]
        summary.append(f"  {miner_display}: {count} total ({main_count} main, {sibling_count} siblings)")
    
    # Block height statistics
    if blocks_by_number:
        summary.append(f"\nBlock Height Range: {min(blocks_by_number.keys())} - {max(blocks_by_number.keys())}")
        summary.append(f"Total Heights: {len(blocks_by_number)}")
        
        # Calculate average blocks per height
        avg_blocks_per_height = len(blockchain) / len(blocks_by_number)
        summary.append(f"Average blocks per height: {avg_blocks_per_height:.2f}")
    
    return "\n".join(summary)

def print_new_block(block_data):
    """Print detailed information about a newly added block"""
    miner_display = get_miner_display(block_data['coinbase'])
    status_indicator = "🌟 NEW MAIN BLOCK" if block_data['is_first_block_for_height'] else "📄 NEW SIBLING BLOCK"
    
    print(f"\n{'='*80}")
    print(f"{status_indicator} ADDED TO BLOCKCHAIN COLLECTION")
    print(f"{'='*80}")
    print(f"Block Number: {block_data['block_number']}")
    print(f"Hash: {block_data['hash_id']}")
    print(f"Parent Hash: {block_data['parent_hash_id']}")
    print(f"Miner: {miner_display}")
    print(f"Status: {block_data['status']}")
    print(f"Log Time: {block_data['log_time'].strftime('%Y-%m-%d %H:%M:%S.%f')}")
    print(f"Block Time: {block_data['mining_time'].strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"Mining Latency: {block_data['block_latency']:.2f}s")
    print(f"Difficulty: {block_data['difficulty']:,}")
    print(f"Transactions: {block_data['tx_count']}")
    print(f"Uncles: {len(block_data['uncles'])}")
    
    if block_data['time_diff_from_first'] > 0:
        print(f"{BOLD}Time from first block at this height: +{block_data['time_diff_from_first']:.2f}s{RESET_COLOR}")
    
    if block_data['uncles']:
        print(f"Uncle hashes: {', '.join(block_data['uncles'])}")
    
    if block_data['tx_hashes']:
        print(f"Transaction hashes:")
        for i, tx_hash in enumerate(block_data['tx_hashes'], 1):
            print(f"  {i:2d}. {tx_hash}")

def process_line_with_printing(line):
    """Process a log line and print blockchain updates"""
    # Store the current blockchain size before processing
    previous_size = len(blockchain)
    
    # Use the imported process_line function
    result = process_line(line)
    
    if result:
        # Check if a new block was added
        if len(blockchain) > previous_size:
            # Find the newly added block
            new_blocks = [block_data for hash_id, block_data in blockchain.items() 
                         if len(blockchain) == previous_size + 1 or 
                         (len(blockchain) > previous_size + 1 and 
                          block_data['log_time'] == max(b['log_time'] for b in blockchain.values()))]
            
            if new_blocks:
                # Print the most recently added block
                newest_block = max(new_blocks, key=lambda x: x['log_time'])
                print_new_block(newest_block)
                
                # Print updated summary
                print(format_blockchain_summary())
    
    return result

def tail_log_file(file_path, q):
    """Tail the log file and put lines in the queue"""
    try:
        process = subprocess.Popen(['tail', '-1000000000000F', file_path], 
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        while True:
            line = process.stdout.readline()
            if line:
                q.put(line)
    except KeyboardInterrupt:
        print("\nStopping log file monitoring...")
        process.terminate()

def main():
    """Main function to run the blockchain printer"""
    print("Real-time Blockchain Collection Printer")
    print("=" * 50)
    
    # Default log file path
    log_file = "../logs/rsk.log"
    
    # Check if log file exists
    log_file_path = os.path.abspath(log_file)
    if not os.path.exists(log_file_path):
        print(f"Log file not found: {log_file_path}")
        print("Please specify the correct path to your RSK log file.")
        return
    
    print(f"Monitoring log file: {log_file_path}")
    print("Press Ctrl+C to stop monitoring")
    print("=" * 50)
    
    # Create queue for log lines
    q = queue.Queue()

    # Create a thread to tail the log file
    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    try:
        # Process log updates in the main thread
        while True:
            while not q.empty():
                line = q.get()
                process_line_with_printing(line)
            
            # Small delay to prevent excessive CPU usage
            import time
            time.sleep(0.1)
            
    except KeyboardInterrupt:
        print("\n\nFinal blockchain collection summary:")
        print(format_blockchain_summary())
        print("\nMonitoring stopped.")

if __name__ == "__main__":
    main() 