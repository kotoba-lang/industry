# ADR-2607041245: kami-app-sip を kotoba-lang から etzhayyim へ org transfer（`com-etzhayyim-sip`）

**Status**: closed
**Date**: 2026-07-04
**Deciders**: Jun Kawasaki
**Scope**: `orgs/kotoba-lang/kami-app-sip` → `orgs/etzhayyim/com-etzhayyim-sip`, `orgs/kotoba-lang/kami-engine/kami-app-sip-clj`

## Context

オーナーから「kotoba-lang/mangaka と itonami-cloud の mangaka actor の関係は
整理されているか」という質問があり(ADR-2607022800 follow-up-2026-07-04 参照)、
調査の副産物として `kami-app-sip`(Spirit in Physics — Ghost Hacker 世界観の
ブラウザ向け healing game。AIコンパニオンに寄り添う、修正せず「ただ居る」体験)
が2箇所に分裂しているのを発見した:

1. **`kotoba-lang/kami-app-sip`** — ADR-2607010930 で `kami-engine` monorepo
   から split。cluster 43(mangaka `*-clj` domain crates)の一員として、他の
   substrate crate と同じ機械的な抽出パターンで作られた。
2. **`kotoba-lang/kami-engine/kami-app-sip-clj`** — split 元の subtree コピーが
   削除されずそのまま残存。

両者を全36ファイル diff した結果、**実質的な差分はゼロ**(標準化先が -clj
rename 後の git/sha 座標・story-bible repo rename 追従・軽微な import 整理を
反映しているだけで、逆方向の差分は一件もない)。つまり技術的には単純な重複
残骸の掃除で足りるが、それとは別に**そもそもの置き場所が taxonomy と噛み合って
いない**という、より根本的な issue が見えた。

`kami-app-sip` は4-org taxonomy(ADR-2606302300)が定義する

> kotoba-lang = pure-CLJC language substrate, consumed by every other org

には当てはまらない。`kami-mangaka-render`/`page`/`kami-engine-sdk-clj` という
substrate を**消費する側**の、完結したリーフ製品(ゲーム)だからだ。kotoba-lang
に居たのは「kami-engine のリストア作業からたまたま split された」という機械的な
経緯に過ぎず、責任分界としての選択ではなかった。これが2つのコピーが放置され
分岐する土壌になっていた(実際の作業は「取り回しの良い」monorepo 内コピーで
続けられ、"正式な" 標準化先 repo は置き去りにされていた)。

一方、同じ Ghost Hacker/Spirit-in-Physics 世界観の manga publishing actor
`etzhayyim/com-etzhayyim-tsumugu`(ADR-2607011500)は、既に

> etzhayyim = agent-centric, public-interest organism actors

の枠で正しく組織されている。`kami-app-sip` はドメインも `sip.etzhayyim.com`
で、体験の性質(AIコンパニオンが時間をかけて awaken していく、命令ではなく
寄り添う agent 的体験)も tsumugu と同じ枠に収まる。

## Decision

`kotoba-lang/kami-app-sip` を **GitHub repo transfer(履歴保持)で
`etzhayyim/com-etzhayyim-sip` へ移管**し、`com-etzhayyim-tsumugu` と命名規則
を揃える。`kotoba-lang/kami-engine` 内の孤立コピー `kami-app-sip-clj` は
削除する(上記の通り実質差分ゼロを確認済み)。

## Rationale

- **責任分界を taxonomy に合わせる**: kotoba-lang は substrate(`kami-mangaka-*`,
  `kami-engine-sdk-clj` 等)の提供に専念し、それを消費する完結製品は持たない。
  etzhayyim は同一世界観のactor群(tsumugu, sip)を束ねる。com-junkawasaki は
  story-bible(`org-spirit-in-physics-comics`)という data のみを持つ ——
  taxonomy の3層(substrate / actor / data)がこのユニバースで綺麗に対応する。
- **重複の再発防止**: 「機械的 split 先」と「実際に手を入れる場所」が一致しない
  限り、この種の分岐は何度でも起きる。正しい org に置き直すことで、次に誰かが
  手を入れる場所が1箇所に定まる。
- **gftdcojp は関与しない**: `gftdcojp/ai-gftd-mangaka`(無関係な汎用マンガSaaS)
  とは完全に別のユニバース・別のactorであることも、この整理で明確になる。

## Execution (2026-07-04, 同session内で実施)

1. `gh api repos/kotoba-lang/kami-app-sip/transfer -f new_owner=etzhayyim -f new_name=com-etzhayyim-sip`
   — 履歴保持のまま org transfer + rename。旧 URL は GitHub 側の redirect が
   一定期間効く。
2. ローカル checkout: 旧 `orgs/kotoba-lang/kami-app-sip` を削除し、新パス
   `orgs/etzhayyim/com-etzhayyim-sip` へ fresh shallow clone。
3. `kotoba-lang/kami-engine` の `kami-app-sip-clj` subtree を削除
   (worktree 経由で commit、server-side merge。全36ファイルを標準化先と diff
   し独自差分ゼロを確認済み)。
4. `manifest/repos.edn`: `:extra-projects` の cluster 43 リストから
   `orgs/kotoba-lang/kami-app-sip` を除去し、`orgs/etzhayyim/com-etzhayyim-sip`
   を tsumugu の隣に登録。`:path-overrides` に旧→新パスのリダイレクトを追加。
5. `manifest/west.yml`: `name`/`remote`/`path`/`groups` を新 org・新パスへ更新
   (GitHub API single-entry commit。並行セッションが同ファイルを触っていた
   ため、ローカル working tree 経由ではなく直接 API 経由で反映)。

## Related

- ADR-2606302300: 4-org taxonomy の定義(kotoba-lang / etzhayyim / gftdcojp /
  com-junkawasaki)。
- ADR-2607010930: `kami-app-sip-clj` を含む mangaka cluster 43 の kami-engine
  からの split。
- ADR-2607011500: `com-etzhayyim-tsumugu`(同一世界観の manga publishing actor)。
- ADR-2607022800 follow-up-2026-07-04: この issue を発見した調査(mangaka
  cluster 43 の実消費者、mangaka actor と itonami の関係)。


## Closed (2026-07-04, same session)

Final verification after all steps landed:

- `etzhayyim/com-etzhayyim-sip` live, `main` at `ef8d56cd3389bf5ba08a07c7d8b9d403dd405d04`; old
  URL `kotoba-lang/kami-app-sip` correctly redirects (GitHub API confirms — a
  request to the old path returns the new repo's data).
- `kotoba-lang/kami-engine` no longer contains `kami-app-sip-clj` (`main` at
  `abfeb2c1588d8f16e995b8910b8206b0bc2fc8c0`); local leftover `.cpcache`/`public`
  build artifacts (untracked, gitignored) cleaned from the shared checkout.
- `manifest/repos.edn`: zero remaining `kami-app-sip` references under
  `kotoba-lang`; `orgs/etzhayyim/com-etzhayyim-sip` registered next to
  `com-etzhayyim-tsumugu`; `:path-overrides` redirect in place.
- `manifest/west.yml`: `kami-app-sip` entry count is zero; `com-etzhayyim-sip`
  registered under the `etzhayyim` remote at the correct alphabetical position;
  `kami-engine`'s pin advanced to the removal commit.
- Org-wide grep confirms no `deps.edn` anywhere in the fleet references
  `kotoba-lang/kami-app-sip` as a git coordinate — nothing else needed updating.

No open follow-ups from this ADR remain. Closing.
