#!/usr/bin/env python3
"""
Run the analysis pipeline locally using the Dagger Python SDK.

This script mounts the repository into a Python container, installs
requirements and runs the `system_thinking.py` analysis, writing outputs
directly into the host workspace (via a mounted directory).

Prerequisites:
- Docker (or another OCI runtime) available locally
- Python 3.8+ on host to run this script
- Install Dagger Python SDK: `pip install dagger`
- (Optional) Install Dagger CLI if desired: https://dagger.io/docs

Usage:
    python resources/system_thinking/ci/dagger_local.py

"""
import os
import sys
import textwrap

try:
    import dagger
except Exception:
    print('Dagger SDK not installed. Install with: pip install dagger')
    sys.exit(1)


def main():
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
    print(f'Repo root: {repo_root}')

    print('Connecting to Dagger engine...')
    # Use Connection if available (newer SDKs)
    conn_ctx = getattr(dagger, 'Connection', None) or getattr(dagger, 'Client', None)
    if conn_ctx is None:
        print('Unsupported Dagger SDK API. Please upgrade dagger package.')
        sys.exit(1)

    with conn_ctx() as client:
        print('Preparing container (python:3.11)')
        src = client.host().directory(repo_root)

        container = client.container().from_('python:3.11')
        container = container.with_mounted_directory('/work', src)
        container = container.with_workdir('/work')

        # Install requirements and run the analysis script
        cmd = textwrap.dedent('''
            bash -lc "python -m pip install --upgrade pip && \
            python -m pip install -r resources/system_thinking/requirements.txt || true && \
            python resources/system_thinking/system_thinking.py resources/system_thinking/edges.csv"
        ''')

        print('Running pipeline inside container...')
        container = container.with_exec(["bash", "-lc", cmd])

        try:
            # Stream logs (stdout)
            out = container.stdout()
            print('--- container output start ---')
            print(out)
            print('--- container output end ---')
        except Exception as e:
            print('Failed to read container stdout:', e)

    print('Pipeline finished. Check resources/system_thinking for outputs: graph.png, metrics.json, communities.json')


if __name__ == '__main__':
    main()
