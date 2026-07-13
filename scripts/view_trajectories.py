#!/usr/bin/env python3
"""Render trajectory JSONL files as a readable HTML conversation viewer.

Usage: scripts/view_trajectories.py file.jsonl [file.jsonl.*.xz ...] [--no-open]

Writes /tmp/trajectory-view.html and opens it in the default browser.

Records are one line per LLM call, each holding the full message history it
was prompted with. Per attempt we render the last record's history plus its
response, then attach each call's verbatim reasoning_content to the matching
assistant message (the history itself drops reasoning on non-tool rounds).
"""
import glob
import html
import json
import lzma
import sys
import webbrowser

CSS = """
body{font-family:system-ui,sans-serif;max-width:1000px;margin:2rem auto;padding:0 1rem;background:#fafafa;color:#222}
h2{margin-top:2rem}
pre{white-space:pre-wrap;word-break:break-word;margin:.3rem 0;font-size:.85rem;font-family:ui-monospace,monospace}
.msg{border-left:3px solid #ccc;padding:.4rem .8rem;margin:.5rem 0;background:#fff;border-radius:0 6px 6px 0}
.msg.user{border-color:#2a7ae2}.msg.assistant{border-color:#2e9e5b}
.msg.system{border-color:#999}.msg.tool{border-color:#e2932a}
.role{font-size:.7rem;text-transform:uppercase;letter-spacing:.05em;color:#888}
.reasoning summary{cursor:pointer;color:#8a6ad0;font-size:.8rem}
.reasoning pre{color:#555;background:#f5f2fb;padding:.5rem;border-radius:4px}
.toolcall{color:#a05c00}.toolres{color:#555}
.badge{padding:.1rem .5rem;border-radius:9px;font-size:.75rem;color:#fff;margin-right:.4rem}
.badge.ok{background:#2e9e5b}.badge.fail{background:#d64545}
details.attempt{margin:.6rem 0;background:#fff;border:1px solid #ddd;border-radius:8px;padding:.5rem .8rem}
details.attempt>summary{cursor:pointer;font-weight:600}
.meta{color:#888;font-weight:400;font-size:.85rem}
"""


def esc(s):
    return html.escape(str(s))


def pretty_json(s):
    try:
        return json.dumps(json.loads(s), indent=2)
    except Exception:
        return s


def load(path):
    opener = lzma.open if path.endswith(".xz") else open
    with opener(path, "rt") as f:
        return [json.loads(line) for line in f if line.strip()]


def group_attempts(records):
    """Group records by (run-id, attempt-id), preserving file order."""
    groups, order = {}, []
    for r in records:
        key = (r.get("run-id"), r.get("attempt-id"))
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append(r)
    return [(k, groups[k]) for k in order]


def conversation(recs):
    """Full conversation for an attempt, with reasoning re-attached."""
    last = recs[-1]
    msgs = [dict(m) for m in last["messages"]]
    msgs.append(dict(last["response"], role="assistant"))
    reasonings = [r["response"].get("reasoning_content") for r in recs]
    k = 0
    for m in msgs:
        if m.get("role") == "assistant":
            if k < len(reasonings) and reasonings[k] and not m.get("reasoning_content"):
                m["reasoning_content"] = reasonings[k]
            k += 1
    return msgs


def render_message(m):
    role = m.get("role", "?")
    out = [f'<div class="msg {esc(role)}"><div class="role">{esc(role)}</div>']
    reasoning = m.get("reasoning_content")
    if reasoning:
        out.append(f'<details class="reasoning"><summary>&#128173; reasoning'
                   f' ({len(reasoning):,} chars)</summary><pre>{esc(reasoning)}</pre></details>')
    content = m.get("content")
    if role == "tool":
        out.append(f'<pre class="toolres">&rarr; {esc(pretty_json(content))}</pre>')
    elif content:
        out.append(f'<pre>{esc(content)}</pre>')
    for tc in m.get("tool_calls") or []:
        fn = tc.get("function", {})
        args = fn.get("arguments", "")
        try:
            args = ", ".join(f"{k}={json.dumps(v)}" for k, v in json.loads(args).items())
        except Exception:
            pass
        out.append(f'<pre class="toolcall">&#128295; {esc(fn.get("name", "?"))}({esc(args)})</pre>')
    out.append("</div>")
    return "".join(out)


def render_attempt(key, recs):
    _, attempt_id = key
    badge = ('<span class="badge ok">success</span>' if recs[-1]["success"]
             else '<span class="badge fail">fail</span>')
    msgs = conversation(recs)
    ntools = sum(len(m.get("tool_calls") or []) for m in msgs)
    return (f'<details class="attempt"><summary>{badge}attempt {esc(attempt_id)}'
            f' <span class="meta">{len(recs)} LLM calls, {ntools} tool calls</span></summary>'
            + "".join(render_message(m) for m in msgs) + "</details>")


def main():
    files = [a for a in sys.argv[1:] if a != "--no-open"]
    if not files:
        available = "\n  ".join(sorted(glob.glob("trajectories-*.jsonl")
                                       + glob.glob("trajectories-*.xz"))) or "(none found)"
        sys.exit(f"usage: view_trajectories.py file.jsonl [...] [--no-open]\n"
                 f"available:\n  {available}")

    parts = [f"<meta charset='utf-8'><title>trajectories</title><style>{CSS}</style>",
             "<h1>Trajectories</h1>"]
    for path in files:
        attempts = group_attempts(load(path))
        nsuccess = sum(1 for _, recs in attempts if recs[-1]["success"])
        parts.append(f"<h2>{esc(path)} <span class='meta'>{nsuccess}/{len(attempts)}"
                     f" attempts successful</span></h2>")
        parts.extend(render_attempt(key, recs) for key, recs in attempts)

    out = "/tmp/trajectory-view.html"
    with open(out, "w") as f:
        f.write("\n".join(parts))
    print(f"wrote {out}")
    if "--no-open" not in sys.argv:
        webbrowser.open(f"file://{out}")


if __name__ == "__main__":
    main()
