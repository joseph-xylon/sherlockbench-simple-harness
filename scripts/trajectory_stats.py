#!/usr/bin/env python3
"""Per-file counts of successful trajectories (attempts) and completions
(records) in trajectory JSONL files.

Usage: scripts/trajectory_stats.py [file.jsonl|file.jsonl.*.xz ...]

With no arguments, reports on all trajectories-*.jsonl in the current
directory. See TRAJECTORY-FORMAT.md.
"""
import glob
import json
import lzma
import sys


def stats(path):
    opener = lzma.open if path.endswith(".xz") else open
    records = ok_records = 0
    attempts, ok_attempts = set(), set()
    with opener(path, "rt") as f:
        for line in f:
            if not line.strip():
                continue
            r = json.loads(line)
            key = (r.get("run-id"), r.get("attempt-id"))
            success = r["success"]
            records += 1
            attempts.add(key)
            if success:
                ok_records += 1
                ok_attempts.add(key)
    return len(ok_attempts), len(attempts), ok_records, records


def main():
    files = sys.argv[1:] or sorted(glob.glob("trajectories-*.jsonl"))
    if not files:
        sys.exit("no trajectory files found")

    rows = [(path, *stats(path)) for path in files]
    rows.append(("TOTAL", *(sum(r[i] for r in rows) for i in range(1, 5))))

    width = max(len(r[0]) for r in rows)
    print(f"{'file':<{width}}  {'trajectories':>14}  {'completions':>13}   (successful/total)")
    for path, ok_a, n_a, ok_r, n_r in rows:
        print(f"{path:<{width}}  {f'{ok_a}/{n_a}':>14}  {f'{ok_r}/{n_r}':>13}")


if __name__ == "__main__":
    main()
