import os
import matplotlib.pyplot as plt
import numpy as np
from ast import literal_eval

def parse_intervals(filename):
    """
    Parse the intervals from a result file and return them grouped by pool
    """
    pool_intervals = {}
    
    try:
        with open(filename, 'r') as f:
            content = f.read()
            lines = content.strip().split('\n')
            
            for line in lines:
                if 'Intervals:' in line:
                    pool_name = line.split('Intervals:')[0].strip()
                    intervals_str = line.split('Intervals:')[1].strip()
                    try:
                        intervals = literal_eval(intervals_str)
                        if intervals:  # Only add non-empty lists
                            pool_intervals[pool_name] = intervals
                    except (SyntaxError, ValueError) as e:
                        print(f"Warning: Couldn't parse intervals for {pool_name}: {e}")
                        continue
    
    except FileNotFoundError:
        print(f"Error: File '{filename}' not found!")
        return {}
    
    if not pool_intervals:
        print(f"Warning: No intervals found in {filename}")
    
    return pool_intervals
    

def plot_histograms(pool_intervals, bin_size):
    """
    Create histograms for each pool's intervals with specified bin size
    """
    # Define the desired order of pools
    pool_order = ['Luxor', 'AntPool', 'ViaBTC', 'F2Pool', 'SpiderPool']
    
    # Filter out pools with empty data and sort according to desired order
    ordered_pools = {}
    for pool_name in pool_order:
        if pool_name in pool_intervals and pool_intervals[pool_name]:
            ordered_pools[pool_name] = pool_intervals[pool_name]
    
    num_pools = len(ordered_pools)
    if num_pools == 0:
        print("No data to plot!")
        return None
    
    # Determine the number of rows needed for 2 columns
    num_rows = (num_pools + 1) // 2
    
    fig, axes = plt.subplots(num_rows + 1, 2, figsize=(15, 5 * (num_rows + 1)))
    axes = axes.flatten()  # Flatten in case of single row
    
    all_intervals = []
    
    for ax, (pool_name, intervals) in zip(axes, ordered_pools.items()):
        # Calculate bin edges at specified intervals
        max_interval = max(intervals)
        bins = np.arange(0, max_interval + bin_size, bin_size)
        
        # Create histogram with specified bins
        ax.hist(intervals, bins=bins, edgecolor='black', alpha=0.7)
        
        # Add labels and title
        ax.set_title(pool_name, fontsize=12, pad=20)
        ax.set_xlabel('Interval (seconds)', fontsize=10)
        ax.set_ylabel('Number of Blocks', fontsize=10)
        
        # Set x-axis ticks
        ax.set_xticks(bins)
        ax.tick_params(axis='x', rotation=45)
        
        # Add grid for better readability
        ax.grid(True, alpha=0.3)
        
        # Add statistical information
        mean_interval = np.mean(intervals)
        median_interval = np.median(intervals)
        std_interval = np.std(intervals)
        
        stats_text = (f'Samples: {len(intervals)}\n'
                     f'Mean: {mean_interval:.2f}s\n'
                     f'Median: {median_interval:.2f}s\n'
                     f'Std Dev: {std_interval:.2f}s\n'
                     f'Min: {min(intervals):.2f}s\n'
                     f'Max: {max_interval:.2f}s')
        
        ax.text(0.95, 0.95, stats_text,
                transform=ax.transAxes,
                verticalalignment='top',
                horizontalalignment='right',
                bbox=dict(boxstyle='round', facecolor='white', alpha=0.9),
                fontsize=10)
        
        all_intervals.extend(intervals)
    
    # Plot the combined histogram
    if all_intervals:
        ax = axes[num_pools]
        max_interval = max(all_intervals)
        bins = np.arange(0, max_interval + bin_size, bin_size)
        
        ax.hist(all_intervals, bins=bins, edgecolor='black', alpha=0.7, color='orange')
        
        # Add labels and title
        ax.set_title('All Pools Combined', fontsize=12, pad=20)
        ax.set_xlabel('Interval (seconds)', fontsize=10)
        ax.set_ylabel('Number of Blocks', fontsize=10)
        
        # Set x-axis ticks
        ax.set_xticks(bins)
        ax.tick_params(axis='x', rotation=45)
        
        # Add grid for better readability
        ax.grid(True, alpha=0.3)
        
        # Add statistical information
        mean_interval = np.mean(all_intervals)
        median_interval = np.median(all_intervals)
        std_interval = np.std(all_intervals)
        
        stats_text = (f'Samples: {len(all_intervals)}\n'
                     f'Mean: {mean_interval:.2f}s\n'
                     f'Median: {median_interval:.2f}s\n'
                     f'Std Dev: {std_interval:.2f}s\n'
                     f'Min: {min(all_intervals):.2f}s\n'
                     f'Max: {max_interval:.2f}s')
        
        ax.text(0.95, 0.95, stats_text,
                transform=ax.transAxes,
                verticalalignment='top',
                horizontalalignment='right',
                bbox=dict(boxstyle='round', facecolor='white', alpha=0.9),
                fontsize=10)
    
    # Hide any unused subplots
    for i in range(num_pools + 1, len(axes)):
        fig.delaxes(axes[i])
    
    plt.tight_layout()
    return fig

def main():
    # Directory containing result files
    results_dir = './results'
    
    # Find all result files
    result_files = [f for f in os.listdir(results_dir) if 'intervals_' in f and f.endswith('.txt')]
    
    if not result_files:
        print("Error: No result files found in current directory!")
        return
    
    # Let user choose which file to analyze if multiple exist
    if len(result_files) > 1:
        print("Available result files:")
        for i, file in enumerate(result_files):
            print(f"{i+1}. {file}")
        while True:
            try:
                choice = int(input("Choose a file number: ")) - 1
                if 0 <= choice < len(result_files):
                    break
                print(f"Please enter a number between 1 and {len(result_files)}")
            except ValueError:
                print("Please enter a valid number")
        filename = result_files[choice]
    else:
        filename = result_files[0]
    
    print(f"\nAnalyzing file: {results_dir}/{filename}")
    
    # Parse intervals from the chosen file
    pool_intervals = parse_intervals(results_dir + '/' + filename)
    
    if not pool_intervals:
        print("Error: No data to plot!")
        return
    
    # Create and save histograms with different bin sizes
    for bin_size in [2, 5]:
        fig = plot_histograms(pool_intervals, bin_size)
        if fig is None:
            continue
            
        # Save the plot with bin size in filename
        output_filename = f"{results_dir}/histogram_{os.path.splitext(filename)[0]}_{bin_size}s_bins.png"
        fig.savefig(output_filename, dpi=300, bbox_inches='tight')
        print(f"Histogram saved as {output_filename}")
        plt.close(fig)  # Close the figure to free memory
    
    # Print statistical summary
    print("\nStatistical Summary:")
    print("-" * 50)
    for pool_name, intervals in pool_intervals.items():
        if intervals:  # Only print stats for non-empty intervals
            print(f"\n{pool_name}:")
            print(f"Number of samples: {len(intervals)}")
            print(f"Mean interval: {np.mean(intervals):.2f} seconds")
            print(f"Median interval: {np.median(intervals):.2f} seconds")
            print(f"Standard deviation: {np.std(intervals):.2f} seconds")
            print(f"Min interval: {min(intervals):.2f} seconds")
            print(f"Max interval: {max(intervals):.2f} seconds")

if __name__ == "__main__":
    main()