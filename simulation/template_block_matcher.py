import socket
import json
import re
import threading
import os
from datetime import datetime, timezone, timedelta
from collections import defaultdict, deque
from pprint import pprint

# Pool details (reused from get_intervals.py)
pools = [
    {"name": "AntPool", "host": "ss.antpool.com", "port": 3333, "username": "patominer.001", "password": "x"},
    {"name": "ViaBTC", "host": "btc.viabtc.io", "port": 3333, "username": "pesbtc.001", "password": "123"},
    {"name": "SecPool", "host": "btc.secpool.com", "port": 3333, "username": "patominer.001'", "password": ""},
    {"name": "F2Pool", "host": "btc.f2pool.com", "port": 1314, "username": "patominer.001'", "password": "21235365876986800"},
    {"name": "Luxor", "host": "btc.global.luxor.tech", "port": 700, "username": "patominer.001", "password": "123"},
    {"name": "SpiderPool", "host": "btc-us.spiderpool.com", "port": 2309, "username": "patominer.001", "password": "123"}
]

# Global storage for events and matches
template_events = deque(maxlen=5)  # Recent template refresh events
block_events = deque(maxlen=5)     # Recent block events
matches = []                          # Matched events
total_blocks_global = 0              # Global block counter
matched_blocks = set()               # Track which blocks have been matched
first_template_after_block = {}      # Track first template after each block
blocks_by_miner = defaultdict(int)   # Track blocks by miner (coinbase)
matches_by_miner = defaultdict(lambda: defaultdict(int))  # Track matches by pool and miner
pool_stats = defaultdict(lambda: {
    "template_count": 0,
    "block_count": 0,
    "matches": 0,
    "total_time_diff": 0.0,
    "min_time_diff": float('inf'),
    "max_time_diff": 0.0,
    "first_template_response_times": [],  # Track response times for first templates
    "avg_first_template_response": 0.0,   # Average response time
    "same_miner_matches": 0,             # Matches with blocks from same miner
    "same_miner_blocks": 0               # Total blocks from same miner
})

# Thread-safe locks
template_lock = threading.Lock()
block_lock = threading.Lock()
match_lock = threading.Lock()

# Error margin for matching (2 seconds)
ERROR_MARGIN = 5  

def ensure_results_dir():
    """Create results directory if it doesn't exist"""
    results_dir = 'results'
    if not os.path.exists(results_dir):
        os.makedirs(results_dir)
    return results_dir

def get_output_file_name(base_name="template_block_matches", suffix="txt"):
    """Finds an available file name by appending an incrementing number."""
    results_dir = ensure_results_dir()
    index = 1
    while os.path.exists(os.path.join(results_dir, f"{base_name}_{index}.{suffix}")):
        index += 1
    return os.path.join(results_dir, f"{base_name}_{index}.{suffix}")

# Update file names
matches_file = get_output_file_name("matches")
timestamps_file = get_output_file_name("timestamps", "json")

def process_block_line(line):
    """Process a log line to extract block events (reused from log_processor.py)"""
    if 'IMPORTED_BEST' in line:
        match = re.search(r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*block: num: \[(\d+)\].*hash:\s*\[([0-9a-fA-F]+)\],\s*parentHash:\[(\w+)\],\s*coinbase:\[(\w+)\],\s*uncles:\[([0-9a-fA-F, ]*)\],\s*difficulty:\[(\d+)\],\s*txs:\[(\d+)\],\s*txsHashes:\[([0-9a-fA-F, ]*)\],\s*timestamp:(\d+),.*result (.*)', line)
        if match:
            log_time_str = match.group(1)
            block_number = int(match.group(2))
            hash_id = match.group(3)
            parent_hash_id = match.group(4)
            coinbase = match.group(5)
            uncles = [u.strip() for u in match.group(6).split(',') if u.strip()]
            difficulty = int(match.group(7))
            tx_count = int(match.group(8))
            tx_hashes = [tx.strip() for tx in match.group(9).split(',') if tx.strip()]
            timestamp = int(match.group(10))
            status = match.group(11)

            # Convert log timestamp to datetime (naive)
            log_time = datetime.strptime(log_time_str, "%Y-%m-%d-%H:%M:%S.%f")

            # Create block event
            block_event = {
                'timestamp': log_time,  # Keep in GMT+3 for comparison
                'block_number': block_number,
                'hash_id': hash_id,
                'coinbase': coinbase,
                'difficulty': difficulty,
                'tx_count': tx_count,
                'status': status,
                'log_time_str': log_time_str
            }
            
            with block_lock:
                block_events.append(block_event)
            
            # Increment global block counter
            global total_blocks_global
            total_blocks_global += 1
            
            # Increment blocks by miner
            blocks_by_miner[coinbase] += 1
            
            # Initialize tracking for first template after this block
            first_template_after_block[block_number] = {}
            
            # Find matches with existing template events
            with template_lock:
                for template_event in template_events:
                    time_diff = (template_event['timestamp'] - log_time).total_seconds()
                    # Only consider templates that come after the block (positive time_diff)
                    if 0 <= time_diff <= ERROR_MARGIN:
                        # Check if this block has already been matched
                        if block_number not in matched_blocks:
                            match = {
                                'template_event': template_event,
                                'block_event': block_event,
                                'time_diff': time_diff
                            }
                            
                            with match_lock:
                                matches.append(match)
                                matched_blocks.add(block_number)  # Mark block as matched
                                pool_name = template_event['pool_name']
                                pool_stats[pool_name]['matches'] += 1
                                pool_stats[pool_name]['total_time_diff'] += time_diff
                                pool_stats[pool_name]['min_time_diff'] = min(pool_stats[pool_name]['min_time_diff'], time_diff)
                                pool_stats[pool_name]['max_time_diff'] = max(pool_stats[pool_name]['max_time_diff'], time_diff)
                                
                                # Check if the block and template are from the same miner
                                if coinbase == template_event['coinbase']:
                                    pool_stats[pool_name]['same_miner_matches'] += 1
                                    pool_stats[pool_name]['same_miner_blocks'] += 1
            
            return True
    return False

def monitor_pool(pool):
    """Monitor the block template refresh time for a given pool (reused from get_intervals.py)"""
    while True:
        try:
            print(f"[{pool['name']}] Connecting to {pool['host']}...")
            sock = socket.create_connection((pool["host"], pool["port"]))
            print(f"[{pool['name']}] Connected.")

            # Send subscription request
            subscribe_request = json.dumps({"id": 1, "method": "mining.subscribe", "params": []}) + '\n'
            sock.sendall(subscribe_request.encode())
            response = sock.recv(8192).decode()
            #print(f"[{pool['name']}] Subscription response: {response.strip()}")

            # Send authorization request
            authorize_request = json.dumps({"id": 2, "method": "mining.authorize", "params": [pool["username"], pool["password"]]}) + '\n'
            sock.sendall(authorize_request.encode())
            response = sock.recv(16384).decode()
            #print(f"[{pool['name']}] Auth response: {response.strip()}")
            responses = response.strip().split('\n')
            resp_json = json.loads(responses[0])
            #print(f"[{pool['name']}] 1st Authorization response: {resp_json}")

            template_count = 0

            # Listen for incoming messages
            print(f"[{pool['name']}] Listening for template updates...")
            while template_count < 50:
                data = sock.recv(3276800).decode()
                if not data:
                    break

                # Check for 'mining.notify' messages
                if "mining.notify" in data:
                    template_count += 1
                    current_timestamp = datetime.now()
                    
                    template_event = {
                        'timestamp': current_timestamp,
                        'pool_name': pool['name'],
                        'template_count': template_count,
                        'original_utc_time': current_timestamp,
                        'coinbase': pool['username']  # Add coinbase for template events
                    }
                    
                    with template_lock:
                        template_events.append(template_event)
                    
                    # Update pool statistics
                    pool_stats[pool['name']]['template_count'] += 1
                    
                    # Check if this is the first template after any block
                    with block_lock:
                        for block_event in block_events:
                            # Only consider templates that come after the block
                            if current_timestamp > block_event['timestamp']:
                                block_number = block_event['block_number']
                                pool_name = pool['name']
                                
                                # Check if this is the first template from this pool after this block
                                if block_number in first_template_after_block and pool_name not in first_template_after_block[block_number]:
                                    time_diff = (current_timestamp - block_event['timestamp']).total_seconds()
                                    # Only consider templates that come after the block (positive time_diff)
                                    if time_diff >= 0:
                                        first_template_after_block[block_number][pool_name] = time_diff
                                        
                                        # Add to pool statistics
                                        pool_stats[pool_name]['first_template_response_times'].append(time_diff)
                                        pool_stats[pool_name]['avg_first_template_response'] = sum(pool_stats[pool_name]['first_template_response_times']) / len(pool_stats[pool_name]['first_template_response_times'])
                                        
                                        # If this is within ERROR_MARGIN, count it as a match
                                        if time_diff <= ERROR_MARGIN and block_number not in matched_blocks:
                                            match = {
                                                'template_event': template_event,
                                                'block_event': block_event,
                                                'time_diff': time_diff
                                            }
                                            
                                            with match_lock:
                                                matches.append(match)
                                                matched_blocks.add(block_number)  # Mark block as matched
                                                pool_stats[pool_name]['matches'] += 1
                                                pool_stats[pool_name]['total_time_diff'] += time_diff
                                                pool_stats[pool_name]['min_time_diff'] = min(pool_stats[pool_name]['min_time_diff'], time_diff)
                                                pool_stats[pool_name]['max_time_diff'] = max(pool_stats[pool_name]['max_time_diff'], time_diff)
                                                
                                                # Check if the block and template are from the same miner
                                                if block_event['coinbase'] == template_event['coinbase']:
                                                    pool_stats[pool_name]['same_miner_matches'] += 1
                                        
                                        if time_diff < ERROR_MARGIN:
                                            print(f"DEBUG[{pool['name']}] First template after block {block_number} from {pool_name} in {time_diff} seconds")

            print(f"[{pool['name']}] Reached 50 templates, waiting 10 seconds before reconnecting...")
            sock.close()
            threading.Event().wait(10)

        except Exception as e:
            print(f"[{pool['name']}] Error: {e}")
            threading.Event().wait(10)

def print_statistics():
    """Print real-time statistics"""
    while True:
        print("\n" + "="*80)
        print("📊 REAL-TIME STATISTICS")
        print("="*80)
        
        total_templates = sum(stats['template_count'] for stats in pool_stats.values())
        total_blocks = total_blocks_global
        total_matches = sum(stats['matches'] for stats in pool_stats.values())
        
        print(f"Total Template Events: {total_templates}")
        print(f"Total Block Events: {total_blocks}")
        print(f"Total Matches: {total_matches}")
        print(f"Overall Template Match Rate: {(total_matches/total_templates*100):.2f}%" if total_templates > 0 else "Overall Template Match Rate: 0.00%")
        print(f"Overall Block Match Rate: {(total_matches/total_blocks*100):.2f}%" if total_blocks > 0 else "Overall Block Match Rate: 0.00%")
        print()
        
        print("Pool Statistics:")
        print("-" * 80)
        # Sort pools by matches (highest to lowest)
        sorted_pools = sorted(pool_stats.items(), key=lambda x: x[1]['matches'], reverse=True)
        for pool_name, stats in sorted_pools:
            if stats['template_count'] > 0 or total_blocks_global > 0:
                same_miner_block_rate = (stats['same_miner_matches'] / stats['same_miner_blocks'] * 100) if stats['same_miner_blocks'] > 0 else 0
                block_match_rate = (stats['matches'] / total_blocks_global * 100) if total_blocks_global > 0 else 0
                avg_time_diff = stats['total_time_diff'] / stats['matches'] if stats['matches'] > 0 else 0
                min_time_diff = stats['min_time_diff'] if stats['min_time_diff'] != float('inf') else 0
                max_time_diff = stats['max_time_diff']
                avg_response_time = stats['avg_first_template_response']
                response_count = len(stats['first_template_response_times'])
                
                print(f"{pool_name:12} | Templates: {stats['template_count']:3} | Blocks: {total_blocks_global:3} | "
                      f"Matches: {stats['matches']:3} | Same Miner Rate: {same_miner_block_rate:5.1f}% | "
                      f"Block Rate: {block_match_rate:5.1f}% | Avg Diff: {avg_time_diff:5.2f}s | "
                      f"Avg Response: {avg_response_time:5.2f}s ({response_count})")
        
        print("\nRecent Matches:")
        print("-" * 60)
        with match_lock:
            recent_matches = matches[-5:]  # Show last 5 matches
            for i, match in enumerate(reversed(recent_matches)):
                template = match['template_event']
                block = match['block_event']
                print(f"{i+1}. {template['pool_name']} template → Block {block['block_number']} "
                      f"(diff: {match['time_diff']:.2f}s)")
                print(f"    Template Log TZ: {template['timestamp']} | Block Log TZ: {block['timestamp']}")
        
        print("\n" + "="*80)
        threading.Event().wait(10)  # Update every 10 seconds

def monitor_log_file(log_file_path):
    """Monitor a log file for block events"""
    print(f"📋 Monitoring log file: {log_file_path}")
    
    # If file doesn't exist, create a dummy one or wait for it
    if not os.path.exists(log_file_path):
        print(f"⚠️  Log file {log_file_path} not found. Waiting for it to be created...")
        while not os.path.exists(log_file_path):
            threading.Event().wait(1)
    
    with open(log_file_path, 'r') as f:
        # Go to end of file
        f.seek(0, 2)
        
        while True:
            line = f.readline()
            if line:
                process_block_line(line)
            else:
                threading.Event().wait(0.1)  # Small delay when no new lines

if __name__ == "__main__":
    # Get log file path from user or use default
    log_file_path = input("Enter the path to the log file to monitor (or press Enter for default): ").strip()
    if not log_file_path:
        log_file_path = "../logs/rsk.log"  # Default log file name
    
    threads = []
    
    # Start monitoring each pool in a separate thread
    for pool in pools:
        thread = threading.Thread(target=monitor_pool, args=(pool,))
        thread.daemon = True
        thread.start()
        threads.append(thread)
    
    # Start log file monitoring thread
    log_thread = threading.Thread(target=monitor_log_file, args=(log_file_path,))
    log_thread.daemon = True
    log_thread.start()
    threads.append(log_thread)
    
    # Start statistics printing thread
    stats_thread = threading.Thread(target=print_statistics)
    stats_thread.daemon = True
    stats_thread.start()
    threads.append(stats_thread)
    
    try:
        # Keep main thread alive
        while True:
            threading.Event().wait(1)
    except KeyboardInterrupt:
        print("\n💾 Saving results to files...")
        
        # Save matches to txt file
        with open(matches_file, "w") as f:
            f.write("Template Refresh to Block Event Matches\n")
            f.write("="*50 + "\n\n")
            
            for i, match in enumerate(matches):
                template = match['template_event']
                block = match['block_event']
                f.write(f"Match {i+1}:\n")
                f.write(f"  Pool: {template['pool_name']}\n")
                f.write(f"  Template Time: {template['timestamp']}\n")
                f.write(f"  Block Number: {block['block_number']}\n")
                f.write(f"  Block Time: {block['timestamp']}\n")
                f.write(f"  Time Difference: {match['time_diff']:.2f} seconds\n")
                f.write(f"  Block Hash: {block['hash_id']}\n")
                f.write(f"  Coinbase: {block['coinbase']}\n")
                f.write("-" * 30 + "\n")
        
        # Save timestamps to JSON file
        timestamps_data = {
            'template_events': [
                {
                    'pool_name': event['pool_name'],
                    'timestamp': event['timestamp'].isoformat(),
                    'template_count': event['template_count']
                }
                for event in template_events
            ],
            'block_events': [
                {
                    'block_number': event['block_number'],
                    'hash_id': event['hash_id'],
                    'coinbase': event['coinbase'],
                    'timestamp': event['timestamp'].isoformat(),
                    'log_time_str': event['log_time_str']
                }
                for event in block_events
            ],
            'matches': [
                {
                    'template_pool': match['template_event']['pool_name'],
                    'template_time': match['template_event']['timestamp'].isoformat(),
                    'block_number': match['block_event']['block_number'],
                    'block_time': match['block_event']['timestamp'].isoformat(),
                    'time_diff': match['time_diff']
                }
                for match in matches
            ],
            'pool_statistics': {
                pool_name: {
                    'template_count': stats['template_count'],
                    'block_count': total_blocks_global,
                    'matches': stats['matches'],
                    'same_miner_block_rate': (stats['same_miner_matches'] / stats['same_miner_blocks'] * 100) if stats['same_miner_blocks'] > 0 else 0,
                    'block_match_rate': (stats['matches'] / total_blocks_global * 100) if total_blocks_global > 0 else 0,
                    'avg_time_diff': (stats['total_time_diff'] / stats['matches']) if stats['matches'] > 0 else 0,
                    'min_time_diff': stats['min_time_diff'] if stats['min_time_diff'] != float('inf') else 0,
                    'max_time_diff': stats['max_time_diff'],
                    'avg_first_template_response': stats['avg_first_template_response'],
                    'first_template_response_count': len(stats['first_template_response_times']),
                    'first_template_response_times': stats['first_template_response_times'],
                    'same_miner_matches': stats['same_miner_matches'],
                    'same_miner_blocks': stats['same_miner_blocks']
                }
                for pool_name, stats in pool_stats.items()
            }
        }
        
        with open(timestamps_file, "w") as f:
            json.dump(timestamps_data, f, indent=2)
        
        print(f"✅ Matches saved to {matches_file}")
        print(f"✅ Timestamps saved to {timestamps_file}")
        print("👋 Exiting...") 