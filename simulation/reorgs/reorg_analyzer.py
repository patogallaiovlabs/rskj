import re
import os
import json
import threading
import time
import matplotlib.pyplot as plt
from datetime import datetime
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Optional

@dataclass
class BlockInfo:
    number: int
    difficulty: int
    fees: int
    hash: str

@dataclass
class ReorgEvent:
    timestamp: datetime
    last_block: BlockInfo
    new_block: BlockInfo
    cause: str  # 'difficulty', 'fees', or 'hash'
    height: int

@dataclass
class SiblingBlock:
    block_number: int
    hash: str
    coinbase: str
    timestamp: datetime
    is_first_block: bool
    time_diff_from_first: float

class ReorgAnalyzer:
    def __init__(self):
        self.reorgs = []
        self.reorg_stats = {
            'total_reorgs': 0,
            'by_cause': defaultdict(int),
            'by_height': defaultdict(int),
            'time_intervals': deque(maxlen=1000),  # Track time between reorgs
            'last_reorg_time': None,
            'first_block': None,  # Track first block number from reorgs
            'highest_block': None,  # Track highest block number from reorgs
            'overall_first_block': None,  # Track first block number from entire log
            'overall_highest_block': None,  # Track highest block number from entire log
            'reorg_lengths': deque(maxlen=1000), # Track lengths of recent reorgs
            'pending_reorgs': {},  # Track pending reorgs by blockHeight and blockHash
            'length_by_cause': defaultdict(lambda: defaultdict(int)),  # Track reorg lengths by cause
            'blocks_by_reorg_types': defaultdict(set),  # Track which reorg types affected each block
            'reorg_events': [],  # Track all reorg events with their affected blocks
            'sibling_blocks': defaultdict(list),  # Track sibling blocks by block number
            'first_block_arrival_times': {},  # Track first block arrival time for each height
            'hash_reorg_stats': {
                'total_siblings': 0,
                'hash_reorgs': 0,
                'actual_hash_reorgs': 0
            }
        }
        
    def track_block_number(self, block_number: int):
        """Track block numbers for overall range calculation"""
        if self.reorg_stats['overall_first_block'] is None:
            self.reorg_stats['overall_first_block'] = block_number
        else:
            self.reorg_stats['overall_first_block'] = min(self.reorg_stats['overall_first_block'], block_number)
        
        if self.reorg_stats['overall_highest_block'] is None:
            self.reorg_stats['overall_highest_block'] = block_number
        else:
            self.reorg_stats['overall_highest_block'] = max(self.reorg_stats['overall_highest_block'], block_number)
    
    def parse_block_info(self, block_str: str) -> BlockInfo:
        """Parse block information from the log string"""
        # Extract number, difficulty, fees, and hash
        number_match = re.search(r'number:(\d+)', block_str)
        difficulty_match = re.search(r'difficulty:(\d+)', block_str)
        fees_match = re.search(r'fees:(\d+)', block_str)
        hash_match = re.search(r'hash:([a-fA-F0-9]+)', block_str)
        
        if not all([number_match, difficulty_match, fees_match, hash_match]):
            raise ValueError(f"Could not parse block info: {block_str}")
        
        return BlockInfo(
            number=int(number_match.group(1)),
            difficulty=int(difficulty_match.group(1)),
            fees=int(fees_match.group(1)),
            hash=hash_match.group(1)
        )
    
    def determine_reorg_cause(self, last_block: BlockInfo, new_block: BlockInfo) -> str:
        """Determine the cause of the reorg based on difficulty, fees, and hash"""
        print(f"🔍 DEBUG: Comparing blocks {last_block.number} vs {new_block.number}")
        print(f"   Last Block:  D={last_block.difficulty}, F={last_block.fees}, H={last_block.hash[:16]}...")
        print(f"   New Block:   D={new_block.difficulty}, F={new_block.fees}, H={new_block.hash[:16]}...")
        
        # First compare difficulty (higher difficulty wins)
        if new_block.difficulty > last_block.difficulty:
            print(f"   → Difficulty-based reorg (new block has higher difficulty)")
            return 'difficulty'
        elif last_block.difficulty > new_block.difficulty:
            print(f"   → Difficulty-based reorg (last block had higher difficulty)")
            return 'difficulty'
        
        print(f"   → Difficulties are equal, comparing fees...")
        
        # If difficulties are equal, compare fees (new block needs DOUBLE the fees to win)
        if new_block.fees >= (last_block.fees * 2):
            print(f"   → Fee-based reorg (new block has double or more fees: {new_block.fees} >= {last_block.fees * 2})")
            return 'fees'
        elif last_block.fees >= (new_block.fees * 2):
            print(f"   → Fee-based reorg (last block had double or more fees: {last_block.fees} >= {new_block.fees * 2})")
            return 'fees'
        
        print(f"   → Fees are not double, comparing hashes...")
        
        # If fees are equal, compare hash (lower hash wins)
        if new_block.hash < last_block.hash:
            print(f"   → Hash-based reorg (new block has lower hash)")
            return 'hash'
        else:
            print(f"   → Hash-based reorg (last block had lower hash)")
            return 'hash'
    
    def process_reorg_line(self, line: str) -> Optional[ReorgEvent]:
        """Process a log line to extract reorg information"""
        # Look for rebranching pattern (start of reorg)
        rebranch_match = re.search(r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*Rebranching lastBlock:\[([^\]]+)\] -> newBlock:\[([^\]]+)\]', line)
        
        if rebranch_match:
            return self.process_rebranch_start(line, rebranch_match)
        
        # Look for rebranching done pattern (end of reorg with length info)
        rebranch_done_match = re.search(r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*Rebranching done, from: (\d+) to: (\d+)', line)
        
        if rebranch_done_match:
            return self.process_rebranch_done(line, rebranch_done_match)
        
        return None
    
    def process_sibling_block(self, line: str) -> Optional[SiblingBlock]:
        """Process a log line to extract sibling block information"""
        # Look for IMPORTED pattern (sibling block)
        import_match = re.search(r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*block: num: \[(\d+)\].*hash:\s*\[([0-9a-fA-F]+)\],.*coinbase:\[(\w+)\],.*timestamp:(\d+)', line)
        
        if import_match:
            timestamp_str = import_match.group(1)
            block_number = int(import_match.group(2))
            hash_id = import_match.group(3)
            coinbase = import_match.group(4)
            timestamp = int(import_match.group(5))
            
            # Parse timestamp
            log_time = datetime.strptime(timestamp_str, "%Y-%m-%d-%H:%M:%S.%f")
            
            # Track first block arrival time for this height
            is_first_block_for_height = False
            if block_number not in self.reorg_stats['first_block_arrival_times']:
                self.reorg_stats['first_block_arrival_times'][block_number] = log_time
                is_first_block_for_height = True
            
            # Calculate time difference from first block for this height
            time_diff_from_first = 0
            if not is_first_block_for_height:
                first_arrival_time = self.reorg_stats['first_block_arrival_times'][block_number]
                time_diff_from_first = (log_time - first_arrival_time).total_seconds()
            
            # Create sibling block
            sibling_block = SiblingBlock(
                block_number=block_number,
                hash=hash_id,
                coinbase=coinbase,
                timestamp=log_time,
                is_first_block=is_first_block_for_height,
                time_diff_from_first=time_diff_from_first
            )
            
            # Track sibling block
            self.reorg_stats['sibling_blocks'][block_number].append(sibling_block)
            
            # Update hash reorg statistics
            if not is_first_block_for_height:
                self.reorg_stats['hash_reorg_stats']['total_siblings'] += 1
                
                # Get the current chain head (what this sibling would be reorging)
                current_head = self._get_current_chain_head(sibling_block.block_number)
                
                if current_head and sibling_block.hash < current_head.hash:
                    self.reorg_stats['hash_reorg_stats']['hash_reorgs'] += 1
            
            return sibling_block
        
        return None
    
    def _could_cause_hash_reorg(self, sibling_block: SiblingBlock) -> bool:
        """Determine if a sibling block could potentially cause a hash-based reorg"""
        if sibling_block.is_first_block:
            return False
        
        # Get all siblings for this block number
        siblings = self.reorg_stats['sibling_blocks'][sibling_block.block_number]
        
        # Find the first block (the one that was initially accepted)
        first_block = None
        for sibling in siblings:
            if sibling.is_first_block:
                first_block = sibling
                break
        
        if not first_block:
            return False
        
        # Compare hashes - lower hash wins in hash-based reorgs
        return sibling_block.hash < first_block.hash
    
    def _get_current_chain_head(self, block_number: int) -> Optional[SiblingBlock]:
        """Get the current chain head for a block number (the block that would be reorged)"""
        siblings = self.reorg_stats['sibling_blocks'].get(block_number, [])
        
        # Find the first block initially
        first_block = None
        for sibling in siblings:
            if sibling.is_first_block:
                first_block = sibling
                break
        
        if not first_block:
            return None
        
        # Check if there were any hash reorgs for this block
        if block_number in self.reorg_stats['blocks_by_reorg_types']:
            reorg_types = self.reorg_stats['blocks_by_reorg_types'][block_number]
            if 'hash' in reorg_types:
                # Find the sibling with the lowest hash (the one that won the hash reorg)
                lowest_hash_sibling = None
                lowest_hash = None
                for sibling in siblings:
                    if not sibling.is_first_block and sibling.hash < first_block.hash:
                        if lowest_hash is None or sibling.hash < lowest_hash:
                            lowest_hash = sibling.hash
                            lowest_hash_sibling = sibling
                
                return lowest_hash_sibling if lowest_hash_sibling else first_block
        
        return first_block
    
    def _did_cause_hash_reorg(self, sibling_block: SiblingBlock) -> bool:
        """Check if a sibling block actually caused a hash-based reorg"""
        if sibling_block.is_first_block:
            return False
        
        # Check if this block was actually reorged by hash
        block_number = sibling_block.block_number
        if block_number in self.reorg_stats['blocks_by_reorg_types']:
            reorg_types = self.reorg_stats['blocks_by_reorg_types'][block_number]
            if 'hash' in reorg_types:
                # If this block was reorged by hash, and this sibling has a lower hash
                # than the first block, it's likely this sibling caused the reorg
                siblings = self.reorg_stats['sibling_blocks'].get(block_number, [])
                first_block = None
                for sibling in siblings:
                    if sibling.is_first_block:
                        first_block = sibling
                        break
                
                if first_block and sibling_block.hash < first_block.hash:
                    return True
        
        return False
    
    def process_rebranch_start(self, line: str, match) -> Optional[ReorgEvent]:
        """Process the start of a rebranching event"""
        timestamp_str = match.group(1)
        last_block_str = match.group(2)
        new_block_str = match.group(3)
        
        # Extract blockHeight and blockHash for tracking
        height_match = re.search(r'blockHeight=(\d+)', line)
        hash_match = re.search(r'blockHash=([a-fA-F0-9]+)', line)
        
        if not height_match or not hash_match:
            return None
        
        block_height = int(height_match.group(1))
        block_hash = hash_match.group(1)
        
        try:
            # Parse timestamp
            timestamp = datetime.strptime(timestamp_str, "%Y-%m-%d-%H:%M:%S.%f")
            
            # Parse block information
            last_block = self.parse_block_info(last_block_str)
            new_block = self.parse_block_info(new_block_str)
            
            # Track block numbers for overall range
            self.track_block_number(last_block.number)
            self.track_block_number(new_block.number)
            
            print(f"\n🎯 REORG START DETECTED at {timestamp}")
            print(f"   Block {last_block.number} → Block {new_block.number}")
            print(f"   BlockHeight: {block_height}, BlockHash: {block_hash[:16]}...")
            
            # Determine cause
            cause = self.determine_reorg_cause(last_block, new_block)
            
            # Create reorg event (without length yet)
            reorg_event = ReorgEvent(
                timestamp=timestamp,
                last_block=last_block,
                new_block=new_block,
                cause=cause,
                height=new_block.number
            )
            
            # Store pending reorg for later completion
            key = f"{block_height}_{block_hash}"
            self.reorg_stats['pending_reorgs'][key] = reorg_event
            
            return None  # Don't add to reorgs list yet
            
        except Exception as e:
            print(f"❌ Error parsing rebranch start: {e}")
            print(f"   Line: {line}")
            return None
    
    def process_rebranch_done(self, line: str, match) -> Optional[ReorgEvent]:
        """Process the completion of a rebranching event"""
        timestamp_str = match.group(1)
        from_block = int(match.group(2))
        to_block = int(match.group(3))
        
        # Calculate reorg length
        reorg_length = abs(from_block - to_block)
        
        # Extract blockHeight and blockHash to find the pending reorg
        height_match = re.search(r'blockHeight=(\d+)', line)
        hash_match = re.search(r'blockHash=([a-fA-F0-9]+)', line)
        
        if not height_match or not hash_match:
            return None
        
        block_height = int(height_match.group(1))
        block_hash = hash_match.group(1)
        key = f"{block_height}_{block_hash}"
        
        # Find the corresponding pending reorg
        if key in self.reorg_stats['pending_reorgs']:
            reorg_event = self.reorg_stats['pending_reorgs'][key]
            del self.reorg_stats['pending_reorgs'][key]  # Remove from pending
            
            print(f"\n✅ REORG COMPLETED")
            print(f"   Length: {reorg_length} blocks (from {from_block} to {to_block})")
            print(f"   Cause: {reorg_event.cause}")
            
            # Update statistics
            self.reorg_stats['total_reorgs'] += 1
            self.reorg_stats['by_cause'][reorg_event.cause] += 1
            self.reorg_stats['by_height'][reorg_event.height] += 1
            self.reorg_stats['reorg_lengths'].append(reorg_length)
            self.reorg_stats['length_by_cause'][reorg_event.cause][reorg_length] += 1
            
            # Track which reorg types affected this block
            self.reorg_stats['blocks_by_reorg_types'][reorg_event.height].add(reorg_event.cause)
            
            # Track the reorg event with affected blocks
            affected_blocks = list(range(from_block, to_block + 1)) if from_block <= to_block else list(range(to_block, from_block + 1))
            reorg_event_data = {
                'timestamp': reorg_event.timestamp,
                'cause': reorg_event.cause,
                'height': reorg_event.height,
                'length': reorg_length,
                'affected_blocks': affected_blocks
            }
            self.reorg_stats['reorg_events'].append(reorg_event_data)
            
            # Update blocks_by_reorg_types for all affected blocks
            for block_num in affected_blocks:
                self.reorg_stats['blocks_by_reorg_types'][block_num].add(reorg_event.cause)
            
            # Track block range for percentage calculation
            if self.reorg_stats['first_block'] is None:
                self.reorg_stats['first_block'] = reorg_event.height
            else:
                self.reorg_stats['first_block'] = min(self.reorg_stats['first_block'], reorg_event.height)
            
            if self.reorg_stats['highest_block'] is None:
                self.reorg_stats['highest_block'] = reorg_event.height
            else:
                self.reorg_stats['highest_block'] = max(self.reorg_stats['highest_block'], reorg_event.height)
            
            # Track time intervals between reorgs
            if self.reorg_stats['last_reorg_time']:
                time_diff = (reorg_event.timestamp - self.reorg_stats['last_reorg_time']).total_seconds()
                self.reorg_stats['time_intervals'].append(time_diff)
                print(f"   ⏱️  Time since last reorg: {time_diff:.2f} seconds")
            
            self.reorg_stats['last_reorg_time'] = reorg_event.timestamp
            
            return reorg_event
        else:
            print(f"⚠️  Warning: No pending reorg found for key {key}")
            return None
    
    def process_log_history(self, log_file_path: str):
        """Process the entire log file history first"""
        print(f"📖 Processing log file history: {log_file_path}")
        
        if not os.path.exists(log_file_path):
            print(f"❌ Log file {log_file_path} not found!")
            return
        
        with open(log_file_path, 'r') as f:
            for line_num, line in enumerate(f, 1):
                reorg_event = self.process_reorg_line(line)
                if reorg_event:
                    self.reorgs.append(reorg_event)
                
                # Also process sibling blocks
                sibling_block = self.process_sibling_block(line)
                
                # Print progress every 1000 lines
                if line_num % 1000 == 0:
                    print(f"   Processed {line_num} lines...")
        
        print(f"✅ History processing complete! Found {len(self.reorgs)} reorgs in history.")
        if self.reorgs:
            self.print_real_time_statistics()
    
    def print_real_time_statistics(self):
        """Print real-time statistics after each reorg"""
        print(f"\n📊 REAL-TIME STATISTICS")
        print("="*60)
        
        total_reorgs = self.reorg_stats['total_reorgs']
        print(f"Total Reorgs: {total_reorgs}")
        
        if total_reorgs == 0:
            return
        
        # Cause breakdown
        print("\nCauses:")
        for cause, count in self.reorg_stats['by_cause'].items():
            percentage = (count / total_reorgs) * 100
            print(f"  {cause.capitalize():12}: {count:3} ({percentage:5.1f}%)")
        
        # Recent reorgs (last 3)
        print(f"\nRecent Reorgs:")
        for i, reorg in enumerate(self.reorgs[-3:], 1):
            print(f"  {i}. Block {reorg.height} - {reorg.cause.capitalize()} - {reorg.timestamp.strftime('%H:%M:%S')}")
        
        # Time intervals
        if self.reorg_stats['time_intervals']:
            intervals = list(self.reorg_stats['time_intervals'])
            avg_interval = sum(intervals) / len(intervals)
            print(f"\nAvg time between reorgs: {avg_interval:.2f} seconds")
        
        # Reorg length statistics
        if self.reorg_stats['reorg_lengths']:
            lengths = list(self.reorg_stats['reorg_lengths'])
            avg_length = sum(lengths) / len(lengths)
            min_length = min(lengths)
            max_length = max(lengths)
            print(f"\nReorg Length Stats:")
            print(f"  Average: {avg_length:.2f} blocks")
            print(f"  Min: {min_length} blocks")
            print(f"  Max: {max_length} blocks")
            
            # Length by cause breakdown
            print(f"\nReorg Lengths by Cause:")
            for cause in ['difficulty', 'fees', 'hash']:
                if cause in self.reorg_stats['length_by_cause']:
                    cause_lengths = self.reorg_stats['length_by_cause'][cause]
                    if cause_lengths:
                        print(f"  {cause.capitalize()}:")
                        for length in sorted(cause_lengths.keys()):
                            count = cause_lengths[length]
                            # Calculate percentage of total reorgs for this length
                            total_for_length = sum(self.reorg_stats['length_by_cause'][c].get(length, 0) for c in ['difficulty', 'fees', 'hash'])
                            percentage = (count / total_for_length * 100) if total_for_length > 0 else 0
                            print(f"    {length} blocks: {count} reorgs ({percentage:.1f}%)")
        
        # Block range and reorg percentage
        if self.reorg_stats['overall_first_block'] is not None and self.reorg_stats['overall_highest_block'] is not None:
            total_blocks = self.reorg_stats['overall_highest_block'] - self.reorg_stats['overall_first_block'] + 1
            reorg_percentage = (total_reorgs / total_blocks) * 100 if total_blocks > 0 else 0
            print(f"\nBlock Range: {self.reorg_stats['overall_first_block']} - {self.reorg_stats['overall_highest_block']} ({total_blocks} blocks)")
            print(f"Reorg Percentage: {reorg_percentage:.2f}% ({total_reorgs} reorgs / {total_blocks} blocks)")
        
        print("="*60)
        
        # Display block reorg type combinations
        self._print_block_reorg_combinations()
        
        # Display hash reorg probability analysis
        self._print_hash_reorg_probability()
    
    def _print_block_reorg_combinations(self):
        """Print analysis of which reorg types affected each block"""
        if not self.reorg_stats['blocks_by_reorg_types']:
            return
        
        print(f"\n🔍 BLOCK REORG TYPE ANALYSIS")
        print("="*60)
        
        # Count combinations with frequency
        combination_counts = defaultdict(int)
        total_affected_blocks = len(self.reorg_stats['blocks_by_reorg_types'])
        
        for block_height, reorg_types in self.reorg_stats['blocks_by_reorg_types'].items():
            # Count frequency of each reorg type
            type_counts = defaultdict(int)
            for reorg_type in reorg_types:
                type_counts[reorg_type] += 1
            
            # Create combination string with counts
            combination_parts = []
            for reorg_type in sorted(type_counts.keys()):
                count = type_counts[reorg_type]
                if count == 1:
                    combination_parts.append(reorg_type)
                else:
                    combination_parts.append(f"{reorg_type}({count}x)")
            
            combination_str = " + ".join(combination_parts)
            combination_counts[combination_str] += 1
        
        print(f"Total blocks affected by reorgs: {total_affected_blocks}")
        print(f"\nReorg Type Combinations (with frequency):")
        
        for combination, count in sorted(combination_counts.items(), key=lambda x: x[1], reverse=True):
            percentage = (count / total_affected_blocks) * 100
            print(f"  {combination:25}: {count:3} blocks ({percentage:5.1f}%)")
        
        # Show some example blocks for each combination
        print(f"\nExample blocks for each combination:")
        for combination in sorted(combination_counts.keys(), key=lambda x: combination_counts[x], reverse=True):
            example_blocks = []
            for block, types in self.reorg_stats['blocks_by_reorg_types'].items():
                # Recreate the combination string for this block
                type_counts = defaultdict(int)
                for reorg_type in types:
                    type_counts[reorg_type] += 1
                
                combination_parts = []
                for reorg_type in sorted(type_counts.keys()):
                    count = type_counts[reorg_type]
                    if count == 1:
                        combination_parts.append(reorg_type)
                    else:
                        combination_parts.append(f"{reorg_type}({count}x)")
                
                block_combination = " + ".join(combination_parts)
                if block_combination == combination:
                    example_blocks.append(block)
                    if len(example_blocks) >= 5:  # Show first 5 examples
                        break
            
            print(f"  {combination}: {example_blocks}")
        
        # Show detailed reorg events for blocks with multiple reorg types
        print(f"\nDetailed reorg events for blocks with multiple reorg types:")
        multi_reorg_blocks = {block: types for block, types in self.reorg_stats['blocks_by_reorg_types'].items() 
                             if len(types) > 1}
        
        for block_num in sorted(multi_reorg_blocks.keys())[:10]:  # Show first 10 examples
            reorg_types = multi_reorg_blocks[block_num]
            # Count frequency for display
            type_counts = defaultdict(int)
            for reorg_type in reorg_types:
                type_counts[reorg_type] += 1
            
            combination_parts = []
            for reorg_type in sorted(type_counts.keys()):
                count = type_counts[reorg_type]
                if count == 1:
                    combination_parts.append(reorg_type)
                else:
                    combination_parts.append(f"{reorg_type}({count}x)")
            
            combination_str = " + ".join(combination_parts)
            print(f"  Block {block_num} ({combination_str}):")
            
            # Find reorg events that affected this block
            for event in self.reorg_stats['reorg_events']:
                if block_num in event['affected_blocks']:
                    print(f"    {event['timestamp'].strftime('%H:%M:%S')} - {event['cause']} reorg (length: {event['length']})")
    
    def _print_hash_reorg_probability(self):
        """Print analysis of hash reorg probability for sibling blocks"""
        hash_stats = self.reorg_stats['hash_reorg_stats']
        
        if hash_stats['total_siblings'] == 0:
            return
        
        print(f"\n🔍 HASH REORG PROBABILITY ANALYSIS")
        print("="*60)
        
        # Calculate overall probability
        potential_hash_reorgs = hash_stats['hash_reorgs']
        # Use the actual hash reorg count from the reorg statistics
        actual_hash_reorgs = self.reorg_stats['by_cause'].get('hash', 0)
        potential_probability = (potential_hash_reorgs / hash_stats['total_siblings'] * 100) if hash_stats['total_siblings'] > 0 else 0
        actual_probability = (actual_hash_reorgs / hash_stats['total_siblings'] * 100) if hash_stats['total_siblings'] > 0 else 0
        
        print(f"Total sibling blocks: {hash_stats['total_siblings']}")
        print(f"Potential hash reorgs (lower hash): {potential_hash_reorgs}")
        print(f"Actual hash reorgs: {actual_hash_reorgs}")
        print(f"Potential hash reorg probability: {potential_probability:.2f}%")
        print(f"Actual hash reorg probability: {actual_probability:.2f}%")
        print(f"Success rate (actual/potential): {(actual_hash_reorgs / potential_hash_reorgs * 100) if potential_hash_reorgs > 0 else 0:.2f}%")
        
        # Show some example sibling blocks that could cause hash reorgs
        print(f"\nExample sibling blocks with potential hash reorgs:")
        example_count = 0
        for block_number, siblings in self.reorg_stats['sibling_blocks'].items():
            if example_count >= 5:  # Show only first 5 examples
                break
            
            # Sort siblings by timestamp to show sequence
            sorted_siblings = sorted(siblings, key=lambda s: s.timestamp)
            
            for sibling in sorted_siblings:
                if not sibling.is_first_block:
                    current_head = self._get_current_chain_head(block_number)
                    if current_head and sibling.hash < current_head.hash:
                        print(f"  Block {block_number}: {sibling.hash[:16]}... (sibling) vs {current_head.hash[:16]}... (current head)")
                        example_count += 1
                        break
    
    def monitor_log_file(self, log_file_path: str):
        """Monitor a log file in real-time for reorg events"""
        print(f"📋 Monitoring reorgs in log file: {log_file_path}")
        
        if not os.path.exists(log_file_path):
            print(f"❌ Log file {log_file_path} not found!")
            return
        
        with open(log_file_path, 'r') as f:
            # Go to end of file
            f.seek(0, 2)
            
            while True:
                line = f.readline()
                if line:
                    reorg_event = self.process_reorg_line(line)
                    if reorg_event:
                        self.reorgs.append(reorg_event)
                        self.print_real_time_statistics()
                    
                    # Also process sibling blocks
                    sibling_block = self.process_sibling_block(line)
                else:
                    time.sleep(0.1)  # Small delay when no new lines
    
    def save_results(self, output_dir: str = "results"):
        """Save analysis results to files"""
        if not os.path.exists(output_dir):
            os.makedirs(output_dir)
        
        # Save detailed reorg data
        reorgs_data = []
        for reorg in self.reorgs:
            reorgs_data.append({
                'timestamp': reorg.timestamp.isoformat(),
                'height': reorg.height,
                'cause': reorg.cause,
                'last_block': {
                    'number': reorg.last_block.number,
                    'difficulty': reorg.last_block.difficulty,
                    'fees': reorg.last_block.fees,
                    'hash': reorg.last_block.hash
                },
                'new_block': {
                    'number': reorg.new_block.number,
                    'difficulty': reorg.new_block.difficulty,
                    'fees': reorg.new_block.fees,
                    'hash': reorg.new_block.hash
                }
            })
        
        # Save statistics
        stats_data = {
            'total_reorgs': self.reorg_stats['total_reorgs'],
            'by_cause': dict(self.reorg_stats['by_cause']),
            'by_height': dict(self.reorg_stats['by_height']),
            'time_intervals': list(self.reorg_stats['time_intervals']),
            'reorg_lengths': list(self.reorg_stats['reorg_lengths']),
            'length_by_cause': {cause: dict(lengths) for cause, lengths in self.reorg_stats['length_by_cause'].items()},
            'length_by_cause_percentages': self._calculate_length_by_cause_percentages(),
            'blocks_by_reorg_types': {str(block): list(types) for block, types in self.reorg_stats['blocks_by_reorg_types'].items()},
            'reorg_type_combinations': self._calculate_reorg_type_combinations(),
            'reorg_events': [{'timestamp': event['timestamp'].isoformat(), 'cause': event['cause'], 'height': event['height'], 'length': event['length'], 'affected_blocks': event['affected_blocks']} for event in self.reorg_stats['reorg_events']],
            'hash_reorg_stats': self.reorg_stats['hash_reorg_stats'],
            'sibling_blocks': {str(block): [{'hash': s.hash, 'coinbase': s.coinbase, 'timestamp': s.timestamp.isoformat(), 'is_first_block': s.is_first_block, 'time_diff_from_first': s.time_diff_from_first} for s in siblings] for block, siblings in self.reorg_stats['sibling_blocks'].items()},
            'reorg_length_stats': {
                'average': sum(self.reorg_stats['reorg_lengths']) / len(self.reorg_stats['reorg_lengths']) if self.reorg_stats['reorg_lengths'] else 0,
                'min': min(self.reorg_stats['reorg_lengths']) if self.reorg_stats['reorg_lengths'] else 0,
                'max': max(self.reorg_stats['reorg_lengths']) if self.reorg_stats['reorg_lengths'] else 0,
                'total_reorgs': len(self.reorg_stats['reorg_lengths'])
            },
            'block_range': {
                'first_block': self.reorg_stats['first_block'],
                'highest_block': self.reorg_stats['highest_block'],
                'overall_first_block': self.reorg_stats['overall_first_block'],
                'overall_highest_block': self.reorg_stats['overall_highest_block'],
                'total_blocks': (self.reorg_stats['overall_highest_block'] - self.reorg_stats['overall_first_block'] + 1) if self.reorg_stats['overall_first_block'] is not None and self.reorg_stats['overall_highest_block'] is not None else 0,
                'reorg_percentage': (self.reorg_stats['total_reorgs'] / (self.reorg_stats['overall_highest_block'] - self.reorg_stats['overall_first_block'] + 1) * 100) if self.reorg_stats['overall_first_block'] is not None and self.reorg_stats['overall_highest_block'] is not None and (self.reorg_stats['overall_highest_block'] - self.reorg_stats['overall_first_block'] + 1) > 0 else 0
            },
            'reorgs': reorgs_data
        }
        
        # Save to JSON file
        json_file = os.path.join(output_dir, "reorg_analysis.json")
        with open(json_file, 'w') as f:
            json.dump(stats_data, f, indent=2)
        
        print(f"✅ Results saved to {json_file}")
    
    def plot_reorg_length_histogram(self, output_dir: str = "results"):
        """Plot histogram of reorg lengths and save to file"""
        if not self.reorg_stats['reorg_lengths']:
            print("No reorg data available for histogram")
            return
        
        if not os.path.exists(output_dir):
            os.makedirs(output_dir)
        
        lengths = list(self.reorg_stats['reorg_lengths'])
        
        # Create histogram
        plt.figure(figsize=(12, 8))
        n, bins, patches = plt.hist(lengths, bins=min(20, len(set(lengths))), alpha=0.7, color='skyblue', edgecolor='black')
        plt.xlabel('Reorg Length (blocks)')
        plt.ylabel('Frequency')
        plt.title('Distribution of Reorg Lengths')
        plt.grid(True, alpha=0.3)
        
        # Add quantity and percentage labels to each bar
        total_reorgs = len(lengths)
        for i, (count, patch) in enumerate(zip(n, patches)):
            if count > 0:  # Only add labels for bars with data
                percentage = (count / total_reorgs) * 100
                # Calculate the center of the bar
                bar_center = (bins[i] + bins[i + 1]) / 2
                # Add text above the bar
                plt.text(bar_center, count + 0.1, f'{int(count)}\n({percentage:.1f}%)', 
                        ha='center', va='bottom', fontsize=9, fontweight='bold')
        
        # Add statistics text
        avg_length = sum(lengths) / len(lengths)
        min_length = min(lengths)
        max_length = max(lengths)
        stats_text = f'Average: {avg_length:.2f}\nMin: {min_length}\nMax: {max_length}\nTotal: {len(lengths)}'
        plt.text(0.95, 0.95, stats_text, transform=plt.gca().transAxes, 
                verticalalignment='top', horizontalalignment='right',
                bbox=dict(boxstyle='round', facecolor='wheat', alpha=0.8))
        
        # Save plot
        plot_file = os.path.join(output_dir, "reorg_length_histogram.png")
        plt.savefig(plot_file, dpi=300, bbox_inches='tight')
        plt.close()
        
        print(f"📊 Reorg length histogram saved to {plot_file}")
    
    def _calculate_length_by_cause_percentages(self):
        """Calculate percentages for length by cause statistics"""
        percentages = {}
        
        # Get all unique lengths across all causes
        all_lengths = set()
        for cause_lengths in self.reorg_stats['length_by_cause'].values():
            all_lengths.update(cause_lengths.keys())
        
        for length in sorted(all_lengths):
            total_for_length = sum(self.reorg_stats['length_by_cause'][c].get(length, 0) for c in ['difficulty', 'fees', 'hash'])
            if total_for_length > 0:
                percentages[length] = {}
                for cause in ['difficulty', 'fees', 'hash']:
                    count = self.reorg_stats['length_by_cause'][cause].get(length, 0)
                    percentage = (count / total_for_length * 100) if total_for_length > 0 else 0
                    percentages[length][cause] = {
                        'count': count,
                        'percentage': round(percentage, 1)
                    }
        
        return percentages
    
    def _calculate_reorg_type_combinations(self):
        """Calculate reorg type combination statistics"""
        combination_counts = defaultdict(int)
        total_affected_blocks = len(self.reorg_stats['blocks_by_reorg_types'])
        
        for block_height, reorg_types in self.reorg_stats['blocks_by_reorg_types'].items():
            # Count frequency of each reorg type
            type_counts = defaultdict(int)
            for reorg_type in reorg_types:
                type_counts[reorg_type] += 1
            
            # Create combination string with counts
            combination_parts = []
            for reorg_type in sorted(type_counts.keys()):
                count = type_counts[reorg_type]
                if count == 1:
                    combination_parts.append(reorg_type)
                else:
                    combination_parts.append(f"{reorg_type}({count}x)")
            
            combination_str = " + ".join(combination_parts)
            combination_counts[combination_str] += 1
        
        combinations_data = {}
        for combination, count in sorted(combination_counts.items(), key=lambda x: x[1], reverse=True):
            percentage = (count / total_affected_blocks) * 100 if total_affected_blocks > 0 else 0
            combinations_data[combination] = {
                'count': count,
                'percentage': round(percentage, 1)
            }
        
        return {
            'total_affected_blocks': total_affected_blocks,
            'combinations': combinations_data
        }

def main():
    analyzer = ReorgAnalyzer()
    
    # Get log file path from user
    log_file_path = input("Enter the path to the log file to monitor (or press Enter for default): ").strip()
    if not log_file_path:
        log_file_path = "../../logs/rsk.log"  # Default log file name
    
    print("🚀 Starting reorg monitoring...")
    print("Press Ctrl+C to stop and save results")
    
    try:
        # Process history first
        analyzer.process_log_history(log_file_path)
        print("\n🔄 Starting real-time monitoring...")
        # Then start monitoring in real-time
        analyzer.monitor_log_file(log_file_path)
    except KeyboardInterrupt:
        print("\n💾 Saving results...")
        analyzer.save_results()
        analyzer.plot_reorg_length_histogram()
        print("👋 Monitoring stopped!")

if __name__ == "__main__":
    main() 