import os
import threading
import queue
import matplotlib.pyplot as plt
import numpy as np
from log_processor import process_line, block_latencies, uncle_counts, difficulties, difficulty_times, difficulty_parents, block_numbers, block_mining_times, blockchain
from log_utils import tail_log_file

alpha = 0.0025
uncle_threshold = 0.5

def difficulty_algorithm_a(prev_difficulty, delta, uncle_count):
    """
    Algorithm A: current
    """
    calcDur = (14 * (len(uncle_count) + 1))
    factor = prev_difficulty // 400
    if calcDur > delta:
        return prev_difficulty + factor
    else:
        if calcDur < delta:
            return prev_difficulty - factor
        else:
            return prev_difficulty

def difficulty_algorithm_b(prev_difficulty, window):
    """
    Algorithm B: 
    - If uncle rate (sum(uncles)/len(uncles)) > threshold, increment by alpha (like Algorithm A).
    - Else, if average block_time of window > 25, increment by alpha.
    - Else, decrement by alpha.
    """
    uncles = [len(blk['uncles']) for blk in window]
    times = [blk['difficulty_time'] for blk in window]
    uncle_rate = sum(uncles) / len(uncles) if uncles else 0
    avg_block_time = sum(times) / len(times) if times else 0

    factor = prev_difficulty // 400
    if uncle_rate > uncle_threshold:
        print(f"UP:{factor} -> {prev_difficulty + factor}")
        return prev_difficulty + factor
    else:
        if avg_block_time > 25:
            print(f"UP:{factor} -> {prev_difficulty + factor}")
            return prev_difficulty + factor
        else:
            if avg_block_time < 23:
                print(f"DOWN:{factor} -> {prev_difficulty - factor}")
                return prev_difficulty - factor
            else:
                print(f"STAY:{prev_difficulty}")
                return prev_difficulty

def plot_difficulty_algorithms(window_size=30):
    # Initialize with the first difficulty in the window
    blockchain_values = list(blockchain.values())
    difficulty_A = [blockchain_values[1000]['difficulty']]
    difficulty_B = [blockchain_values[1000]['difficulty']] 
    previous_difficulty_B = blockchain_values[1000]['difficulty']

    for idx in range(1001, len(blockchain)):  # Start from block 31 up to the end of times
        t = blockchain_values[idx]['difficulty_time']
        u = blockchain_values[idx]['uncles']
        # For Algorithm A
        previous_difficulty_A = difficulty_parents[idx]
        previous_difficulty_A = difficulty_algorithm_a(previous_difficulty_A, t, u)
        previous_difficulty_A = max(previous_difficulty_A, 550000000)  # Ensure difficulty is not negative
        difficulty_A.append(previous_difficulty_A)
        if (idx % window_size == 0):
            hash = blockchain_values[idx]['hash_id']
            window = []
            for wid in range(1, window_size + 1):
                window.insert(0, blockchain[hash])
                hash = blockchain[hash]['parent_hash_id']
            previous_difficulty_B = difficulty_algorithm_b(previous_difficulty_B, window)
            previous_difficulty_B = max(previous_difficulty_B, 550000000)
        difficulty_B.append(previous_difficulty_B)


    plt.figure(figsize=(10, 5))
    plt.plot(difficulty_A, label="Algorithm A", marker='o')
    plt.plot(difficulty_B, label="Algorithm B", marker='x')
    #plt.plot(difficulties[30:], label="Actual Difficulty", marker='s')  # Start from block 31
    plt.title(f"Difficulty Adjustment Window: {window_size} Blocks")
    plt.xlabel("Block Index")
    plt.ylabel("Difficulty")
    plt.legend()
    plt.grid(True)
    plt.tight_layout()
    plt.show()

if __name__ == "__main__":
    log_file = "../logs/rsk.log"
    #log_file = "/Users/patricio/workspace/rsk/rskj/simulation/samples/rskj-2025-01-15.0.log"
    #log_file = "/Users/patricio/workspace/rsk/rskj/simulation/samples/rskj-2025-01-22.0.log"
    #log_file = "/Users/patricio/workspace/rsk/rskj/simulation/samples/rskj-2025-01-29.0.log"
    log_file_path = os.path.abspath(log_file)

    q = queue.Queue()

    log_thread = threading.Thread(target=tail_log_file, args=(log_file_path, q))
    log_thread.daemon = True
    log_thread.start()

    while True:
        while not q.empty():
            line = q.get()
            process_line(line)
        if len(difficulty_times) >= 30 and len(uncle_counts) >= 30 and len(difficulties) >= 30:
            plot_difficulty_algorithms()
            plot_difficulty_algorithms(100)
            plot_difficulty_algorithms(200)
            break

        plt.pause(0.1)