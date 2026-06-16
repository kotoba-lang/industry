"""
Client for the Modal vLLM endpoints (MiniMax-M2.7 or Kimi-K2.7-Code).

Pick a target with env vars (URLs printed by `modal deploy`):
  # MiniMax-M2.7
  export LLM_URL="https://<workspace>--minimax-m2-7-serve.modal.run"
  export LLM_MODEL="MiniMaxAI/MiniMax-M2.7"
  export LLM_KEY="minimax-m2-7-testkey-7f3a"
  # Kimi-K2.7-Code
  export LLM_URL="https://<workspace>--kimi-k27-code-serve.modal.run"
  export LLM_MODEL="moonshotai/Kimi-K2.7-Code"
  export LLM_KEY="kimi-k27-code-testkey-7f3a"

Then:
  python client.py chat      # interactive chat REPL
  python client.py tools     # tool-calling demo (weather function)
  python client.py coding    # coding task demo

Requires: pip install openai
"""
import json
import os
import sys

from openai import OpenAI

MODEL = os.environ.get("LLM_MODEL", "MiniMaxAI/MiniMax-M2.7")
API_KEY = os.environ.get("LLM_KEY", "minimax-m2-7-testkey-7f3a")

url = os.environ.get("LLM_URL") or os.environ.get("MINIMAX_URL")
if not url:
    sys.exit("Set LLM_URL to the *.modal.run endpoint (without trailing /v1).")

print(f"[target] {MODEL} @ {url}")
client = OpenAI(base_url=url.rstrip("/") + "/v1", api_key=API_KEY, timeout=1800, max_retries=0)


def _show(resp):
    msg = resp.choices[0].message
    reasoning = getattr(msg, "reasoning_content", None)
    if reasoning:
        print(f"\n[reasoning]\n{reasoning}\n")
    if msg.content:
        print(msg.content)
    return msg


def chat():
    print("MiniMax-M2.7 chat. Ctrl-C to exit.")
    history = []
    while True:
        try:
            user = input("\nyou> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not user:
            continue
        history.append({"role": "user", "content": user})
        resp = client.chat.completions.create(
            model=MODEL, messages=history, temperature=1.0, top_p=0.95, max_tokens=2048
        )
        msg = _show(resp)
        history.append({"role": "assistant", "content": msg.content or ""})


def tools():
    tool_defs = [{
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get the current weather for a city.",
            "parameters": {
                "type": "object",
                "properties": {
                    "city": {"type": "string", "description": "City name"},
                    "unit": {"type": "string", "enum": ["c", "f"]},
                },
                "required": ["city"],
            },
        },
    }]
    messages = [{"role": "user",
                 "content": "What's the weather in Tokyo right now? Use the tool, then tell me in Celsius."}]
    resp = client.chat.completions.create(model=MODEL, messages=messages, tools=tool_defs, temperature=1.0)
    msg = _show(resp)
    if not msg.tool_calls:
        print("(model did not call a tool)")
        return
    messages.append(msg)
    for tc in msg.tool_calls:
        args = json.loads(tc.function.arguments)
        print(f"\n[tool_call] {tc.function.name}({args})")
        # fake tool result
        result = {"city": args.get("city"), "temp_c": 22, "condition": "clear"}
        messages.append({"role": "tool", "tool_call_id": tc.id,
                         "content": json.dumps(result)})
    resp2 = client.chat.completions.create(model=MODEL, messages=messages, tools=tool_defs, temperature=1.0)
    print("\n[final answer]")
    _show(resp2)


def coding():
    prompt = (
        "Write a Python function `merge_intervals(intervals)` that merges overlapping "
        "intervals given as a list of [start, end] pairs. Include 3 doctest examples "
        "and explain the time complexity."
    )
    resp = client.chat.completions.create(
        model=MODEL, messages=[{"role": "user", "content": prompt}],
        temperature=1.0, top_p=0.95, max_tokens=2048,
    )
    _show(resp)


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "chat"
    {"chat": chat, "tools": tools, "coding": coding}.get(mode, chat)()
