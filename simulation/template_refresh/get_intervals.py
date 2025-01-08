import socket
import json
from datetime import datetime, timezone
import threading
import os

# Pool details (you can add more pools here)
pools = [
    {"name": "AntPool", "host": "ss.antpool.com", "port": 3333, "username": "patominer.001", "password": "x"},
    {"name": "ViaBTC", "host": "btc.viabtc.io", "port": 3333, "username": "pesbtc.001", "password": "123"}, # 14s
    {"name": "SecPool", "host": "btc.secpool.com", "port": 3333, "username": "patominer.001'", "password": ""},
    {"name": "F2Pool", "host": "btc.f2pool.com", "port": 1314, "username": "patominer.001'", "password": "21235365876986800"},
    {"name": "Luxor", "host": "btc.global.luxor.tech", "port": 700, "username": "patominer.001", "password": "123"}, # 
    {"name": "SpiderPool", "host": "btc-us.spiderpool.com", "port": 2309, "username": "patominer.001", "password": "123"}, 
]

# Storage for timestamps and intervals
pool_data = {pool["name"]: {"timestamps": [], "intervals": []} for pool in pools}


def ensure_results_dir():
    """Create results directory if it doesn't exist"""
    results_dir = 'results'
    if not os.path.exists(results_dir):
        os.makedirs(results_dir)
    return results_dir


def get_output_file_name(base_name="results", suffix="txt"):
    """
    Finds an available file name by appending an incrementing number.
    """
    results_dir = ensure_results_dir()
    index = 1
    while os.path.exists(os.path.join(results_dir, f"{base_name}_{index}.{suffix}")):
        index += 1
    return os.path.join(results_dir, f"{base_name}_{index}.{suffix}")


# Update file names
intervals_file = get_output_file_name("intervals")
timestamps_file = get_output_file_name("timestamps", "json")


def monitor_pool(pool):
    """
    Monitor the block template refresh time for a given pool.
    """
    while True:  # Add outer loop for reconnection
        try:
            print(f"[{pool['name']}] Connecting to {pool['host']}...")
            sock = socket.create_connection((pool["host"], pool["port"]))
            print(f"[{pool['name']}] Connected.")

            # Send subscription request
            subscribe_request = json.dumps({"id": 1, "method": "mining.subscribe", "params": []}) + '\n'
            sock.sendall(subscribe_request.encode())
            response = sock.recv(1024).decode()
            print(f"[{pool['name']}] Subscription response: {response.strip()}")

            # Send authorization request
            authorize_request = json.dumps({"id": 2, "method": "mining.authorize", "params": [pool["username"], pool["password"]]}) + '\n'
            sock.sendall(authorize_request.encode())
            response = sock.recv(1024).decode()
            print(f"[{pool['name']}] Authorization response: {response.strip()}")

            last_timestamp = None
            template_count = 0  # Add counter for templates

            # Listen for incoming messages
            print(f"[{pool['name']}] Listening for template updates...")
            while template_count < 50:  # Add limit to inner loop
                data = sock.recv(1024).decode()
                if not data:
                    break

                # Check for 'mining.notify' messages
                if "mining.notify" in data:
                    template_count += 1  # Increment counter
                    current_timestamp = datetime.now(timezone.utc)
                    print(f"[{pool['name']}] New template received at {current_timestamp} ({template_count}/50)")

                    pool_data[pool["name"]]["timestamps"].append(current_timestamp)

                    if last_timestamp is not None:
                        interval = (current_timestamp - last_timestamp).total_seconds()
                        pool_data[pool["name"]]["intervals"].append(interval)
                        print(f"[{pool['name']}] Template refresh interval: {interval:.2f} seconds")

                    last_timestamp = current_timestamp

            print(f"[{pool['name']}] Reached 50 templates, waiting 10 seconds before reconnecting...")
            sock.close()
            threading.Event().wait(10)  # Wait 10 seconds before reconnecting

        except Exception as e:
            print(f"[{pool['name']}] Error: {e}")
            threading.Event().wait(10)  # Also wait 10 seconds on error before retrying


if __name__ == "__main__":
    threads = []

    # Start monitoring each pool in a separate thread
    for pool in pools:
        thread = threading.Thread(target=monitor_pool, args=(pool,))
        thread.daemon = True
        thread.start()
        threads.append(thread)

    try:
        while True:
            # Print summary of intervals for each pool
            for pool in pools:
                name = pool["name"]
                intervals = pool_data[name]["intervals"]
                if intervals:
                    avg_interval = sum(intervals) / len(intervals)
                    print(f"[{name}] Average interval: {avg_interval:.2f} seconds ({len(intervals)} intervals recorded)")

            print("\n---\n")
            threading.Event().wait(10)  # Update every 10 seconds
    except KeyboardInterrupt:
        results_dir = ensure_results_dir()
        print("\nSaving results to files...")

        # Save intervals to txt file
        with open(intervals_file, "w") as f:
            for pool in pools:
                name = pool["name"]
                intervals = pool_data[name]["intervals"]
                f.write(f"{name} Intervals: {intervals}\n")

        # Save timestamps to JSON file
        timestamps_data = {
            pool["name"]: [ts.isoformat() for ts in pool_data[pool["name"]]["timestamps"]]
            for pool in pools
        }
        with open(timestamps_file, "w") as f:
            json.dump(timestamps_data, f, indent=2)

        print(f"Intervals saved to {intervals_file}")
        print(f"Timestamps saved to {timestamps_file}")
        print("Exiting...")
