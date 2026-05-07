# Sbench E2E
Clojure codebase for E2E reinforcement on SherlockBench.

It acts as a client, which means it sits in-between the sherlockbench-api
and the LLM API.

It needs to be used in combination with a custom proxy which sits in-front
of llama-server, and allows us to save the good and bad responses in a
format compatible with DPO.

## Development

### Compile
Compile your .jar:
```
clj -T:build uber
```
