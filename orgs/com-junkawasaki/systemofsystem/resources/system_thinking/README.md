# System Thinking (networkx) sample

This folder contains a small example to represent the causal system shown in the provided slides.

Files:
- `edges.csv`: directed edge list (source,target,weight)
- `system_thinking.py`: script to build the graph, analyze centralities, detect communities and plot a PNG
- `metrics.json`: generated metrics after running the script
- `communities.json`: generated communities after running the script
- `graph.png`: generated plot

Quick start:

Install dependencies:
```bash
python3 -m pip install -r resources/system_thinking/requirements.txt
```

Run analysis:
```bash
python3 resources/system_thinking/system_thinking.py resources/system_thinking/edges.csv
```

Outputs will be saved in the same folder.

Run with Dagger locally
-----------------------
If you have Dagger installed you can run the pipeline inside an ephemeral container
so the host environment stays clean. This mounts the repository into the container
and runs the same analysis script. It requires the Dagger Python SDK and a local
OCI runtime (Docker).

Install SDK:
```bash
python3 -m pip install dagger
```

Run the Dagger local pipeline:
```bash
python3 resources/system_thinking/ci/dagger_local.py
```

Notes:
- The script uses the `python:3.11` base image; adjust `dapper_local.py` if you need a different image.
- Outputs (graph.png, metrics.json, communities.json) are written to `resources/system_thinking`.
