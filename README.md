# Sbench E2E
Harness to allow LLMs to compete at SherlockBench.

It sits in-between the sherlockbench-api and the LLM API.

## Development

### Run
```
clj -M -m sbench-simple-harness.core list
clj -M -m sbench-simple-harness.core start sherlock1/all 1
```

### Compile
Compile your .jar:
```
clj -T:build uber
```

## Parallel tool calls
Set `:parallel-tool-calls false` in `config.edn` to limit the model to one tool
call per turn. Omit it or set true for the original behaviour.

## Run stats
Completed runs append per-attempt results to `run-stats.edn`. Print
per-function success rates for one or more runs (requires babashka):
```
scripts/run-stats <run-id> [run-id ...]
```
