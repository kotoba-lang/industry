"""Prove the task set before using it to score a model.

A task whose tests cannot be passed by a correct implementation lowers every
cell's pass rate equally and silently -- it looks like the model got worse.
So: write a reference solution for each task, assert it passes, and assert a
deliberately broken variant FAILS.  A harness that never rejects anything is
not measuring correctness.
"""

import json
import subprocess
import sys
import tempfile

REFERENCE = {
"merge_intervals": """
def merge_intervals(intervals):
    if not intervals:
        return []
    xs = sorted([list(i) for i in intervals], key=lambda x: x[0])
    out = [xs[0]]
    for s, e in xs[1:]:
        if s <= out[-1][1]:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return out
""",
"two_sum": """
def two_sum(nums, target):
    seen = {}
    for i, n in enumerate(nums):
        if target - n in seen:
            return [seen[target - n], i]
        seen[n] = i
    return []
""",
"is_balanced": """
def is_balanced(s):
    pairs = {')': '(', ']': '[', '}': '{'}
    st = []
    for c in s:
        if c in '([{':
            st.append(c)
        elif c in pairs:
            if not st or st.pop() != pairs[c]:
                return False
    return not st
""",
"run_length": """
def run_length(s):
    out = []
    for c in s:
        if out and out[-1][0] == c:
            out[-1][1] += 1
        else:
            out.append([c, 1])
    return [(c, n) for c, n in out]
""",
"flatten": """
def flatten(xs):
    out = []
    for x in xs:
        if isinstance(x, list):
            out.extend(flatten(x))
        else:
            out.append(x)
    return out
""",
"fizzbuzz_list": """
def fizzbuzz_list(n):
    out = []
    for i in range(1, n + 1):
        if i % 15 == 0: out.append('FizzBuzz')
        elif i % 3 == 0: out.append('Fizz')
        elif i % 5 == 0: out.append('Buzz')
        else: out.append(str(i))
    return out
""",
"is_anagram": """
def is_anagram(a, b):
    return sorted(a) == sorted(b)
""",
"word_count": """
def word_count(s):
    d = {}
    for w in s.split():
        d[w] = d.get(w, 0) + 1
    return d
""",
"binary_search": """
def binary_search(xs, target):
    lo, hi = 0, len(xs) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        if xs[mid] == target: return mid
        if xs[mid] < target: lo = mid + 1
        else: hi = mid - 1
    return -1
""",
"chunk_list": """
def chunk_list(xs, n):
    return [xs[i:i+n] for i in range(0, len(xs), n)]
""",
"gcd_list": """
from math import gcd
from functools import reduce
def gcd_list(xs):
    return reduce(gcd, xs)
""",
"transpose": """
def transpose(m):
    if not m:
        return []
    return [list(r) for r in zip(*m)]
""",
"dedupe_ordered": """
def dedupe_ordered(xs):
    seen, out = set(), []
    for x in xs:
        if x not in seen:
            seen.add(x); out.append(x)
    return out
""",
"roman_to_int": """
def roman_to_int(s):
    v = {'I':1,'V':5,'X':10,'L':50,'C':100,'D':500,'M':1000}
    tot = 0
    for i, c in enumerate(s):
        if i + 1 < len(s) and v[c] < v[s[i+1]]:
            tot -= v[c]
        else:
            tot += v[c]
    return tot
""",
"longest_common_prefix": """
def longest_common_prefix(strs):
    if not strs:
        return ''
    p = strs[0]
    for s in strs[1:]:
        while not s.startswith(p):
            p = p[:-1]
            if not p: return ''
    return p
""",
"rotate_list": """
def rotate_list(xs, k):
    if not xs: return []
    k %= len(xs)
    return xs[-k:] + xs[:-k] if k else list(xs)
""",
"count_vowels": """
def count_vowels(s):
    return sum(1 for c in s if c in 'aeiouAEIOU')
""",
"sum_digits": """
def sum_digits(n):
    return sum(int(c) for c in str(n))
""",
"matrix_diagonal": """
def matrix_diagonal(m):
    return [m[i][i] for i in range(len(m))]
""",
"group_by_parity": """
def group_by_parity(xs):
    return {'even': [x for x in xs if x % 2 == 0], 'odd': [x for x in xs if x % 2 != 0]}
""",

"lru_cache_class": """
from collections import OrderedDict
class LRUCache:
    def __init__(self, capacity):
        self.cap = capacity; self.d = OrderedDict()
    def get(self, key):
        if key not in self.d: return -1
        self.d.move_to_end(key); return self.d[key]
    def put(self, key, value):
        if key in self.d: self.d.move_to_end(key)
        self.d[key] = value
        if len(self.d) > self.cap: self.d.popitem(last=False)
""",
"topo_sort": """
def topo_sort(n, edges):
    adj = {i: [] for i in range(n)}; indeg = {i: 0 for i in range(n)}
    for u, v in edges:
        adj[u].append(v); indeg[v] += 1
    q = [i for i in range(n) if indeg[i] == 0]; out = []
    while q:
        u = q.pop(0); out.append(u)
        for v in adj[u]:
            indeg[v] -= 1
            if indeg[v] == 0: q.append(v)
    return out if len(out) == n else None
""",
"edit_distance": """
def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]
""",
"parse_ini": """
def parse_ini(text):
    out = {}; sec = ''
    for line in text.splitlines():
        t = line.strip()
        if not t or t[0] in ';#': continue
        if t.startswith('[') and t.endswith(']'):
            sec = t[1:-1]; out.setdefault(sec, {})
        elif '=' in t:
            k, v = t.split('=', 1)
            out.setdefault(sec, {})[k.strip()] = v.strip()
    return out
""",
"interval_intersection": """
def interval_intersection(a, b):
    i = j = 0; out = []
    while i < len(a) and j < len(b):
        lo = max(a[i][0], b[j][0]); hi = min(a[i][1], b[j][1])
        if lo <= hi: out.append([lo, hi])
        if a[i][1] < b[j][1]: i += 1
        else: j += 1
    return out
""",
"base62_roundtrip": """
A = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
def to_base62(n):
    if n == 0: return '0'
    s = ''
    while n: n, r = divmod(n, 62); s = A[r] + s
    return s
def from_base62(s):
    n = 0
    for c in s: n = n * 62 + A.index(c)
    return n
""",
"group_anagrams": """
def group_anagrams(words):
    d = {}
    for w in words: d.setdefault(''.join(sorted(w)), []).append(w)
    return sorted([sorted(g) for g in d.values()], key=lambda g: g[0])
""",
"validate_brackets_depth": """
def max_depth(s):
    d = best = 0
    for c in s:
        if c == '(': d += 1; best = max(best, d)
        elif c == ')':
            d -= 1
            if d < 0: return -1
    return best if d == 0 else -1
""",
"merge_sorted_k": """
def merge_k(lists):
    out = []
    for l in lists: out.extend(l)
    return sorted(out)
""",
"spiral_matrix": """
def spiral(m):
    if not m: return []
    m = [list(r) for r in m]; out = []
    while m:
        out += m.pop(0)
        if m and m[0]:
            for r in m: out.append(r.pop())
        if m: out += m.pop()[::-1]
        if m and m[0]:
            for r in m[::-1]: out.append(r.pop(0))
    return out
""",
}


def run(code, tests):
    prog = code + "\n" + tests + "\nprint('OK')\n"
    with tempfile.NamedTemporaryFile("w", suffix=".py", delete=False) as f:
        f.write(prog); path = f.name
    r = subprocess.run([sys.executable, path], capture_output=True, text=True, timeout=15)
    return "OK" in r.stdout, (r.stderr.strip().split("\n")[-1][:100] if r.returncode else "")


def main():
    tasks = json.load(open(sys.argv[1]))
    missing = [t["name"] for t in tasks if t["name"] not in REFERENCE]
    if missing:
        print("NO REFERENCE for:", missing); return 2
    ok_pos = ok_neg = 0
    for t in tasks:
        ref = REFERENCE[t["name"]]
        passed, err = run(ref, t["tests"])
        if passed:
            ok_pos += 1
        else:
            print(f"  POSITIVE FAIL {t['name']}: {err}")
        # negative control: the harness must reject a stub that returns None
        stub = f"def {t['name']}(*a, **k):\n    return None\n"
        rejected, _ = run(stub, t["tests"])
        if not rejected:
            ok_neg += 1
        else:
            print(f"  NEGATIVE LEAK {t['name']}: a None-returning stub PASSED its tests")
    print(f"tasks={len(tasks)} reference-passes={ok_pos}/{len(tasks)} "
          f"stub-rejected={ok_neg}/{len(tasks)}")
    return 0 if (ok_pos == len(tasks) and ok_neg == len(tasks)) else 1


if __name__ == "__main__":
    sys.exit(main())
