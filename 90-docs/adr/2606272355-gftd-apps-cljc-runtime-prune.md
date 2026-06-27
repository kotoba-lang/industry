# ADR-2606272355: gftd 60-apps の Python 実装を CLJ/CLJC runtime contract へ寄せて prune する

**Status**: closed  
**Date**: 2026-06-27  
**Closed**: 2026-06-27  
**Scope**: `orgs/gftdcojp/ai-gftd-apps-gftdcojp/60-apps`

## Context

`ai-gftd-apps-gftdcojp/60-apps` には、LangGraph Python pod、local Python
LaunchAgent、Mac renderer、MLX/weight PoC、ComfyUI helper、publish/backfill helper が
混在していた。

一方で、gftdcojp の app runtime は kotoba / langgraph-clj / CLJ server / CLJC shared
contract へ寄せている。Python 実装が残ると次の問題が残る。

- runtime surface が pod ごとに分裂する
- appview / dispatcher / local runner の contract が言語ごとにずれる
- secrets、Keychain、PDS、B2、D1 などの副作用境界が helper script に散る
- test/audit が `pyproject.toml`、`requirements.txt`、ad hoc shell wrapper に引き戻される

## Decision

`60-apps` 配下の Python 実装は CLJ/CLJC の deterministic plan/result contract へ移行し、
Python runtime/source files、`pyproject.toml`、`requirements*.txt`、Python cache を prune する。

この repo では `orgs/gftdcojp/ai-gftd-apps-gftdcojp/` 自体が `.gitignore` 対象であるため、
この ADR が tracked SSoT になる。実装作業は workspace 内の ignored app tree で完了している。

## Migration Coverage

完了済みの主な移行単位:

- `lg_*` LangGraph pod runtime: animeka / mangaka / dougaka / pornhub / gameka / kaisya /
  syosetsuka / hume / hakken / jp-ashiba / kakure / yukkuri / manimani / nemuri / shinshi
- `weight-kototama`: expert/router/snapshot/training spec を `clj/src/kototama/*.cljc` へ移行
- `weight-oka`: Oka LM config/model/kernel/pipeline spec を `clj/src/oka_lm/*.cljc` へ移行
- `murakumo/mlx-distributed`: MLX distributed PoC を `murakumo.mlx.*` CLJC plan へ移行
- `yukkuri/renderer-mac`: Starlette renderer を `yukkuri.renderer` CLJC render-plan へ移行
- `hume/scripts`: artifact persistence helper を `hume.artifacts` CLJC plan へ移行
- `manimani/agent`: Python LaunchAgent / action executor を `manimani.agent` CLJC contract へ移行
- `shinshi/scripts/*.py`: gad render, co-scientist, B2/D1 upload, aozora dual-write,
  publish/backfill helper を `shinshi.ops` CLJC plan へ移行

## Runtime Boundary

CLJ/CLJC runtime は原則 plan-only とする。

- D1 / PDS / B2 / kotoba / RW への live write は appview、dispatcher、operator runner に閉じる
- T0 credentials や Keychain secret value は runtime に載せない
- T1 plaintext は persist/log/checkpoint しない
- external media generation は `external_io false` の deterministic plan として表現する
- deploy、publish、merge、billing などの副作用は approval gate / local runner の whitelist を通す

`manimani.agent` は local runner contract として whitelist、approval state、source fan-in、
sealed datom plan、action report plan を保持する。実行器そのものはこの contract に従う。

`shinshi.ops` は gad/Modal render、co-scientist candidate generation、B2/D1 upload、aozora
dual-write、publish/backfill を deterministic dry-run plan として返す。

## Verification

移行後の代表検証:

```sh
cd ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-weight-kototama/clj && clojure -M:test
cd ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-weight-oka/clj && clojure -M:test
cd ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-murakumo/mlx-distributed/clj && clojure -M:test
cd ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-yukkuri/renderer-mac/clj && clojure -M:test
cd ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-hume/clj && clojure -M:test
cd ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-manimani/clj && clojure -M:test
cd ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-shinshi/clj && clojure -M:test
```

Final audit:

```sh
find ai-gftd-apps-gftdcojp/60-apps \( -name '*.py' -o -name 'pyproject.toml' -o -name 'requirements*.txt' \) -type f
find ai-gftd-apps-gftdcojp/60-apps \( -name '__pycache__' -o -name '*.pyc' -o -name '.pytest_cache' -o -name '.cpcache' \)
```

Both final audit commands returned no files.

## Consequences

- `60-apps` の implementation contract は CLJ/CLJC に統一される。
- Python helper は historical reference ではなく、削除済み runtime として扱う。
- ignored app tree のため、future PR では tracked ADR / docs / infra pin と、必要に応じて明示的な
  generated artifact だけを commit する。
- Python を再導入する場合は、この ADR を reopen し、なぜ CLJ/CLJC plan contract では足りないかを
  明記する。
