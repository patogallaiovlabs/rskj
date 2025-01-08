import re
from collections import defaultdict

def analyze_coinbases(filename):
    # Dictionary to store known miners
    known_miners = {
        "0x12d3178a62ef1f520944534ed04504609f7307a1": "F2Pool",
        "0x4e5dabc28e4a0f5e5b19fcb56b28c5a1989352c1": "AntPool",
        "0x5aee2975e2ed688f231ccb40e20ee6c10a98d507": "Sec Pool",
        "0x08f6c90cfc462db10d4dd41fb1f2162ff854a462": "ViaBTC",
        "0xcf5072f792246690c75c63638e3d98bb2554ff2c": "Luxor",
        "0x0fd9b9b567a459c6c9645ab0847785aef13dfe1b": "SpiderPool",
        # Add more known miners here
    }
    
    # Track coinbase frequencies
    coinbase_counts = defaultdict(int)
    
    # Regular expression for coinbase
    coinbase_pattern = r'coinbase:\[([a-fA-F0-9]+)\]'
    
    try:
        with open(filename, 'r') as file:
            for line in file:
                # Only process IMPORTED_BEST blocks
                if 'result IMPORTED_' not in line:
                    continue
                    
                coinbase_match = re.search(coinbase_pattern, line)
                if coinbase_match:
                    coinbase = "0x" + coinbase_match.group(1).lower()
                    coinbase_counts[coinbase] += 1
        
        # Calculate statistics
        total_blocks = sum(coinbase_counts.values())
        known_blocks = sum(count for addr, count in coinbase_counts.items() if addr in known_miners)
        unknown_blocks = total_blocks - known_blocks
        
        # Sort miners by block count
        known_miner_stats = [(addr, count, known_miners[addr]) 
                           for addr, count in coinbase_counts.items() 
                           if addr in known_miners]
        known_miner_stats.sort(key=lambda x: x[1], reverse=True)
        
        unknown_miners = [(addr, count) for addr, count in coinbase_counts.items() 
                         if addr not in known_miners]
        unknown_miners.sort(key=lambda x: x[1], reverse=True)
        
        # Print results to console
        print("\nMiner Analysis Report")
        print("=" * 50)
        print()
        
        # Overall statistics
        print("Overall Statistics:")
        print("-" * 50)
        print(f"Total Unique Miners: {len(coinbase_counts)}")
        print(f"Total Blocks: {total_blocks}")
        print(f"Known Miner Blocks: {known_blocks} ({known_blocks/total_blocks*100:.1f}%)")
        print(f"Unknown Miner Blocks: {unknown_blocks} ({unknown_blocks/total_blocks*100:.1f}%)")
        print()
        
        # Known miners statistics
        print("Known Miners Statistics:")
        print("-" * 50)
        print(f"{'Pool Name':<15} {'Address':<42} {'Blocks':>7} {'Share':>8}")
        print("-" * 75)
        
        for addr, count, name in known_miner_stats:
            percentage = count/total_blocks*100
            print(f"{name:<15} {addr:<42} {count:>7} {percentage:>7.1f}%")
        
        # Unknown miners
        print("\nTop Unknown Miners:")
        print("-" * 50)
        print(f"{'Rank':>4} {'Address':<42} {'Blocks':>7} {'Share':>8}")
        print("-" * 75)
        
        for i, (addr, count) in enumerate(unknown_miners[:20], 1):  # Show top 20
            percentage = count/total_blocks*100
            print(f"{i:>4}. {addr:<42} {count:>7} {percentage:>7.1f}%")

    except FileNotFoundError:
        print(f"Error: File '{filename}' not found.")
    except Exception as e:
        print(f"Error processing file: {e}")

if __name__ == "__main__":
    log_file = "../logs/rsk.log"  # Adjust path as needed
    analyze_coinbases(log_file) 