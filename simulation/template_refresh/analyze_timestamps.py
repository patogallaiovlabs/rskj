import json
import pandas as pd
import matplotlib.pyplot as plt
from datetime import datetime
import seaborn as sns
import glob
import os

def load_and_process_timestamps(filename, window_minutes=None):
    # Load JSON data
    with open(filename, 'r') as f:
        data = json.load(f)
    
    # Convert timestamps to datetime objects for each pool
    processed_data = {}
    for pool in data:
        timestamps = [datetime.fromisoformat(ts) for ts in data[pool]]
        if timestamps:
            # If window_minutes is specified, filter timestamps
            if window_minutes is not None:
                start_time = min(min(datetime.fromisoformat(ts) for ts in data.values()))
                end_time = start_time + pd.Timedelta(minutes=window_minutes)
                timestamps = [ts for ts in timestamps if ts <= end_time]
            processed_data[pool] = timestamps
    
    return processed_data

def create_timeline_plot(data):
    try:
        # Create figure with appropriate size
        plt.figure(figsize=(15, 8))
        
        # Find global start time for relative timing
        all_timestamps = [ts for timestamps in data.values() for ts in timestamps]
        if not all_timestamps:
            print("Error: No timestamps found for timeline plot")
            return
            
        start_time = min(all_timestamps)
        
        # Plot each pool's updates
        for i, (pool_name, timestamps) in enumerate(data.items()):
            if timestamps:  # Only plot if we have timestamps
                # Convert to relative times in minutes
                relative_times = [(ts - start_time).total_seconds() / 60 for ts in timestamps]
                
                # Plot points and add a line to make it more visible
                plt.scatter(relative_times, [i] * len(timestamps), 
                          label=pool_name, alpha=0.6, s=50)
                plt.plot(relative_times, [i] * len(timestamps), 
                        alpha=0.2, linewidth=0.5)
        
        # Customize the plot
        plt.yticks(range(len(data)), list(data.keys()))
        plt.xlabel('Time (minutes since start)')
        plt.ylabel('Mining Pools')
        plt.title('Template Update Timeline by Mining Pool')
        plt.grid(True, alpha=0.3)
        
        # Add legend with better placement
        plt.legend(bbox_to_anchor=(1.05, 1), loc='upper left')
        
        # Adjust layout to prevent label cutoff
        plt.tight_layout()
        
        # Print debug information
        print("\nTimeline Plot Debug Information:")
        print(f"Total number of pools: {len(data)}")
        print("Updates per pool:")
        for pool_name, timestamps in data.items():
            print(f"  {pool_name}: {len(timestamps)} updates")
        print(f"Time range: {min(all_timestamps)} to {max(all_timestamps)}")
        
    except Exception as e:
        print(f"Error creating timeline plot: {e}")

def analyze_correlations(data, results_dir, file_number):
    # Check if data is empty
    if not data or all(not ts for ts in data.values()):
        print("Error: No timestamp data found for correlation analysis")
        return None
    
    try:
        # Get non-empty timestamp lists
        non_empty_data = {k: v for k, v in data.items() if v}
        if not non_empty_data:
            print("Error: No timestamps found in any pool")
            return None
            
        min_time = min(min(ts) for ts in non_empty_data.values())
        max_time = max(max(ts) for ts in non_empty_data.values())
        
        # Create time bins (5-second resolution)
        window_size = '5S'
        bins = pd.date_range(start=min_time, end=max_time, freq=window_size)
        
        # Create DataFrame with binary events
        df = pd.DataFrame(index=bins)
        for pool, timestamps in data.items():
            df[pool] = 0
            for ts in timestamps:
                # Find the closest bin for each timestamp
                idx = bins.get_indexer([ts], method='nearest')[0]
                if 0 <= idx < len(bins):
                    df.iloc[idx, df.columns.get_loc(pool)] = 1
        
        # Print debug information
        print("\nDebug Information:")
        print(f"Number of time bins: {len(bins)}")
        print(f"Time window size: {window_size}")
        print("Number of events per pool:")
        for pool in df.columns:
            events = df[pool].sum()
            print(f"  {pool}: {events}")
            
        # Check if we have enough data for correlation
        if df.sum().sum() == 0:
            print("Error: No events found in any time bin")
            return None
        
        # Calculate rolling correlation with error handling
        max_shift = 2
        correlation_matrix = pd.DataFrame(0, 
                                        index=df.columns, 
                                        columns=df.columns)
        
        for pool1 in df.columns:
            for pool2 in df.columns:
                if pool1 != pool2:
                    max_corr = 0
                    valid_corr_found = False
                    
                    for shift in range(-max_shift, max_shift + 1):
                        series1 = df[pool1]
                        series2 = df[pool2].shift(shift) if shift != 0 else df[pool2]
                        
                        # Check if both series have variation
                        if series1.std() > 0 and series2.std() > 0:
                            corr = series1.corr(series2)
                            if pd.notnull(corr):
                                max_corr = max(max_corr, corr)
                                valid_corr_found = True
                    
                    correlation_matrix.loc[pool1, pool2] = max_corr if valid_corr_found else 0
                else:
                    correlation_matrix.loc[pool1, pool2] = 1.0
        
        # Save correlation heatmap in results directory
        plt.figure(figsize=(10, 8))
        sns.heatmap(correlation_matrix, annot=True, cmap='coolwarm', vmin=-1, vmax=1, center=0)
        plt.title('Correlation of Template Update Times Between Pools\n(5-second windows)')
        plt.tight_layout()
        plt.savefig(os.path.join(results_dir, f'correlation_heatmap_{file_number}.png'))
        plt.close()
        
        return correlation_matrix
        
    except ValueError as e:
        print(f"Error processing timestamps for correlation analysis: {e}")
        return None
    except Exception as e:
        print(f"Unexpected error in correlation analysis: {e}")
        return None

def select_file():
    # Get all timestamp files
    timestamp_files = glob.glob('results/timestamps_*.json')
    if not timestamp_files:
        print("Error: No timestamp files found")
        return None
        
    # Sort files by number
    timestamp_files.sort(key=lambda x: int(x.split('_')[1].split('.')[0]))
    
    # Display options
    print("\nAvailable timestamp files:")
    print("-" * 50)
    for i, file in enumerate(timestamp_files, 1):
        file_number = file.split('_')[1].split('.')[0]
        print(f"{i}. {file} (#{file_number})")
    
    # Get user choice
    while True:
        try:
            choice = input("\nSelect file number (or press Enter for latest): ").strip()
            if choice == "":
                return timestamp_files[-1]
            
            choice = int(choice)
            if 1 <= choice <= len(timestamp_files):
                return timestamp_files[choice-1]
            else:
                print(f"Please enter a number between 1 and {len(timestamp_files)}")
        except ValueError:
            print("Please enter a valid number")

def ensure_results_dir():
    """Create results directory if it doesn't exist"""
    results_dir = 'results'
    if not os.path.exists(results_dir):
        os.makedirs(results_dir)
    return results_dir

def main():
    # Create results directory
    results_dir = ensure_results_dir()
    
    # Let user select the file
    selected_file = select_file()
    if not selected_file:
        return
    
    # Get time window from user
    while True:
        window_input = input("\nEnter time window in minutes (press Enter for all data): ").strip()
        if window_input == "":
            window_minutes = None
            break
        try:
            window_minutes = float(window_input)
            if window_minutes > 0:
                break
            else:
                print("Please enter a positive number")
        except ValueError:
            print("Please enter a valid number")
    
    # Extract the number from the filename
    file_number = selected_file.split('_')[1].split('.')[0]
    
    print(f"\nAnalyzing {selected_file}" + 
          (f" (first {window_minutes} minutes)" if window_minutes else " (all data)") + 
          "...")
    
    # Load and process data with time window
    data = load_and_process_timestamps(selected_file, window_minutes)
    
    if not data:
        print("Error: No data found in timestamp file")
        return
    
    # Print raw data sample for debugging
    print("\nSample of timestamps for each pool:")
    for pool, times in data.items():
        print(f"{pool}: {times[:3] if times else 'No timestamps'}")
    
    # Create visualizations with numbered output files in results directory
    create_timeline_plot(data)
    output_suffix = f"_{window_minutes}min" if window_minutes else ""
    plt.savefig(os.path.join(results_dir, f'timeline_plot_{file_number}{output_suffix}.png'))
    plt.close()
    
    correlation = analyze_correlations(data, results_dir, file_number)
    
    if correlation is not None:
        # Save correlation plot with the same number and time window suffix
        plt.figure(figsize=(10, 8))
        sns.heatmap(correlation, annot=True, cmap='coolwarm', vmin=-1, vmax=1, center=0)
        plt.title('Correlation of Template Update Times Between Pools\n(5-second windows)')
        plt.tight_layout()
        plt.savefig(os.path.join(results_dir, f'correlation_heatmap_{file_number}{output_suffix}.png'))
        plt.close()
        
        # Save summary to a text file in results directory
        summary_file = os.path.join(results_dir, f'summary_{file_number}{output_suffix}.txt')
        with open(summary_file, 'w') as f:
            f.write(f"Analysis Summary for {os.path.basename(selected_file)}\n")
            f.write(f"Time window: {window_minutes} minutes\n" if window_minutes else "Time window: all data\n")
            f.write("=" * 50 + "\n\n")
            for pool, timestamps in data.items():
                if timestamps:
                    intervals = [(timestamps[i] - timestamps[i-1]).total_seconds() 
                               for i in range(1, len(timestamps))]
                    avg_interval = sum(intervals) / len(intervals) if intervals else 0
                    f.write(f"{pool}:\n")
                    f.write(f"  Number of updates: {len(timestamps)}\n")
                    f.write(f"  Average interval: {avg_interval:.2f} seconds\n")
                    f.write(f"  First update: {timestamps[0]}\n")
                    f.write(f"  Last update: {timestamps[-1]}\n\n")
        
        print("\nFiles created in 'results' directory:")
        print(f"- timeline_plot_{file_number}{output_suffix}.png")
        print(f"- correlation_heatmap_{file_number}{output_suffix}.png")
        print(f"- summary_{file_number}{output_suffix}.txt")
    else:
        print("Warning: Correlation analysis could not be completed")

if __name__ == "__main__":
    main() 