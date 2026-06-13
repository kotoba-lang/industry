"""
Non-interactive head-to-head bench for the Modal vLLM endpoints.
Reads LLM_URL / LLM_MODEL / LLM_KEY from env. Runs 3 fixed tasks and prints
results + latency so MiniMax-M2.7 and Kimi-K2.7-Code can be compared directly.

  .venv/bin/python bench.py
"""
import json
import os
import sys
import time

from openai import OpenAI

MODEL = os.environ["LLM_MODEL"]
API_KEY = os.environ["LLM_KEY"]
URL = os.environ["LLM_URL"].rstrip("/")

client = OpenAI(base_url=URL + "/v1", api_key=API_KEY, timeout=1800, max_retries=0)


def show(resp, label):
    msg = resp.choices[0].message
    r = getattr(msg, "reasoning_content", None)
    u = resp.usage
    print(f"\n========== {label} ==========")
    if r:
        print(f"[reasoning {len(r)} chars] {r[:600]}{'...' if len(r) > 600 else ''}\n")
    print(msg.content or "(no content)")
    if u:
        print(f"\n[tokens] prompt={u.prompt_tokens} completion={u.completion_tokens}")
    return msg


def t_reasoning():
    t = time.time()
    resp = client.chat.completions.create(
        model=MODEL, temperature=1.0, top_p=0.95, max_tokens=1500,
        messages=[{"role": "user", "content":
            "A farmer has 17 sheep. All but 9 run away. Then he buys 5 more, and "
            "twice as many goats as the sheep he now has. How many animals total? "
            "Show your reasoning step by step, then give the final number."}])
    show(resp, "1) REASONING")
    print(f"[latency] {time.time()-t:.1f}s")


def t_tools():
    tools = [{"type": "function", "function": {
        "name": "get_weather",
        "description": "Get current weather for a city.",
        "parameters": {"type": "object", "properties": {
            "city": {"type": "string"}, "unit": {"type": "string", "enum": ["c", "f"]}},
            "required": ["city"]}}}]
    msgs = [{"role": "user", "content":
             "What's the weather in Tokyo? Use the tool, then answer in Celsius."}]
    t = time.time()
    resp = client.chat.completions.create(model=MODEL, messages=msgs, tools=tools, temperature=1.0)
    msg = show(resp, "2) TOOL CALL (round 1)")
    if not msg.tool_calls:
        print(">>> MODEL DID NOT CALL THE TOOL")
        print(f"[latency] {time.time()-t:.1f}s"); return
    msgs.append(msg)
    for tc in msg.tool_calls:
        args = json.loads(tc.function.arguments)
        print(f">>> tool_call: {tc.function.name}({args})")
        msgs.append({"role": "tool", "tool_call_id": tc.id,
                     "content": json.dumps({"city": args.get("city"), "temp_c": 22, "condition": "clear"})})
    resp2 = client.chat.completions.create(model=MODEL, messages=msgs, tools=tools, temperature=1.0)
    show(resp2, "2) TOOL CALL (final)")
    print(f"[latency] {time.time()-t:.1f}s")


def t_coding():
    t = time.time()
    resp = client.chat.completions.create(
        model=MODEL, temperature=1.0, top_p=0.95, max_tokens=2000,
        messages=[{"role": "user", "content":
            "Write a Python function `merge_intervals(intervals)` that merges overlapping "
            "intervals (list of [start, end]). Include 3 doctest examples and state the "
            "time complexity."}])
    show(resp, "3) CODING")
    print(f"[latency] {time.time()-t:.1f}s")


if __name__ == "__main__":
    print(f"### TARGET: {MODEL} @ {URL}")
    t_reasoning()
    t_tools()
    t_coding()
    print("\n### DONE")
