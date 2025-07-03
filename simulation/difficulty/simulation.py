import numpy as np
import matplotlib.pyplot as plt
from collections import deque

def generate_block_data(num_blocks):
    # Generate deltas with exponential distribution (mean=28)
    deltas = np.random.exponential(28, num_blocks)
    
    # Generate uncle counts with exponential distribution (mean=0.6)
    # and round to integers
    uncle_counts = np.round(np.random.exponential(0.6, num_blocks)).astype(int)
    
    return deltas, uncle_counts

def current_approach(delta, uncle_count, duration=14):
    calc_dur = (1 + uncle_count) * duration
    if calc_dur > delta:
        return 1  # Increase difficulty
    elif calc_dur < delta:
        return -1  # Decrease difficulty
    return 0  # No change

def proposed_approach(delta, uncle_count, duration=14):
    adjusted_delta = delta / (1 + uncle_count)
    if duration > adjusted_delta:
        return 1  # Increase difficulty
    elif duration < adjusted_delta:
        return -1  # Decrease difficulty
    return 0  # No change

def run_simulation(num_blocks=10000):
    deltas, uncle_counts = generate_block_data(num_blocks)
    
    # Track difficulty changes
    current_diff_changes = []
    proposed_diff_changes = []
    
    # Track effective block times
    current_effective_times = []
    proposed_effective_times = []
    
    for i in range(num_blocks):
        delta = deltas[i]
        uncle_count = uncle_counts[i]
        
        # Calculate difficulty changes
        current_change = current_approach(delta, uncle_count)
        proposed_change = proposed_approach(delta, uncle_count)
        
        current_diff_changes.append(current_change)
        proposed_diff_changes.append(proposed_change)
        
        # Calculate effective block times (time per block including uncles)
        current_effective_times.append(delta / (1 + uncle_count))
        proposed_effective_times.append(delta / (1 + uncle_count))
    
    return {
        'deltas': deltas,
        'uncle_counts': uncle_counts,
        'current_changes': current_diff_changes,
        'proposed_changes': proposed_diff_changes,
        'current_times': current_effective_times,
        'proposed_times': proposed_effective_times
    }

def plot_results(results):
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(15, 12))
    
    # Plot delta distribution
    ax1.hist(results['deltas'], bins=50, density=True, alpha=0.7)
    ax1.set_title('Delta Distribution')
    ax1.set_xlabel('Time (seconds)')
    ax1.axvline(28, color='r', linestyle='--', label='Mean (28s)')
    ax1.legend()
    
    # Plot uncle count distribution
    ax2.hist(results['uncle_counts'], bins=range(max(results['uncle_counts'])+2), 
             density=True, alpha=0.7)
    ax2.set_title('Uncle Count Distribution')
    ax2.set_xlabel('Number of Uncles')
    ax2.axvline(0.6, color='r', linestyle='--', label='Mean (0.6)')
    ax2.legend()
    
    # Plot difficulty changes comparison
    ax3.hist([results['current_changes'], results['proposed_changes']], 
             label=['Current', 'Proposed'], bins=[-1.5, -0.5, 0.5, 1.5])
    ax3.set_title('Difficulty Changes Distribution')
    ax3.set_xlabel('Change Direction (-1, 0, 1)')
    ax3.legend()
    
    # Plot effective block times
    ax4.hist([results['current_times'], results['proposed_times']], 
             label=['Current', 'Proposed'], bins=50, alpha=0.5)
    ax4.set_title('Effective Block Times (time/total blocks)')
    ax4.set_xlabel('Time per Block (seconds)')
    ax4.axvline(14, color='r', linestyle='--', label='Target (14s)')
    ax4.legend()
    
    plt.tight_layout()
    plt.show()
    
    # Print statistics
    print("\nStatistics:")
    print(f"Average delta: {np.mean(results['deltas']):.2f}s")
    print(f"Average uncle count: {np.mean(results['uncle_counts']):.2f}")
    print(f"\nCurrent Approach:")
    print(f"Increases: {sum(x == 1 for x in results['current_changes'])} ({sum(x == 1 for x in results['current_changes'])/len(results['current_changes'])*100:.1f}%)")
    print(f"Decreases: {sum(x == -1 for x in results['current_changes'])} ({sum(x == -1 for x in results['current_changes'])/len(results['current_changes'])*100:.1f}%)")
    print(f"No change: {sum(x == 0 for x in results['current_changes'])} ({sum(x == 0 for x in results['current_changes'])/len(results['current_changes'])*100:.1f}%)")
    print(f"\nProposed Approach:")
    print(f"Increases: {sum(x == 1 for x in results['proposed_changes'])} ({sum(x == 1 for x in results['proposed_changes'])/len(results['proposed_changes'])*100:.1f}%)")
    print(f"Decreases: {sum(x == -1 for x in results['proposed_changes'])} ({sum(x == -1 for x in results['proposed_changes'])/len(results['proposed_changes'])*100:.1f}%)")
    print(f"No change: {sum(x == 0 for x in results['proposed_changes'])} ({sum(x == 0 for x in results['proposed_changes'])/len(results['proposed_changes'])*100:.1f}%)")

# Run simulation
results = run_simulation()
plot_results(results)