# hermes-orgbrain-publish — kyber main → kyber.kotoba.cloud 機械同期 bot

owner direction 2026-09-16 (orgbrain 三層 ADR-2609142000 の公開ループ最終化)。

orgbrain-maint (LLM bot, 04:40) が kyber main に運営プロセスを 1日1本 増やす。
この **no-LLM script cron** がその変化だけを kyber.kotoba.cloud の /org-data
に届ける。提案内容は一切生成しない (ADR 憲章ゲート互換: 内容ゲートは kyber 側
の Clojure テストスイート、本 bot は「すでに main に merge 済みのもの」だけを
公開する)。

## 流れ (`orgbrain_publish.sh`)

1. `git ls-remote kyber main` の SHA == 公開済 `index.json .source.rev` なら
   **無音 exit** (stdout 空 = cron 通知なし)
2. drift → `build-orgbrain-data.mjs` (プロセス自動発見) で catalog 再生成
3. golden 再生成: `gh api tarball/$SHA` → `golden_emit.cljk` を kbb --backend sci
   で走らせ Clojure 真値を取得 → `orgbrain-golden.mjs` が test の GOLDEN を書換
4. gate: `node test/orgbrain-catalog.mjs` (provenance sha256 / authority audit /
   golden / XML DI / vendored==published)
5. diff が `assets/orgbrain-catalog/` + `test/orgbrain-catalog.mjs` 以外に
   触れていたら abort (scope guard)
6. PR 作成 → 自己 merge (ops-bots の機械 PR discipline; gate=上のテスト) →
   `npm run deploy` (フルチェーン) → live readback:
   `kyber.kotoba.cloud/org-data/index.json` の rev とプロセス数が一致したら
   1行レポート (stdout → cron deliver)

## 配備

- 正本 = この dir。稼働コピー = `~/.hermes/scripts/orgbrain-publish.sh`
  (`.hermes.md` 規約: copy to run)
- cron: hermes cron job (default profile) **no_agent=true**, script
  `orgbrain-publish.sh`, `35 5,17 * * *` JST — maint bot の拡張 (04:40) と
  kotoba-merger の午后レビューの後に追従
- 作業場: `~/.gftd/worktrees/orgbrain-publish/app`
  (app-kotoba-cloud worktree、node_modules は leaf へ symlink)
- 失敗時: 最終行 `orgbrain-publish FAILED: ...` が必ず stdout に出る
  (無音の失敗なし)。cron 履歴 + deliver で確認

## 検査

```bash
ORGBRAIN_SELFTEST=1 orgbrain-publish.sh   # 合成3本目プロセスで発見〜gate〜revert
```

## 既知の設計判断

- rev stamp のみの PR (kyber 側の非 orgbrain commit でも) がoccasionally出る:
  provenance を正直に保つため許容 (byte-deterministic ビルドで generatedAt のみ差)
- schema が変わって golden が動いた日も gate は緑 (golden が Clojure から
  再生成されるため)。Clojure 自体が壊れた時だけ红灯 → 人間へ
- kyber pin (superproject west) は本 bot は触らない (advance は人間/merger の領域)
