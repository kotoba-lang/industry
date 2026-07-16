# ADR-2607141620: kyoninka — ITAD 許認可取得手続きの as-code 化

**Status**: accepted, implemented (closing 2026-07-14 — errand 拡張 d857836、確認屋 M0/M1 で運転中)
**Date**: 2026-07-14
**Deciders**: Jun Kawasaki

## Context

itad.gftd.ai（ADR-2607141446）の persona visual test 第1周（ADR-2607141550、
run `persona-visual-20260714-0700`）で **trust 2.80 が最弱軸**となり、その主因は
LP フッターの「産業廃棄物収集運搬業許可: 準備中 / 古物商許可: 準備中」だった。
オーナー確認: **両許認可とも未取得**。取得は数ヶ月単位の行政手続きであり、
「どこまで進んだか・次に何をするか・何がブロックしているか」を
散文のメモでなく**コード（data + 純関数 + append-only 台帳）**として管理する。

## Decision — 3 層分担（ADR-2607141550 と同型）

**mechanism = `kotoba-lang/kyoninka`（新規 repo、public）**: 日本の許認可手続きを
**procedure-as-data**（Datomic 互換 EDN）+ 純関数で表すライブラリ。初期収録は
ITAD に必要な 2 手続き:

1. **産業廃棄物収集運搬業許可**（廃棄物処理法 14 条 1 項、都道府県知事許可。
   東京都テンプレート: 環境局、積替え保管なし、手数料 81,000 円、標準処理期間
   約 60 日、有効期間 5 年）— 要件: JW センター講習会修了 / 経理的基礎 /
   欠格要件非該当 / 事業計画（PC 廃棄で扱う品目: 金属くず・廃プラスチック類・
   ガラスくず等）。
2. **古物商許可**（古物営業法 3 条、公安委員会。窓口は営業所所在地の管轄警察署
   — 丸の内所在地なら丸の内警察署。手数料 19,000 円、標準処理期間約 40 日）—
   主たる区分: 事務機器類、営業所ごとの管理者選任、URL 届出。

ライブラリの規約:
- **金額・期間・様式は「一般に公表されている標準値」として `:verify` フラグ付き**
  （`{:status :unverified :how "申請前に窓口/公式サイトで最新値を確認"}`）で持つ。
  行政の手数料・様式は改定されるため、**申請直前の実値確認を手続きの第 1 ステップ
  としてコード自体に組み込む**（捏造ゼロ原則の行政手続き版）。
- **法的論点も data として持つ**（`:legal-questions`）。特に ITAD の宅配回収は
  「廃棄物該当性」（買取リユース = 古物商の範囲 / 廃棄物 = 廃掃法の範囲、
  下取り・専ら物の例外、貨物運送事業者による運送委託の扱い）で**収集運搬許可の
  要否自体が事業スキーム設計に依存**する — これは行政書士・弁護士確認事項として
  明示的に保持し、勝手に結論を出さない。
- 進行は状態機械（`:not-started → :preparing → :ready-to-submit → :submitted →
  :under-review → :granted | :rejected`）。**提出・官庁接触・支払いを伴う遷移は
  `:requires-human true`** — ライブラリは next-action を提案するだけで実行しない。

**operation = `cloud-itonami`**: `cloud_itonami/license.cljc` が kyoninka を
`:local/root` で取り込み、Gftd Japan株式会社の 2 件を case として実体化。
append-only 台帳（`resources/licenses/itad-license-ledger.edn`）にイベントを積み、
next-action を propose→govern の人間 gate 付き提案として出す。許可取得後は
`ai-gftd-itad/resources/itad.edn` の `:licenses` に実番号を入れて LP を再生成する
（trust 軸の再計測 = persona panel 再実行で効果を測る）。

**catalog/consumer = `ai-gftd-itad`**: LP の「準備中」表記の解消がこのループの
完了条件（DoD）。

## First actions（コードが提案する最初の next-action、すべて human gate）

1. 事業スキーマの法的整理（行政書士相談）: 宅配回収 ITAD で収集運搬許可が
   必要なスキームか、買取（古物）+ 提携許可業者で足りるスキームか。
2. JW センター講習会（収集・運搬課程 新規）の受講予約 — 受講者 = 代表者または役員。
3. 古物商: 役員全員の住民票（本籍記載）・身分証明書（本籍地発行）・略歴書の収集開始。
4. 手数料・様式・標準処理期間の窓口確認（東京都環境局 / 丸の内警察署）。

## Consequences

- (+) 「準備中」の中身が queryable になり、LP の trust 改善が計測可能なループに乗る。
- (+) kyoninka は汎用 — 今後の許認可（労働者派遣、宅建、酒販等）も同じ schema で追加できる。
- (−) 収録値は標準値であり、最新の手数料・様式は申請前確認が必須（コードがそれを強制する）。
- (−) 法的論点（廃棄物該当性）は未解決のまま保持 — 行政書士確認までスキーム断定しない。

## Follow-ups

1. 行政書士相談の結果を `:legal-questions` の resolution として台帳に記録。
2. cloud-itonami routine への組み込み（進行停滞アラート、期限管理）。
3. 許可取得時: itad.edn `:licenses` 実番号化 → LP 再生成 → persona panel 再実行。
