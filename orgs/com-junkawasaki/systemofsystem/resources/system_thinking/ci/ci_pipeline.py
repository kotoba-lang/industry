#!/usr/bin/env python3
import os
import sys
import subprocess

try:
    import dagger
except Exception:
    dagger = None


def run_fallback():
    print('Running fallback pipeline (local execution)')
    req = os.path.join(os.path.dirname(__file__), '..', 'requirements.txt')
    script = os.path.join(os.path.dirname(__file__), '..', 'system_thinking.py')
    edges = os.path.join(os.path.dirname(__file__), '..', 'edges.csv')
    # ensure requirements installed (best-effort)
    try:
        subprocess.check_call([sys.executable, '-m', 'pip', 'install', '--upgrade', 'pip'])
        subprocess.check_call([sys.executable, '-m', 'pip', 'install', '-r', req])
    except subprocess.CalledProcessError as e:
        print('Warning: failed to install some requirements:', e)
    # run analysis
    try:
        subprocess.check_call([sys.executable, script, edges])
    except subprocess.CalledProcessError as e:
        print('Fallback analysis failed:', e)


def run_with_dagger():
    print('Dagger SDK detected. Attempting to run pipeline with Dagger...')
    # The Dagger Python SDK usage depends on the engine being available.
    # This example attempts a safe connection; if it fails, it falls back.
    try:
        # Use Connection if available (recent SDKs)
        if hasattr(dagger, 'Connection'):
            with dagger.Connection() as client:
                print('Connected to Dagger engine (Connection)')
                # Example: run a simple container task (commented because SDK API varies)
                # container = client.container().from_('python:3.11').with_exec(['python','-c','print("hello from dagger")'])
                # print(container.stdout())
                print('Dagger pipeline placeholder - customize tasks here')
        elif hasattr(dagger, 'Client'):
            with dagger.Client() as client:
                print('Connected to Dagger engine (Client)')
                print('Dagger pipeline placeholder - customize tasks here')
        else:
            print('Dagger package present but no known client API found; falling back')
            run_fallback()
    except Exception as e:
        print('Failed to run with Dagger engine:', e)
        run_fallback()


def main():
    print('CI pipeline start')
    if dagger:
        run_with_dagger()
    else:
        print('Dagger SDK not installed; running fallback flow')
        run_fallback()


if __name__ == '__main__':
    main()
