# ADR-2607120900: 一話読切マンガ3作品（halfgram/zankyo/yamainu）に専用リポジトリを新設

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki（+ Claude、オーナー承認のうえ実行）

## Context

`manga.gftd.ai`（`ai-gftd-mangaka` プラットフォーム、gftdcojp）で公開中の4作品
（halfgram / zankyo / yamainu / ghosthacker）のうち、`ghosthacker` だけが
`orgs/com-junkawasaki/ghosthacker` という専用IPリポジトリを持ち、他の3作品
（0.5グラムの宇宙 / 残響のカルテット / 山犬と硯）はリポジトリを持たず、
`ai-gftd-mangaka` のD1行 + R2画像と、`orgs/gftdcojp/app-aozora` の
`aozora.appview.manga-actors` 配信用レジストリにしか実体が無い状態だった。

genko（原稿: ページ/ストローク/パネルの実データ）自体はB2/R2 +
`ai-gftd-mangaka` のD1が正であり、gitリポジトリでは管理していない
（`kami-mangaka-genko-clj` は共有エンジンのドメインロジックのみ、実データは
オブジェクトストレージ）。オーナーとの対話で各作品の完結までのarc/episode構成
（halfgram: 全3幕8話、zankyo: 全3幕9話、yamainu: 全4幕12話）を策定し、
halfgramは第2話「融点」・第3話「邪道」のネームまで作成済み。この企画・脚本
資産をgit管理下に置きたいというオーナー要望（「作品ごとにrepoを作って」）に対し、
配置orgは `ghosthacker` 先例（com-junkawasaki = 著者個人IP、gftdcojp =
配信プラットフォーム）に揃えるかを確認し、com-junkawasakiで承認を得た。

## Decision

**halfgram / zankyo / yamainu それぞれに `orgs/com-junkawasaki/<slug>` の
専用リポジトリを新設し、企画・脚本（ネーム）・世界観バイブルのSSoTとする。**
配信用の `aozora.appview.manga-actors` レジストリとgenko実体（B2/R2+D1）は
そのまま正本として残し、本リポジトリ群はそれらを置き換えない。

1. **構成**: 各リポジトリに `README.md`（作品概要・エピソード表・arc構成）、
   `work.edn`（`aozora.appview.manga-actors` とフィールドを揃えたSSoT:
   slug/title/description/genre/characters/arcs/episodes/links）、
   `episodes/NN-<slug>.md`（ネーム）を置く。公開済み第1話は本文を持たず
   スタブ（配信URLへのリンクのみ）とし、genko実体の複製を避ける。
2. **配置org**: `com-junkawasaki`（private, org既定）。`ghosthacker` の
   先例（プラットフォームはgftdcojp、作品IP自体は著者個人org）に揃える。
3. **manifest登録**: `manifest/repos.edn` の `:manifest.repos/extra-projects`
   に3パスを追加し、`nbb scripts/gen-west-manifest.cljs --entry
   halfgram,zankyo,yamainu` で最小diff生成（サーバ側pin検証: 3件とも新規entry、
   default branchから到達可能を確認）。

## 副産物: `scripts/nbb_compat.cljs` の `file-seq` 修正

登録作業中、インストール済み nbb（1.3.204）では `array-seq` が解決できず
`gen-west-manifest.cljs` がロード時に落ちることが判明した
（`chore(tooling)` でのbabashka→nbb移行、67f6594e5e5、で持ち込まれた既存バグ、
本タスクとは無関係）。`(array-seq (.listFiles f))` を `(seq (.listFiles f))`
に変更する1行修正で解決（`.listFiles` は既にJS配列を返すため意味は不変）。
manifest生成が全ユーザーで動かない状態だったため同じdiffに含めて修正した。

## Consequences

- (+) 3作品の企画・脚本資産がgit管理下に入り、`ghosthacker` と同様の
  取り扱いになった。
- (+) `gen-west-manifest.cljs` の `file-seq` バグが直り、`--entry` 経路が
  正常に動作するようになった（他の新規登録作業もこれで動く）。
- (−) `work.edn` と `aozora.appview.manga-actors` の二重管理が生まれる
  （既存のD1/レジストリ二重管理パターンと同型、ADR-2607070400と同じ trade-off）。
  同期は手動。
- (−) zankyo/yamainuの第2話以降はarc構成のみでネーム未着手（README/work.eddnに
  明記）。

## References

- ADR-2607070400 — app-aozora manga work actor profiles（配信用レジストリ、
  halfgram/zankyo/yamainu/ghosthackerのフィールド定義）
- ADR-2607071100 — mangaka retirement wave3（配信プラットフォームの現状）
- `orgs/com-junkawasaki/ghosthacker` — 専用IPリポジトリの先例
- `orgs/com-junkawasaki/{halfgram,zankyo,yamainu}`（本ADRで新設）
