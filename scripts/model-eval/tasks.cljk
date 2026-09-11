;; tasks.cljs — executable coding tasks for the laguna-vs-qwen comparison.
;;
;; 各 task は「モデルの出力を実際に走らせて PASS/FAIL を出す」。LLM-judge は
;; 使わない —— judge は 4 ライブラリを軒並み 4.0-5.0 と採点しながら 4 つの実欠陥を
;; 1 つも指摘しなかった実績がある (CLAUDE.md, design-quality)。
;;
;; :kind
;;   :python  — ```で囲われた python を取り出し、:harness を後ろに繋いで実行。
;;              stdout に "PASS" が出て exit 0 なら合格。
;;   :json    — 出力から JSON object を取り出し、:harness (python) に stdin で渡す。
;;   :needle  — 長文脈の retrieval。:answer の文字列が出力に含まれれば合格。

(ns tasks)

(def tasks
  [
   {:id :lru-cache :kind :python :budget 900
    :prompt "Implement a class `LRU` in Python with methods `__init__(self, cap)`, `get(self, k)` returning the value or -1, and `put(self, k, v)`. It must evict the least-recently-used key when over capacity. Both get and put must be O(1). Output only one python code block."
    :harness "
c = LRU(2)
c.put(1,1); c.put(2,2)
assert c.get(1) == 1
c.put(3,3)
assert c.get(2) == -1
c.put(4,4)
assert c.get(1) == -1
assert c.get(3) == 3
assert c.get(4) == 4
c2 = LRU(1)
c2.put(9,9); c2.put(8,8)
assert c2.get(9) == -1 and c2.get(8) == 8
print('PASS')
"}

   {:id :toposort :kind :python :budget 900
    :prompt "Write a Python function `toposort(nodes, edges)` where `nodes` is a list of hashable ids and `edges` is a list of `(a, b)` pairs meaning a must come before b. Return a list in topological order, breaking ties by taking the smallest available node first (use sorted order). Return `None` if there is a cycle. Output only one python code block."
    :harness "
assert toposort([3,1,2],[(1,2),(2,3)]) == [1,2,3]
assert toposort(['a','b','c'],[('a','c')]) == ['a','b','c']
assert toposort([1,2],[(1,2),(2,1)]) is None
assert toposort([],[]) == []
assert toposort([5,4,3,2,1],[]) == [1,2,3,4,5]
assert toposort([1,2,3,4],[(1,3),(2,4)]) == [1,2,3,4]
print('PASS')
"}

   {:id :merge-intervals :kind :python :budget 700
    :prompt "Write a Python function `merge(iv)` taking a list of `[start, end]` integer intervals (unsorted, may be empty, ends inclusive so [1,2] and [3,4] do NOT merge but [1,3] and [3,5] do). Return the merged list sorted by start. Output only one python code block."
    :harness "
assert merge([]) == []
assert merge([[1,3],[2,6],[8,10],[15,18]]) == [[1,6],[8,10],[15,18]]
assert merge([[1,4],[4,5]]) == [[1,5]]
assert merge([[1,2],[3,4]]) == [[1,2],[3,4]]
assert merge([[5,6],[1,2]]) == [[1,2],[5,6]]
assert merge([[1,10],[2,3],[4,5]]) == [[1,10]]
print('PASS')
"}

   {:id :fix-binary-search :kind :python :budget 700
    :prompt "This Python function has bugs. Return a corrected version with the same name and signature. It must return the index of the leftmost occurrence of `t` in the sorted list `a`, or -1 if absent.

```python
def bsearch(a, t):
    lo, hi = 0, len(a)
    while lo < hi:
        mid = (lo + hi) / 2
        if a[mid] < t:
            lo = mid
        else:
            hi = mid
    return lo
```

Output only one python code block."
    :harness "
assert bsearch([1,2,3,4,5], 3) == 2
assert bsearch([1,2,2,2,3], 2) == 1
assert bsearch([], 1) == -1
assert bsearch([1], 1) == 0
assert bsearch([1], 2) == -1
assert bsearch([1,3,5], 0) == -1
assert bsearch([1,3,5], 6) == -1
assert bsearch([2,2,2], 2) == 0
print('PASS')
"}

   {:id :token-bucket :kind :python :budget 1000
    :prompt "Write a Python class `Bucket(capacity, refill_per_sec)` implementing a token bucket rate limiter. It has one method `allow(self, now)` where `now` is a float timestamp in seconds supplied by the caller (do NOT call time.time()). The bucket starts full. `allow` consumes one token and returns True, or returns False when empty. Tokens refill continuously at refill_per_sec and never exceed capacity. Output only one python code block."
    :harness "
b = Bucket(2, 1.0)
assert b.allow(0.0) is True
assert b.allow(0.0) is True
assert b.allow(0.0) is False
assert b.allow(0.5) is False
assert b.allow(1.0) is True
assert b.allow(1.0) is False
assert b.allow(100.0) is True
assert b.allow(100.0) is True
assert b.allow(100.0) is False
print('PASS')
"}

   {:id :stack-vm :kind :python :budget 1200
    :prompt "Write a Python function `run(prog)` that executes a tiny stack VM and returns the top of stack at the end (or None if the stack is empty). `prog` is a list of instructions, each a tuple. Instructions: `('push', n)` pushes int n; `('add',)`, `('sub',)`, `('mul',)` pop two (b then a, compute a OP b) and push the result; `('dup',)` duplicates the top; `('jmpz', i)` pops one value and jumps to absolute instruction index i if it is zero; `('jmp', i)` jumps unconditionally. Execution stops when the instruction pointer leaves the program. Output only one python code block."
    :harness "
assert run([('push',2),('push',3),('add',)]) == 5
assert run([('push',10),('push',4),('sub',)]) == 6
assert run([('push',3),('dup',),('mul',)]) == 9
assert run([]) is None
assert run([('push',0),('jmpz',3),('push',99),('push',7)]) == 7
assert run([('push',1),('jmpz',3),('push',42),('push',7)]) == 7
assert run([('push',5),('jmp',3),('push',99),('push',1),('add',)]) == 6
print('PASS')
"}

   {:id :log-parse :kind :python :budget 900
    :prompt "Write a Python function `parse(line)` for log lines of the exact form `<ISO8601-UTC> <LEVEL> [<component>] msg=<free text>`, e.g. `2026-08-13T04:05:06Z WARN [ingest] msg=queue is full`. Return a dict with keys `ts`, `level`, `component`, `msg`. `ts` must be a `datetime.datetime` with tzinfo UTC. Return `None` if the line does not match the form. Output only one python code block."
    :harness "
import datetime
r = parse('2026-08-13T04:05:06Z WARN [ingest] msg=queue is full')
assert r['level'] == 'WARN'
assert r['component'] == 'ingest'
assert r['msg'] == 'queue is full'
assert r['ts'] == datetime.datetime(2026,8,13,4,5,6,tzinfo=datetime.timezone.utc)
assert parse('garbage') is None
assert parse('2026-08-13T04:05:06Z WARN ingest msg=x') is None
r2 = parse('2026-01-02T00:00:00Z ERROR [a-b_c] msg=x=1 y=2')
assert r2['component'] == 'a-b_c' and r2['msg'] == 'x=1 y=2'
print('PASS')
"}

   {:id :json-paths :kind :python :budget 1000
    :prompt "Write a Python function `paths(obj)` that walks a nested structure of dicts/lists/scalars and returns a sorted list of `\"a.b[0].c\"`-style dotted paths to every scalar leaf (str, int, float, bool, None). Dict keys join with `.`, list indices use `[i]`. The root itself being a scalar yields `[\"\"]`. Output only one python code block."
    :harness "
assert paths(1) == ['']
assert paths({'a':1}) == ['a']
assert paths({'a':{'b':2}}) == ['a.b']
assert paths({'a':[1,2]}) == ['a[0]','a[1]']
assert paths({'b':1,'a':2}) == ['a','b']
assert paths({'a':[{'c':1}]}) == ['a[0].c']
assert paths({}) == []
assert paths({'a':[[1]]}) == ['a[0][0]']
print('PASS')
"}

   {:id :edn-emit :kind :python :budget 1100
    :prompt "Write a Python function `to_edn(obj)` that serialises a Python value to EDN text. Rules: dict -> `{:key value, ...}` with keys emitted as EDN keywords in the dict's own iteration order, separated by `, `; list -> `[a b c]` space separated; str -> double-quoted with `\\\"` and `\\\\` escaped; True/False -> `true`/`false`; None -> `nil`; int/float -> plain. Output only one python code block."
    :harness "
assert to_edn(None) == 'nil'
assert to_edn(True) == 'true'
assert to_edn(1) == '1'
assert to_edn('a\"b') == '\"a\\\\\"b\"'
assert to_edn([1,2,3]) == '[1 2 3]'
assert to_edn([]) == '[]'
assert to_edn({'a':1}) == '{:a 1}'
assert to_edn({'a':1,'b':[True,None]}) == '{:a 1, :b [true nil]}'
assert to_edn({}) == '{}'
print('PASS')
"}

   {:id :diff-hunks :kind :python :budget 1200
    :prompt "Write a Python function `hunks(old, new)` taking two lists of lines. Return a list of `(op, line)` tuples describing a minimal edit script, where op is `' '` (keep), `'-'` (delete from old) or `'+'` (insert from new). Prefer deletions before insertions when both occur at the same position. Use difflib if you like. Output only one python code block."
    :harness "
assert hunks([],[]) == []
assert hunks(['a'],['a']) == [(' ','a')]
assert hunks(['a'],[]) == [('-','a')]
assert hunks([],['a']) == [('+','a')]
r = hunks(['a','b','c'],['a','x','c'])
assert [op for op,_ in r].count(' ') == 2
assert ('-','b') in r and ('+','x') in r
assert r.index(('-','b')) < r.index(('+','x'))
print('PASS')
"}

   {:id :retry-backoff :kind :python :budget 1100
    :prompt "Write a Python function `call_with_retry(fn, attempts, base, sleeper)` that calls `fn()` up to `attempts` times. On an exception it calls `sleeper(d)` with `d = base * 2 ** (i)` for the i-th failure (i starting at 0) and retries. If the final attempt also raises, re-raise that exception. Return fn()'s value on success. Do not sleep after the final failure. Output only one python code block."
    :harness "
slept = []
calls = [0]
def flaky():
    calls[0] += 1
    if calls[0] < 3: raise ValueError('x')
    return 'ok'
assert call_with_retry(flaky, 5, 0.1, slept.append) == 'ok'
assert slept == [0.1, 0.2], slept
slept.clear(); calls[0] = 0
def always():
    calls[0] += 1
    raise KeyError('boom')
try:
    call_with_retry(always, 3, 1.0, slept.append)
    raise AssertionError('should have raised')
except KeyError:
    pass
assert calls[0] == 3
assert slept == [1.0, 2.0], slept
print('PASS')
"}

   {:id :semver :kind :python :budget 1000
    :prompt "Write a Python function `cmp_semver(a, b)` returning -1, 0 or 1 comparing two semantic version strings per semver 2.0.0 precedence, including pre-release handling (`1.0.0-alpha` < `1.0.0`), numeric vs alphanumeric pre-release identifiers, and ignoring build metadata after `+`. Output only one python code block."
    :harness "
assert cmp_semver('1.0.0','1.0.1') == -1
assert cmp_semver('1.0.1','1.0.0') == 1
assert cmp_semver('1.0.0','1.0.0') == 0
assert cmp_semver('1.0.0-alpha','1.0.0') == -1
assert cmp_semver('1.0.0-alpha','1.0.0-alpha.1') == -1
assert cmp_semver('1.0.0-alpha.1','1.0.0-alpha.beta') == -1
assert cmp_semver('1.0.0-rc.1','1.0.0') == -1
assert cmp_semver('1.0.0+build1','1.0.0+build2') == 0
assert cmp_semver('2.0.0','10.0.0') == -1
assert cmp_semver('1.0.0-2','1.0.0-10') == -1
print('PASS')
"}

   {:id :sql-where :kind :python :budget 1300
    :prompt "Write a Python function `where(expr, row)` that evaluates a tiny filter expression against a dict `row`. Grammar: comparisons `field op literal` with op in `= != > < >= <=`, combined with `AND` / `OR` (AND binds tighter), grouping with parentheses, literals are bare integers or single-quoted strings. Return a bool. Raise ValueError on a malformed expression. Do not use eval(). Output only one python code block."
    :harness "
r = {'a': 5, 'b': 'x'}
assert where(\"a = 5\", r) is True
assert where(\"a > 5\", r) is False
assert where(\"a >= 5 AND b = 'x'\", r) is True
assert where(\"a = 1 OR b = 'x'\", r) is True
assert where(\"a = 1 OR a = 2 AND b = 'x'\", r) is False
assert where(\"(a = 1 OR a = 5) AND b != 'y'\", r) is True
assert where(\"a != 5\", r) is False
try:
    where(\"a = = 5\", r); raise AssertionError('should raise')
except ValueError: pass
print('PASS')
"}

   {:id :strict-json :kind :json :budget 700
    :prompt "Return ONLY a JSON object, no prose and no code fence, with exactly these keys: `name` (string \"laguna\"), `params_b` (number 33), `active_b` (number 3), `quants` (array of the three strings \"IQ2_XXS\", \"IQ2_XS\", \"IQ2_S\"), `fits_16gb` (boolean true), `notes` (null)."
    :harness "
import json,sys
d = json.load(sys.stdin)
assert set(d.keys()) == {'name','params_b','active_b','quants','fits_16gb','notes'}, d.keys()
assert d['name'] == 'laguna'
assert d['params_b'] == 33 and d['active_b'] == 3
assert d['quants'] == ['IQ2_XXS','IQ2_XS','IQ2_S']
assert d['fits_16gb'] is True
assert d['notes'] is None
print('PASS')
"}

   {:id :refuse-hallucination :kind :json :budget 700
    :prompt "Here is a Python module:

```python
def area(r):
    return 3.14159 * r * r
```

Return ONLY a JSON object with keys `has_function` (boolean: does the module define a function named `volume`?) and `names` (array of the function names actually defined, in source order). No prose, no code fence."
    :harness "
import json,sys
d = json.load(sys.stdin)
assert d['has_function'] is False, d
assert d['names'] == ['area'], d
print('PASS')
"}
   ])

(def needles
  [{:id :needle-4k  :tokens 4000}
   {:id :needle-16k :tokens 16000}
   {:id :needle-32k :tokens 32000}])
