# ADR-2607141700: murakumo.cloud に kototama 実行の organism heartbeat を追加 — Cloudflare は routing/web のみ

**Status**: accepted (v1 landed + deployed)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki
**Scope**: `orgs/gftdcojp/cloud-murakumo`（新規 `organism/` + `deploy/organism/`）。
`orgs/kotoba-lang/kototama` / `orgs/kotoba-lang/kotoba` はコード変更なし（CLI を
消費するだけ）。

## Context

オーナー指示（2026-07-14）:「murakumo.cloud で kototama で organism が生きている、
動くように」。続けて明確化:「kototama は cloudflare じゃなくて cloud-murakumo に
実態があるようにして。cloudflare は基本的に routing, また web system のみ」。

事前調査（このセッション内、Explore agent + 直接コード読み）で以下を確認した:

- 「murakumo.cloud で kototama 実行の organism」という組み合わせは **既存 ADR・
  コードのどこにも存在しなかった**（greenfield）。隣接する前例はあるが別レイヤー:
  - etzhayyim organism の推論基盤は `kotoba-lang/murakumo`（fleet/auction の CLI・
    library）を「Murakumo-only」で使う（ADR-2605215000 系）— これは
    **`gftdcojp/cloud-murakumo`（Cloudflare 上の GPU マーケットプレイス製品、
    murakumo.cloud）とは別リポジトリ**。
  - kototama には元々 `organism.cljc`/`heartbeat.cljc`/`cell.cljc` があったが、
    後に汎用 organism ランタイムとして `kotoba-lang/kotodama` ファミリーへ
    切り出し済み（ADR-2607050900 が kototama/kotodama の命名混同を未解決と明記）。
    `kotodama`/`kotodama-host` はどちらも **ライブラリ専用（`-main` なし、常駐
    プロセスを持たない）** — 実際に guest を実行する常駐ホストは kototama 自身
    （`fleet-run`/`fleet-daemon`）。
  - kototama には Cloudflare/edge へのデプロイ経路が無い（JVM/Chicory と
    ブラウザ native WASM のみ）。Workers は JVM/Chicory を実行できない。
- `gftdcojp/cloud-murakumo` は実在の本番 Cloudflare Worker（`murakumo-cloud`、
  custom domain `murakumo.cloud`/`www.murakumo.cloud`、Stripe Checkout あり）。
  `organism`/`kototama`/`kotodama` への言及ゼロ、完全に greenfield。
- cloud-murakumo の実 dispatch（`src/cloud_murakumo/{dispatch,scheduler,gateway,
  worker,executor}.cljc`）は JVM/Clojure の resident worker プロセス
  （`clojure -M:worker -m cloud-murakumo.worker`）で、GPU ノード fleet 上で
  動く。Docker/k8s は無く、bare process（`:proc`）または mesh 内 HTTP
  （`:http`）で dispatch。`:gpu/count 0` の非 GPU function（`cron-report` 等）
  の前例あり。
- Worker（`site_worker.cljs`）は **純粋な pathname routing** —
  `/api/openai`・`/api/anthropic`・`/x402/*` を `js/fetch` でバックエンドへ
  proxy、それ以外は `env.ASSETS.fetch`（静的アセット/SPA）に fall through。
  `dispatch.cljc`/`worker.cljc` は import しない（JVM専用）。

## Decision

1. **kototama の実体は cloud-murakumo の実コンピュート（この Mac — 既に
   `ai.gftd.murakumo` daemon が resident している fleet ノード）で実行する。
   Cloudflare Worker はコード変更ゼロ** — 既存の `env.ASSETS.fetch` 経路が
   そのまま `public/organism/pulse.json` を静的配信する（etzhayyim organism の
   「ローカルで書く → publish で静的 JSON を deploy」パターンと同型。
   `/api/*` proxy と同じ「Worker は routing/web のみ」規律を保つ）。
2. **organism/pulse.kotoba** — 最小 `.kotoba`（算術+再帰のみ、host capability
   不使用、`policy.edn`/`lock.edn` は vacuous）。`kotoba wasm emit` でコンパイル
   した `pulse.wasm` を checked in（kototama 自身の test fixture 慣例と同型）。
   毎 beat が「本当に kototama で WASM 実行された」ことを証明する目的で
   `fib 12 = 144` を計算する（organism の状態そのものはこの guest に持たせない
   — kototama CLI に guest への外部入力を渡す経路が無いため。状態は host 側の
   journal で持つ）。
3. **実行は `kototama fleet-run`**（`fleet-daemon` ではない — 実測したところ
   `fleet-daemon` は「新規 tick」ではなく「既存 lease の crash recovery」
   専用で、何もしていない状態では ok=0/fail=0 のまま何もしない）。
   `fleet-run` は CLI に relocate flag が無い `tmp/kototama-fleet` へ
   checkpoint を書くため、`organism/.fleet-run/` に throwaway `deps.edn`
   （`:local/root` で kototama checkout を指す）を生成し、そこを cwd にして
   実行 — kototama の共有 checkout には一切書き込まない。
   また `fleet-run` は毎回同一の tenant/guest id をハードコードしており、
   JVM プロセスごとに異なる `fleet/node-id` を持つため、2 回目以降の呼び出しが
   1 回目のディスク lease に対して epoch fence で拒否される
   （`fence claim refused at bootstrap`）— この organism の本当の beat 履歴は
   `organism/.fleet-run/journal.edn`（自前の append-only journal）が正なので、
   毎 beat 前に kototama 自身の checkpoint store を wipe する。
4. **launchd（この Mac 上）** — `organism.heartbeat`（60秒毎、`fleet-run` 1回
   + journal fold）と `organism.publish`（300秒毎、release build +
   `wrangler deploy`）。etzhayyim organism / kaname と同じ `@REPO@` template
   置換の install パターン。
5. **publish は commit 済み（HEAD）内容のみを deploy する** — `npm run prep`/
   `release` は working tree の `resources/murakumo.edn` を無条件 copy するが、
   このリポジトリは並行セッションが共有する checkout。無人 LaunchAgent が
   他セッションの未コミット WIP を本番へ deploy してはならない、という理由で
   `git show HEAD:resources/murakumo.edn` から複製するよう修正した（下記
   Consequences 参照 — 実際に一度事故った）。

## Verification（実測、2026-07-14）

- `kotoba wasm emit` → `pulse.wasm`（108 bytes, exports `fib`/`main`/`vitality`）
  → `kototama run` → `:result 144, :fuel-used 183` をローカルで確認。
- `fleet-run` を3回連続実行し `organism/.fleet-run/journal.edn` に3 beat 蓄積、
  `public/organism/pulse.json` 正しく再生成されることを確認。
- `wrangler deploy` で murakumo.cloud へ実 deploy、
  `https://murakumo.cloud/organism/pulse.json` が 200 + 正しい JSON を返すことを
  `curl` で確認（cache-busting header 込み）。
- 共有 checkout（`orgs/gftdcojp/cloud-murakumo`）に着地後、launchd 経由で
  RunAtLoad が実際に beat を打ち、journal が伸びることを確認。

## Consequences

- (+) 「kototama の実体は cloud-murakumo 側、Cloudflare は routing/web のみ」が
  文字通り真 — Worker のコードは一切変更していない。
- (+) etzhayyim organism と同じ「ローカル heartbeat → 静的 publish」パターンを
  再利用したため、Worker 側に新しい信頼境界・新しい攻撃面を増やしていない。
- (+) `.kotoba`/kototama の実行チェーンが実運用（本番ドメイン配信）で実証された
  最初の例——ADR-2607101200 のベクタリー固定を実地で検証した形。
- (−) **事故**: 実装直後、共有 checkout から初回 `run-publish.sh` を走らせた際、
  `npm run prep` が別セッションの未コミット WIP（`resources/murakumo.edn` の
  airshed-intelligence function 追加）を巻き込んで本番へ deploy してしまった。
  即座に検知し、commit 済み版を再 deploy して復旧、`run-publish.sh` を
  「HEAD からのみ複製する」よう修正（fix commit, 同日ランド）。以後の
  無人 publish はこの事故を再現しない。教訓: 共有 checkout 上で無人ジョブを
  新設するときは、他人の dirty working tree を巻き込む経路がないか個別に
  検証する必要がある——ビルドスクリプトの間接的な副作用（`npm run prep` の
  `cp`）は見落としやすい。
- (−) `kototama fleet-run` の tenant/guest id が固定でノード単位の epoch
  fence と噛み合わないため、cross-beat の checkpoint 継続性は捨てている
  （毎 beat 独立 lease）。真の cross-beat lease 継続（`fleet-resume`）は
  将来の拡張余地。
- 未整理: `kototama`/`kotodama` の命名混同（ADR-2607050900）はこの ADR の
  スコープ外——今回は kotodama を使わず kototama 単体で足りたため、混同の
  解消自体は先送り。

## References

- ADR-2607101200（kotoba=language / kototama=runtime のベクタリー固定）
- ADR-2607050900（kototama/kotodama 命名混同、未解決）
- ADR-2605215000 系（etzhayyim organism の Murakumo-only 推論基盤——別レイヤー）
- `orgs/etzhayyim/root/70-tools/scripts/organism-heartbeat/`（heartbeat/publish
  の launchd パターンの前例）
- `orgs/gftdcojp/cloud-murakumo/organism/README.md`（実装詳細）
