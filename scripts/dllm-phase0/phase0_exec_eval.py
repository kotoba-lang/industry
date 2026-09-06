"""Phase 0, second half: does the parallel-decode gain survive a correctness gate?

The first half measured tok/forward and a structural degeneracy check (distinct
ratio, longest immediate repeat).  Both said "fine" for every setting.  Reading
the generated code showed all of them were wrong -- a missing sort statement at
threshold 0.5, `return return merged` at 0.7, an `interval[1] <=` overlap test at
0.9 and 0.99.  distinct_ratio cannot see any of that.

So the number that matters is not tok/forward.  It is tok/forward AT EQUAL
CORRECTNESS, and correctness here means the generated function actually passes
tests.  This script scores by execution, the same way ADR-2607062300 did.

Refusals built in:
  * A task whose generated text contains no extractable function is scored 0,
    not skipped -- a decode that emits prose must not raise the pass rate by
    shrinking the denominator.
  * Every (block, threshold) cell runs the same task set in the same order with
    greedy decoding, so cells are comparable.
  * block=1 is the AR control and must land at exactly 1.000 tok/forward.
  * Generated code runs in a subprocess with a wall-clock timeout, so an
    infinite loop is a failure, not a hang.
"""

import argparse
import json
import re
import subprocess
import sys
import tempfile
import textwrap

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MASK_ID = 151669
EOS_IDS = (151645, 151643)

TASKS = [
    {
        "name": "merge_intervals",
        "prompt": "Write a Python function `merge_intervals(intervals)` that merges overlapping intervals given as a list of [start, end] pairs and returns the merged list sorted by start. Return only the code, no explanation.",
        "tests": """
assert merge_intervals([]) == []
assert merge_intervals([[1,3]]) == [[1,3]]
assert merge_intervals([[1,3],[2,6],[8,10],[15,18]]) == [[1,6],[8,10],[15,18]]
assert merge_intervals([[1,4],[4,5]]) == [[1,5]]
assert merge_intervals([[5,6],[1,3]]) == [[1,3],[5,6]]
""",
    },
    {
        "name": "two_sum",
        "prompt": "Write a Python function `two_sum(nums, target)` that returns the indices of the two numbers adding to target, as a list of two ints in increasing order. Return only the code, no explanation.",
        "tests": """
assert sorted(two_sum([2,7,11,15], 9)) == [0,1]
assert sorted(two_sum([3,2,4], 6)) == [1,2]
assert sorted(two_sum([3,3], 6)) == [0,1]
""",
    },
    {
        "name": "is_balanced",
        "prompt": "Write a Python function `is_balanced(s)` that returns True if the brackets in the string s are balanced, considering (), [] and {}. Return only the code, no explanation.",
        "tests": """
assert is_balanced("") is True or is_balanced("") == True
assert is_balanced("()[]{}") == True
assert is_balanced("(]") == False
assert is_balanced("([{}])") == True
assert is_balanced("(") == False
""",
    },
    {
        "name": "run_length",
        "prompt": "Write a Python function `run_length(s)` that run-length encodes a string, returning a list of (char, count) tuples in order. Return only the code, no explanation.",
        "tests": """
assert run_length("") == []
assert run_length("aaabbc") == [("a",3),("b",2),("c",1)]
assert run_length("abc") == [("a",1),("b",1),("c",1)]
""",
    },
    {
        "name": "flatten",
        "prompt": "Write a Python function `flatten(xs)` that flattens an arbitrarily nested list of lists into a single flat list, preserving order. Return only the code, no explanation.",
        "tests": """
assert flatten([]) == []
assert flatten([1,[2,[3,4]],5]) == [1,2,3,4,5]
assert flatten([[[[1]]]]) == [1]
""",
    },
]


def block_causal_mask(n_prompt, n_gen, block, device):
    """Bool mask, True = attend.  SDARAttention calls .bool() and hands it to SDPA."""
    L = n_prompt + n_gen
    i = torch.arange(L, device=device)
    allow = i[None, :] <= i[:, None]
    g = i - n_prompt
    in_gen = g >= 0
    blk = torch.where(in_gen, torch.div(g, block, rounding_mode="floor"), -1)
    return (allow | ((blk[:, None] == blk[None, :]) & in_gen[:, None] & in_gen[None, :]))[None, None]


@torch.no_grad()
def decode(model, prompt_ids, block, max_new, threshold, device):
    n_prompt = len(prompt_ids)
    seq = torch.tensor([prompt_ids], device=device)
    committed, forwards, hit_eos = [], 0, False
    while len(committed) < max_new and not hit_eos:
        blk = min(block, max_new - len(committed))
        cur = torch.cat([seq, torch.full((1, blk), MASK_ID, device=device, dtype=seq.dtype)], 1)
        n_gen = cur.shape[1] - n_prompt
        pending = list(range(cur.shape[1] - blk, cur.shape[1]))
        while pending:
            logits = model(input_ids=cur,
                           attention_mask=block_causal_mask(n_prompt, n_gen, block, device)).logits
            forwards += 1
            probs = torch.softmax(logits[0, pending].float(), -1)
            conf, pick = probs.max(-1)
            take = (conf >= threshold).nonzero(as_tuple=True)[0].tolist() or [int(conf.argmax())]
            for j in sorted(take, reverse=True):
                cur[0, pending[j]] = pick[j]
            for j in sorted(take, reverse=True):
                pending.pop(j)
        for t in cur[0, seq.shape[1]:].tolist():
            committed.append(t)
            if t in EOS_IDS:
                hit_eos = True
                break
        seq = cur
    return committed, forwards


def extract_code(text):
    m = re.search(r"```(?:python)?\n(.*?)```", text, re.S)
    body = m.group(1) if m else text
    lines, out, started = body.split("\n"), [], False
    for ln in lines:
        if ln.startswith("def ") or ln.startswith("import ") or ln.startswith("from "):
            started = True
        if started:
            out.append(ln)
    return "\n".join(out).strip()


def run_tests(code, tests, timeout=10):
    """0 if it does not import, define, or pass.  A non-extractable answer is 0, not skipped."""
    if not code or "def " not in code:
        return False, "no function"
    prog = code + "\n" + textwrap.dedent(tests) + "\nprint('OK')\n"
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as f:
        f.write(prog)
        path = f.name
    try:
        r = subprocess.run([sys.executable, path], capture_output=True, text=True, timeout=timeout)
        return ("OK" in r.stdout), (r.stderr.strip().split("\n")[-1][:90] if r.returncode else "")
    except subprocess.TimeoutExpired:
        return False, "timeout"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="/home/gad/dllm-phase0/SDAR-1.7B-Chat")
    ap.add_argument("--blocks", default="1,4,8")
    ap.add_argument("--thresholds", default="0.5,0.7,0.9,0.99")
    ap.add_argument("--max-new", type=int, default=192)
    ap.add_argument("--out", default="/home/gad/dllm-phase0/phase0-exec-eval.json")
    args = ap.parse_args()

    device = "cuda"
    tok = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        args.model, trust_remote_code=True, torch_dtype=torch.bfloat16, attn_implementation="eager"
    ).to(device).eval()

    rows = []
    for block in [int(x) for x in args.blocks.split(",")]:
        for thr in [float(x) for x in args.thresholds.split(",")]:
            if block == 1 and thr != 0.9:
                continue  # the AR control does not depend on threshold
            passed, toks, fwds, notes = 0, 0, 0, []
            for t in TASKS:
                ids = tok.apply_chat_template(
                    [{"role": "user", "content": t["prompt"]}],
                    add_generation_prompt=True, tokenize=True,
                )
                out, f = decode(model, ids, block, args.max_new, thr, device)
                toks += len(out)
                fwds += f
                text = tok.decode(out, skip_special_tokens=True)
                ok, err = run_tests(extract_code(text), t["tests"])
                passed += int(ok)
                notes.append(f"{t['name']}={'PASS' if ok else 'fail:' + (err or '?')}")
            row = {
                "block": block, "threshold": thr,
                "pass": passed, "of": len(TASKS),
                "tok_per_forward": round(toks / max(fwds, 1), 3),
                "tokens": toks, "forwards": fwds, "detail": notes,
            }
            rows.append(row)
            print(f"block={block:<3} thr={thr:<5} pass={passed}/{len(TASKS)} "
                  f"tok/forward={row['tok_per_forward']:<6} forwards={fwds}", flush=True)
            for n in notes:
                print("      ", n, flush=True)

    json.dump({"model": args.model, "rows": rows}, open(args.out, "w"), indent=1, ensure_ascii=False)
    print("wrote", args.out, flush=True)


if __name__ == "__main__":
    main()
