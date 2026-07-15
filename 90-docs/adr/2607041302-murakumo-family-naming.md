# ADR-2607041302: murakumo ファミリーの命名整理 — `gftdcojp/cloud-murakumo-fleet` を `gftdcojp/local-murakumo` へ

**Status**: proposed
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/murakumo`, `orgs/gftdcojp/cloud-murakumo`, `orgs/gftdcojp/cloud-murakumo-fleet`

## Context

`murakumo` を名乗るリポジトリが3つ存在し、関係が暗黙のままだった:

1. **`kotoba-lang/murakumo`** — kotoba WASM lattice の制御面そのもの。nbb/clj 製の
   **OSS CLI**（`nbb murakumo nodes/status/provision/mesh/deploy/reconcile/fleet/
   cloud/overlay/infer`）で、standalone にローカル store 上で動く。Mac-mini fleet
   の provisioning・mesh 形成・leaderless auction を担う唯一の実行ツール。
2. **`gftdcojp/cloud-murakumo`**（通称 Sora） — ADR-2606272300 で決定した
   **GPU serverless 製品**（Modal 等価）。kotoba-lang/murakumo の auction に
   GPU 在庫を bid 対象として乗せた**製品面**。CLI 実体は持たず、`resources/
   murakumo.edn` を SSoT に `clj -M:plan/schedule/serve/deploy/doctor` を提供する。
3. **`gftdcojp/cloud-murakumo-fleet`**（旧 `com-junkawasaki/cloud-murakumo`、
   2026-07-02 に gftdcojp へ transfer+rename） — kotoba-lang/murakumo の**外部
   公開エッジ API**（Cloudflare Worker + kotobase.net）。CLI 実体は持たず、
   murakumo CLI が生成する fleet 状態（nodes/events/infer plan）をクラウド
   ストレージ越しに読み書きするための薄いプロキシ。GitHub 上の rename 理由は
   「transfer 先の `gftdcojp` に既に `cloud-murakumo`（Sora）が存在し衝突した
   ため `-fleet` を付与」という**衝突回避の副産物**であり、意味を表す名前では
   ない。リポジトリ内 README は今も `# cloud-murakumo` を名乗ったまま
   (`manifest/repos.edn:57` のコメント参照)。

CLI の所在を軸に整理すると: **kotoba-lang/murakumo だけが実行ツール（共通lib）**
であり、gftdcojp 側の2リポジトリはどちらもそれを消費するクラウド側の層で、
役割が異なる:

- `cloud-murakumo`（Sora）: **クラウド GPU を借りる**商用製品（課金・承認gate・
  auction への GPU bid）
- `cloud-murakumo-fleet`: **自分の手元(on-prem Mac-mini fleet)を束ねる** murakumo
  CLI に、クラウド接続オプション（kotobase.net 経由の状態同期・外部公開API）を
  足すための付属レイヤー。GPU レンタルも課金もしない。

`-fleet` という技術用語のsuffixは、この「Sora=借りる／こちら=自分の手元を束ねる」
という対比を利用者に伝えない。かつ両方が `cloud-murakumo` を含む名前を持つため、
リポジトリ一覧だけを見て役割を取り違えやすい。

## Decision

- **`kotoba-lang/murakumo`** を murakumo ファミリーの**共通lib（かつ唯一のCLI本体）**
  として正式に位置づける。変更なし（既にコード上の依存関係としてそうなっている
  — `cloud-murakumo` README の「配置は `murakumo` の leaderless auction に乗る」
  という記述が根拠）。
- **`gftdcojp/cloud-murakumo`**（Sora）は現状の名前・役割を維持する。「クラウドで
  GPU を借りる製品」という原義に一致しているため変更不要。
- **`gftdcojp/cloud-murakumo-fleet`** を **`gftdcojp/local-murakumo`** へ改名する。
  「衝突回避のための `-fleet` サフィックス」を、対比が伝わる名前（Sora=cloud /
  これ=local な自分のfleetを束ねる面）に置き換える。

## Rationale

- 衝突は歴史的経緯（2026-07-02 の org transfer 時に名前が偶然ぶつかっただけ）で
  あり、現在の名前はどちらの役割も正しく説明していない。改名の機会に意味の
  通る名前へ揃える。
- 「local」という語は、この repo が **CLI 自体は持たず、kotoba-lang/murakumo と
  いう local-first な OSS CLI のクラウド接続オプションを提供するだけ**という
  実態（README: `murakumo (CLI, OSS) ──local store──▶ fleet on your terminal`）
  と、Sora（正真正銘のクラウド GPU レンタル）との対比を両方表せる。
- CLI 本体（kotoba-lang/murakumo）の名前は変えない。共通libの名前を固定した
  まま、消費側2つの命名だけを整理するので、west の `:path-overrides` 機構
  （`orgs/gftdcojp/cloud-murakumo-fleet` → `orgs/gftdcojp/local-murakumo`）
  一発で完結する。

## Consequences（未実施 — フォローアップ）

本ADRは命名方針の決定のみ。実際の改名操作（GitHubリポジトリ名変更・west
manifest反映）は破壊的/共有状態に影響する操作のため、別途オーナー確認の上で
実施する:

1. `gh repo rename cloud-murakumo-fleet local-murakumo --repo gftdcojp/cloud-murakumo-fleet`
2. `manifest/repos.edn` の該当 project 名 / `:path-overrides` に
   `"orgs/gftdcojp/cloud-murakumo-fleet" "orgs/gftdcojp/local-murakumo"` を追加
   （west.yml は手書き禁止。`nbb scripts/gen-west-manifest.cljs --entry local-murakumo`
   で当該 entry のみ最小diffで再生成し `--check` で確認）
3. リポジトリ内 README のタイトル（今も `# cloud-murakumo` のまま）と本文中の
   `cloud-murakumo (CF Worker, cljs)` 等の自称箇所を `local-murakumo` に更新
4. `manifest/repos.edn:57` の transfer 由来コメントを、rename 完了後の状態に
   更新
5. 依存関係を持つ他ドキュメント（ADR-2607022000 murakumo-exo-distributed-
   inference 等、`cloud-murakumo-fleet` への参照がないか確認）の grep 一巡

## Related

- ADR-2606272300 (`cloud-murakumo — GPU cloud`, closed): Sora 製品の決定・設計。
- ADR-2606302300 (`org-taxonomy-4-orgs`): `murakumo` の kotoba-lang 移行、および
  `com-junkawasaki` 保有記述の古さをこのADRの Amendment で補正。
- `manifest/repos.edn:57`: 2026-07-02 の transfer+rename コメント（本ADRの前提事実）。
