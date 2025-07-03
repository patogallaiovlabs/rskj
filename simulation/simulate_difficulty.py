import random
import matplotlib.pyplot as plt

# Constants
INITIAL_PARAMETER = 1 / 25  # Initial parameter for random.expovariate
INITIAL_DIFFICULTY = 25  # Starting difficulty
WINDOW_SIZE_30 = 30  # Number of blocks in each window for the first simulation
WINDOW_SIZE_100 = 100  # Number of blocks in each window for the second simulation
N_BLOCKS = 10000  # Total number of blocks to simulate
ALPHA = 0.0025  # Adjustment factor for difficulty

# Difficulty adjustment algorithms
def difficulty_algorithm_a(prev_difficulty, block_times, uncle_counts):
    """
    Algorithm A: Adjust difficulty based on average block time.
    """
    avg_block_time = sum(block_times) / len(block_times)
    if avg_block_time > 27:
        return prev_difficulty + (prev_difficulty * ALPHA)
    elif avg_block_time < 23:
        return prev_difficulty - (prev_difficulty * ALPHA)
    else:
        return prev_difficulty

def difficulty_algorithm_b(prev_difficulty, deltas, uncle_counts):
    """
    Algorithm B: Adjust difficulty based on uncle counts and block times.
    """
    calcDur = (14 * (uncle_counts[-1] + 1))
    factor = prev_difficulty * ALPHA
    if calcDur > deltas[-1]:
        return prev_difficulty + factor
    elif calcDur < deltas[-1]:
        return prev_difficulty - factor
    else:
        return prev_difficulty

# Simulation function
def simulate_blocks(window_size, update_rate, n_blocks, difficulty_algorithm):
    parameter = INITIAL_PARAMETER
    difficulty = INITIAL_DIFFICULTY

    block_times = []
    uncle_counts = []
    difficulties = [difficulty]
    avg_block_times = []
    avg_uncles = []

    for i in range(1, n_blocks + 1):
        # Generate block time and uncle count
        block_time = random.expovariate(parameter)
        uncle_count = random.choices([0, 1, 2], [0.6, 0.3, 0.1])[0]  # Randomly generate 0, 1, or 2 uncles

        block_times.append(block_time)
        uncle_counts.append(uncle_count)

        # Adjust difficulty every `update_rate` blocks
        if i % update_rate == 0:
            difficulty = difficulty_algorithm(difficulty, block_times[-window_size:], uncle_counts[-window_size:])
            parameter = 1 / difficulty  # Adjust parameter based on difficulty
            difficulties.append(difficulty)

            # Calculate and store the average block time and average uncles for the window
            avg_block_time = sum(block_times[-window_size:]) / window_size
            avg_uncles_window = sum(uncle_counts[-window_size:]) / window_size
            avg_block_times.append(avg_block_time)
            avg_uncles.append(avg_uncles_window)

    return block_times, uncle_counts, difficulties, avg_block_times, avg_uncles

# Plotting function
def plot_simulation(block_times, uncle_counts, difficulties, avg_block_times, avg_uncles, window_size, title="Difficulty Simulation"):
    plt.figure(figsize=(12, 9))

    # Plot block times
    plt.subplot(3, 1, 1)
    plt.plot(block_times, label="Block Times", color="blue", alpha=0.7)
    plt.axhline(25, color="red", linestyle="--", label="Target Block Time (25s)")
    plt.plot(range(window_size - 1, len(avg_block_times) * window_size, window_size), avg_block_times, label="Avg Block Time", color="green", marker="o")
    plt.title("Block Times")
    plt.xlabel("Block Index")
    plt.ylabel("Block Time (s)")
    plt.legend()
    plt.grid(True)

    # Plot difficulties
    plt.subplot(3, 1, 2)
    plt.plot(range(0, len(difficulties) * window_size, window_size), difficulties, label="Difficulty", marker="o")
    plt.title(title)
    plt.xlabel("Block Index")
    plt.ylabel("Difficulty")
    plt.legend()
    plt.grid(True)

    # Plot uncles
    plt.subplot(3, 1, 3)
    plt.plot(uncle_counts, label="Uncles per Block", color="purple", alpha=0.7)
    plt.plot(range(window_size - 1, len(avg_uncles) * window_size, window_size), avg_uncles, label="Avg Uncles per Window", color="orange", marker="o")
    plt.title("Uncles")
    plt.xlabel("Block Index")
    plt.ylabel("Uncles")
    plt.legend()
    plt.grid(True)

    plt.tight_layout()

# Plot comparison of difficulties
def plot_difficulty_comparison(difficulties_a_30, difficulties_a_100, difficulties_b, window_size_30, window_size_100):
    plt.figure(figsize=(10, 6))
    plt.plot(range(0, len(difficulties_a_30) * window_size_30, window_size_30), difficulties_a_30, label="Algorithm A (Window 30)", marker="o")
    plt.plot(range(0, len(difficulties_a_100) * window_size_100, window_size_100), difficulties_a_100, label="Algorithm A (Window 100)", marker="x")
    plt.plot(range(0, len(difficulties_b) * 1, 1), difficulties_b, label="Algorithm B", marker="s")
    plt.title("Difficulty Comparison")
    plt.xlabel("Block Index")
    plt.ylabel("Difficulty")
    plt.legend()
    plt.grid(True)
    plt.tight_layout()

# Main function
if __name__ == "__main__":
    # Simulate and plot for Algorithm A with WINDOW_SIZE_30
    block_times_a_30, uncle_counts_a_30, difficulties_a_30, avg_block_times_a_30, avg_uncles_a_30 = simulate_blocks(
        WINDOW_SIZE_30, WINDOW_SIZE_30, N_BLOCKS, difficulty_algorithm_a
    )
    plot_simulation(block_times_a_30, uncle_counts_a_30, difficulties_a_30, avg_block_times_a_30, avg_uncles_a_30, WINDOW_SIZE_30, title="Difficulty Simulation Algorithm A (Window 30)")

    # Simulate and plot for Algorithm A with WINDOW_SIZE_100
    block_times_a_100, uncle_counts_a_100, difficulties_a_100, avg_block_times_a_100, avg_uncles_a_100 = simulate_blocks(
        WINDOW_SIZE_100, WINDOW_SIZE_100, N_BLOCKS, difficulty_algorithm_a
    )
    plot_simulation(block_times_a_100, uncle_counts_a_100, difficulties_a_100, avg_block_times_a_100, avg_uncles_a_100, WINDOW_SIZE_100, title="Difficulty Simulation Algorithm A (Window 100)")

    # Simulate and plot for Algorithm B
    block_times_b, uncle_counts_b, difficulties_b, avg_block_times_b, avg_uncles_b = simulate_blocks(
        WINDOW_SIZE_30, 1, N_BLOCKS, difficulty_algorithm_b
    )
    plot_simulation(block_times_b, uncle_counts_b, difficulties_b, avg_block_times_b, avg_uncles_b, 1, title="Difficulty Simulation Algorithm B")

    # Plot difficulty comparison
    plot_difficulty_comparison(difficulties_a_30, difficulties_a_100, difficulties_b, WINDOW_SIZE_30, WINDOW_SIZE_100)

    # Show all plots
    plt.show()