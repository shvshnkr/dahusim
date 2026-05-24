#!/usr/bin/env python3
"""
Python utility to extract contextual chunks around log lines that match important keywords.
Usage:
  python3 tools/extract_log_context.py --logfile /path/to/log.txt [--context 20] [--case-insensitive] [--output out.txt]

If the log is very large, this runs in two passes:
- first pass collects matching line indices
- second pass prints blocks around those indices with a configurable context
"""
import argparse
import re
from typing import List, Tuple

KW_PAIRS = [
    r"disconnect",
    r"DISCONNECT",
    r"disconnected",
    r"timeout",
    r"error",
    r"exception",
    r"crash",
    r"reconnect",
]

def find_match_indices(log_path: str, patterns: List[str], case_insensitive: bool) -> List[int]:
    flags = re.IGNORECASE if case_insensitive else 0
    pat = "|".join(f"(?:{p})" for p in patterns)
    regex = re.compile(pat, flags)
    indices: List[int] = []
    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        for i, line in enumerate(f):
            if regex.search(line):
                indices.append(i)
    return indices

def merge_blocks(indices: List[int], context: int) -> List[Tuple[int,int]]:
    blocks: List[Tuple[int,int]] = []
    for idx in indices:
        start = max(0, idx - context)
        end = idx + context
        if not blocks:
            blocks.append((start, end))
        else:
            last_start, last_end = blocks[-1]
            if start <= last_end + 1:
                blocks[-1] = (last_start, max(last_end, end))
            else:
                blocks.append((start, end))
    return blocks

def print_blocks(log_path: str, blocks: List[Tuple[int,int]]) -> None:
    if not blocks:
        print("No matching events found in log.")
        return
    with open(log_path, 'r', encoding='utf-8', errors='ignore') as f:
        current_block = 0
        block_start, block_end = blocks[0]
        printed_header = False
        for i, line in enumerate(f):
            # move to the current block if needed
            while current_block < len(blocks) and i > blocks[current_block][1]:
                current_block += 1
                printed_header = False
                if current_block >= len(blocks):
                    return
                block_start, block_end = blocks[current_block]
            if i < block_start:
                continue
            if i == block_start and not printed_header:
                print(f"==== MATCH BLOCK {current_block+1}: lines {block_start+1}-{block_end+1} ====")
                printed_header = True
            print(line, end='')

def main():
    parser = argparse.ArgumentParser(description="Extract contextual blocks around log keywords.")
    parser.add_argument('--logfile', required=True, help='Path to the log file')
    parser.add_argument('--context', type=int, default=20, help='Number of lines before/after the match to include (default 20)')
    parser.add_argument('--case-insensitive', action='store_true', help='Apply case-insensitive matching')
    parser.add_argument('--output', '-o', help='Optional path to write output')
    parser.add_argument('--limit', type=int, default=0, help='Limit number of blocks to print (0 = no limit)')
    args = parser.parse_args()

    indices = find_match_indices(args.logfile, KW_PAIRS, args.case_insensitive)
    blocks = merge_blocks(indices, args.context)
    if args.limit > 0:
        blocks = blocks[:args.limit]

    if args.output:
        # Write to file
        with open(args.output, 'w', encoding='utf-8') as out_f:
            # Redirect stdout to file by temporarily swapping
            import sys
            old_stdout = sys.stdout
            sys.stdout = out_f
            print_blocks(args.logfile, blocks)
            sys.stdout = old_stdout
        print(f"Wrote {len(blocks)} blocks to {args.output}")
    else:
        print_blocks(args.logfile, blocks)

if __name__ == '__main__':
    main()
