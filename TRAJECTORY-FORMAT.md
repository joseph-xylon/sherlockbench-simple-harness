# Trajectory file format

Spec for the `trajectories-*.jsonl` files written by the harness
(`sbench-simple-harness.run-bench/save-trajectory`), for whoever implements
training (SFT or KTO) on top of them.

## Files

- `trajectories-<problem-set>.jsonl` — one file per problem set, in the
  directory the harness runs from. `/` in the problem-set name is replaced
  with `_`.
- `trajectories-<problem-set>.jsonl.<epoch-millis>.xz` — rotated files. When
  the live file reaches 50 MB it is renamed with a millisecond timestamp and
  xz-compressed; writing continues in a fresh file under the original name.
  Rotated files hold older data than the live file. To process everything,
  read all rotated files (sorted by timestamp) then the live file.
- `trajectories-<problem-set>.jsonl.lock` — empty lock sidecar used to
  serialize concurrent writers. Ignore.

Encoding: UTF-8, one JSON object per line (JSONL).

## Records

One line = **one LLM call during the investigation phase**: the exact prompt
sent and the verbatim completion received. LLM calls made during the
verification phase are *not* recorded.

```jsonc
{
  "messages":   [ ... ],       // exact prompt: OpenAI-style message array
  "params":     { "tools": [ ... ] },  // extra request params sent with it
  "response":   {              // verbatim assistant message returned
    "role": "assistant",
    "content": "...",              // may be empty on pure tool-call turns
    "reasoning_content": "...",    // chain of thought; may be absent/empty
    "tool_calls": [                // absent or OpenAI-style calls
      { "id": "...", "type": "function",
        "function": { "name": "mystery_function",
                      "arguments": "{\"a\":1}" } }  // JSON *string*
    ]
  },
  "run-id":     "645b58ad-...",  // UUID of the benchmark run
  "attempt-id": "cd70e817-...",  // UUID of the attempt (one problem instance)
  "success":    true             // verification outcome of the whole attempt
}
```

Note the key style split: harness fields use hyphens (`run-id`, `attempt-id`),
OpenAI fields use underscores (`reasoning_content`, `tool_calls`).

`messages` roles are `system`, `user`, `assistant`, `tool`. Tool result
messages look like
`{"role": "tool", "content": "\"1\"", "tool_call_id": "..."}` —
`content` is the JSON-encoded return value of the mystery function.

## Grouping into attempts

An **attempt** is one complete trajectory: the model investigates a mystery
function over several LLM calls, then verification labels the whole thing.
Group records by `(run-id, attempt-id)` — attempt IDs are only guaranteed
unique within a run.

- All records of one attempt are **contiguous** in one file, in chronological
  call order (they are appended in a single locked write, and rotation only
  happens between attempts, never mid-attempt).
- Attempts from concurrent runs on the same problem set may interleave at
  attempt granularity, and a run's attempts may span a rotation boundary.

## The prefix property (important)

Within an attempt, each record's `messages` is a strict prefix extension of
the previous record's: record *k+1* = record *k*'s `messages` + the assistant
message rebuilt from record *k*'s `response` + any tool result messages. So:

- The **last** record of an attempt contains the (almost) full conversation;
  earlier records are redundant prefixes. Deduplicate accordingly.
- The *k*-th record's `response` corresponds to the *k*-th assistant message
  in any later record's `messages` (and to the final assistant turn, missing
  from `messages`, for the last record).

**Lossiness:** the assistant messages inside `messages` are *rebuilt* and
drop `reasoning_content`, except during uninterrupted tool-call rounds when
interleaved thinking is enabled (the current configuration — in practice most
history messages do carry reasoning, but do not rely on it). The verbatim
completion, including full reasoning, exists **only** in each record's
`response`. Always take completions from `response`, never from a later
record's `messages`.

## Label semantics

- `success` is the verification verdict for the **entire attempt**, stamped
  identically on every record of that attempt: `true` = every verification
  prediction was correct, `false` = a prediction was wrong.
- Records written before 2026-07-12 lack the `success` key. Only successful
  attempts were saved back then, so treat a missing key as `true`.

## Using it for training

Each record is a ready-made (prompt, completion) example: prompt =
`messages` (plus the tool definitions in `params`), completion = `response`.

- **SFT**: keep records with `success == true` (or missing). Either train on
  every step, or take one example per attempt from the last record to avoid
  over-weighting long trajectories' early context.
- **KTO**: `success` is the binary desirable/undesirable label. No pairing of
  positives and negatives is needed. Note the label is attempt-level: every
  step of a failed attempt is marked undesirable, including possibly-fine
  intermediate steps. Keep whole attempts together when splitting datasets to
  avoid leakage between near-identical prefix records.
- The final verification exchange (prediction that decided `success`) is not
  in the data; a failed attempt's records show an investigation that *led to*
  a wrong prediction.
