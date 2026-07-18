#!/usr/bin/env python3
"""Per-problem-set counts of successful trajectories (attempts) and
completions (records) in trajectory JSONL files, aggregating rotated
.jsonl.<ts>.xz segments into their base file.

Usage: scripts/trajectory_stats.py [file.jsonl|file.jsonl.*.xz ...]

With no arguments, reports on all trajectories-*.jsonl and rotated
segments in the current directory. See TRAJECTORY-FORMAT.md.
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
    return ok_attempts, attempts, ok_records, records


def main():
    files = sys.argv[1:] or sorted(
        glob.glob("trajectories-*.jsonl") + glob.glob("trajectories-*.jsonl.*.xz"))
    if not files:
        sys.exit("no trajectory files found")

    groups = {}
    for path in files:
        base = path.split(".jsonl")[0] + ".jsonl"
        groups.setdefault(base, []).append(path)

    rows = []
    for base, paths in sorted(groups.items()):
        ok_a, n_a, ok_r, n_r = set(), set(), 0, 0
        for path in paths:
            s_ok_a, s_a, s_ok_r, s_r = stats(path)
            ok_a |= s_ok_a
            n_a |= s_a
            ok_r += s_ok_r
            n_r += s_r
        rows.append((base, len(ok_a), len(n_a), ok_r, n_r))
    rows.append(("TOTAL", *(sum(r[i] for r in rows) for i in range(1, 5))))

    width = max(len(r[0]) for r in rows)
    print(f"{'file':<{width}}  {'trajectories':>14}  {'completions':>13}   (successful/total)")
    for path, ok_a, n_a, ok_r, n_r in rows:
        print(f"{path:<{width}}  {f'{ok_a}/{n_a}':>14}  {f'{ok_r}/{n_r}':>13}")


if __name__ == "__main__":
    main()
