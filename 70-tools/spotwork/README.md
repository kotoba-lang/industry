# spotwork — スキマバイト（単発シフト）の governed 突合

単発シフトの求人 1 件に対して、**cohort-first の突合**と**労働法・職業安定法・
個人情報保護の判定**を行い、提案 1 行を出す。pure `.cljc`。モデルは居ない。

- policy: `manifest/spotwork.edn`
- 常駐 bot: `scripts/spotwork-match-{tick,loop}.cljs` / skill `spotwork-match`
- 設計の正本: `90-docs/adr/2608292100-cloud-itonami-spotwork-matching-governed-bots.edn`

```bash
kbb --backend sci 70-tools/spotwork/run-tests.cljk                      # exit 0 / 1 / 2
kbb --backend sci scripts/spotwork-match-tick.cljk                      # 現在地と次の候補
kbb --backend sci scripts/spotwork-match-tick.cljk --emit <offer-id>    # 提案 1 行（決定論）
```

## 何を持っていて、何を持っていないか

| ns | 役割 |
|---|---|
| `spotwork.time` | 壁時計だけの時刻計算。タイムゾーンを扱わない |
| `spotwork.facts` | 法域別の**条文カタログ**。条文に書かれた数値だけを持つ |
| `spotwork.match` | cohort の順位付け（決定論スコアリング） |
| `spotwork.governor` | 独立 governor。`:pass` / `:hold` / `:block` |
| `spotwork.proposal` | 台帳 1 行の組み立てと検証（Result 返し、例外を投げない） |
| `spotwork.fingerprint` | 求人の内容指紋（再審査の要否判定**専用**。改竄検知ではない） |

**持っていないもの**: 個人を並べる口、マッチ／就業の確定、金銭の移動、
有料職業紹介事業の許可の代替。`manifest/spotwork.edn` の `:spotwork/non-goals` が正本。

## Timee との位置の違い（劣化ではない）

Timee は**個人**を即マッチングして即日払いまで通す。ここが出すのは
**cohort の順位**までで、個人へ降ろすのはオペレータの操作、その個人情報は
actor 側へ戻らない。

理由は 1 つ: この workspace の働き手側の正本 `cloud-itonami/talent` は、
識別情報を E2E 暗号のまま持ち、公開読みを **k-匿名 cohort 集計だけ**にしている。
そこに個人を並べる突合を足すと、`talent` が構造で守っているものを、それを消費する
側が壊す。**同じ製品を作っていない。** 何を作っていないかを先に書く。

## 隣接 repo との境界

| repo | そちらの持ち分 |
|---|---|
| `cloud-itonami/cloud-itonami-isic-7810` | 職業紹介（常用）。年俸 × 手数料率で placement fee を検算する常勤前提のモデル |
| `cloud-itonami/cloud-itonami-isic-7820` | 派遣。agency が雇用主のまま時限 assignment を dispatch する形 |
| `cloud-itonami/talent` | 働き手側の正本（ISCO-08、E2E 暗号、k-匿名 cohort） |
| `cloud-itonami/recruit` | 求人側の集約（公開 feed のみ、常用求人） |
| `kotoba-lang/shift` | 打刻・シフト・ロスター（**既に雇っている人**の勤怠） |

単発シフト固有の判定（時間単位の休憩・深夜・当日キャンセル・手数料の負担者）は
どれも持っていなかったので、ここが持つ。

## 設計で効いている 3 つの規約

1. **`:not-measured` を `:pass` に畳まない。** 地域別最低賃金の額は条文に無く
   （毎年度の告示）、オペレータが供給するまで `:wage/below-minimum` は
   `:not-measured`。したがって提案は `:hold` 止まりで、`:pass` にはならない。
   **「額を知らない」が「額に問題が無い」として通ることはこの系では起きない。**
2. **拒否は理由の literal で返す。** `[:error :rule/id …]`。真偽値で返すと
   truthy 判定が「空でない拒否」を成功として通す。可否は `proposal/ok?` だけが答える。
3. **確定系はどの phase でも自動化しない。** `governor/phase-table` と
   `governor/never-auto` の 2 層。表を書き換えても 2 層目が止める。

## 現在地の測り方

数をここに書かない（書けば日付を落として引用される）。測るのは:

```bash
kbb --backend sci 70-tools/spotwork/run-tests.cljk    # 規則の整合・1 対 1・理由の pin・確定系の不自動化
kbb --backend sci scripts/spotwork-match-tick.cljk    # 求人と cohort の件数、台帳の verdict 内訳、床
```

⚠ `80-data/spotwork/` に入っているのは **fixture であって実需要ではない**。
単発シフトを配る公開 feed は存在しないので、実需要は運用者が持ち込む。
fixture の件数を実績として引用しない。
