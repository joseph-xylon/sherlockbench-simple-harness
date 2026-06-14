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
