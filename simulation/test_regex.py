import re
from datetime import datetime, timezone, timedelta

def test_process_line(line):
    print("Testing line:", line)
    print("Contains 'IMPORTED':", 'IMPORTED' in line)
    
    if 'IMPORTED' in line:
        pattern = r'(\d+-\d+-\d+-\d+:\d+:\d+\.\d+).*block: num: \[(\d+)\].*hash:\s*\[([0-9a-fA-F]+)\],\s*parentHash:\[(\w+)\],\s*coinbase:\[(\w+)\],\s*uncles:\[([0-9a-fA-F, ]*)\],\s*difficulty:\[(\d+)\],\s*txs:\[(\d+)\],\s*txsHashes:\[([0-9a-fA-F, ]*)\],\s*timestamp:(\d+),.*result (.*)'
        match = re.search(pattern, line)
        print("Match found:", match is not None)
        if match:
            print("Groups:", match.groups())
            return True
        else:
            print("No match despite containing 'IMPORTED'")
            return False
    else:
        print("Line does not contain 'IMPORTED'")
        return False

# Test with your log line
test_line = '2025-07-07-15:49:52.0484 INFO [blockchain] [async block processor] [blockHeight=7752275, blockHash=34e130b9a03e2df7e1a31c819fe0136ebd4a752d1a8b017bfcfb91917a913f2e]  block: num: [7752275] hash: [34e130b9a03e2df7e1a31c819fe0136ebd4a752d1a8b017bfcfb91917a913f2e], parentHash:[f213a67e8a21adf37bb05d7541d34e4abc6290bb77bf1e04c8da1624d1cd8706], coinbase:[ce7864a8b5bf360b01099502a163810cec845d4a], uncles:[], difficulty:[10123298593964216835166], txs:[3], txsHashes:[6c410467d565a78e0c33a63f1cc3ef990649b755eb4e24198fb59b2e4b92b612, 2c59661c3c9c9268293f20183bd28148b2c8205022a3336a565af3a53dff105c, aafe35441392c97394591f96f822e4a4fc443339626a17b6497197c21c511cf8, ], timestamp:1751914156, processed after: [0.002443]seconds, result IMPORTED_NOT_BEST'

result = test_process_line(test_line)
print(f"\nFinal result: {result}") 