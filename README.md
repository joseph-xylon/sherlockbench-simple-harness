# Sbench E2E
Harness to allow LLMs to compete at SherlockBench.

It sits in-between the sherlockbench-api and the LLM API.

## Config
We got config merging. First it reads `resources/config.edn`. Then it merges what config you specified with --config flag.

## Development

### Run
This branch is for training so the run instructions are a bit different. problem-set is specified in config instead of on CLI, and we have a CLI option for specifying which llama-server slot to use.
```
clj -M -m sbench-simple-harness.core start --config resources/config-local-1.edn --slot 0 start
```

Recommend put this function in your bash config:
```
whilenotstop() {
    local stop_file=".stop"
    
    if [ -z "$1" ]; then
        echo "Usage: whilenotstop <command>"
        return 1
    fi

    echo "Starting loop. Open another terminal and run 'touch $stop_file' to stop."

    # run the command until it fails or the file exists
    while "$@" ; do
        if [[ -f "$stop_file" ]] ; then
           echo "Stop file detected! Ending loop."
           return
        fi

        sleep 1
    done

    echo "Command failed. Ending loop"
}

```

Then, assuming you have 8 llama-server slots and 10 configs, open 8 terminals and run this, editing just slot each time:
```
whilenotstop bash -c 'clj -M -m sbench-simple-harness.core --config resources/config-local-$((RANDOM % 10)).edn --slot 0 start'
```

### Compile
Compile your .jar:
```
clj -T:build uber
```
