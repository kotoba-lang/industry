# ADR-0015: プロセス・ハンドオフ状態機械 — 能力境界と人間/エージェント移行の EDN 制御

- Status: Accepted (2026-06-11)
- 関連: ADR-0010 (life graph), ADR-0013 (engi メール解約フロー), システム安全ルール (Prohibited / Explicit-permission)

## 課題

Facebook エクスポート・iPhone バックアップ・メール解約のように、**人間とエージェントが
交互に手番を持つ多段プロセス**が増えた。「どのステップを Claude が自動でき、どのステップが
本人の認証/物理操作を要するか」が私の暗黙の判断に埋もれており、状態の引き継ぎ
(『ログインした』→次が解除される) も口頭依存だった。これを宣言的に制御したい。

加えて、**安全境界 (パスワード入力・WebAuthn・CAPTCHA は機械が代行不可) 自体を、
私の reasoning ではなく監査可能な EDN ファクトにする**ことで、warehouse を読む
どのエージェントも同じ境界を尊重できるようにする。

## 決定: capability(能力ポリシ) + process/step(状態機械)

### capability — エージェント境界の機械可読ポリシ
各「能力」に `:capability/agent` = `:yes | :no | :ask` を宣言する。これがシステム
安全ルールの EDN ミラー:
- `:no` = Prohibited (本人のみ; ユーザー許可でも解除されない): `:auth.password` `:auth.webauthn` `:captcha` `:device.physical`
- `:ask` = Explicit-permission (要明示確認): `:download` `:form.submit` `:send.message`
- `:yes` = Regular (自動可): `:browser.nav` `:browser.click` `:auth.session` `:device.extract` `:ingest`

### process / step — ハンドオフ状態機械
process は順序付き step の列。各 step は:
- `:step/actor` = `:jun | :claude | :system` (誰の手番か)
- `:step/capability` → capability (どの能力で。`:no` 能力の step は必ず actor=:jun)
- `:step/status` = `:blocked-on-human | :pending | :ready | :done | :skipped`
- `:step/needs` → 前提 step (満たされると :pending→:ready に昇格)

### 制御フロー
1. `process/next-human` クエリ = いま本人待ちの step (= 私が「これをやって」と頼む対象)。
2. 本人が完了 → その step を `:done` にする (loader が facts/processes.edn を更新 or
   将来は manimani/living が自動)。
3. `:done` で `:needs` が満たされた下流 step が `:ready` に → `process/claude-ready`
   クエリに現れる = 私が自動実行できる step。
4. `:ask` 能力の step は実行直前に本人確認 (download の filename/size 提示等)。

これにより「ログインした」の一言が `fb/1-login :done` への状態遷移になり、
`fb/2..6` が機械的に私へ解放される。口頭の引き継ぎが状態機械になる。

## 帰結

- 良: 安全境界が監査可能なファクトになる (私の判断ミスがあっても EDN が真実源)。
  人間/AI の分担が事前に見え、ハンドオフが状態遷移として制御される。engi のメール解約・
  account-recovery 等も同じ型で表現できる。
- 悪: プロセス定義の保守。status の手動更新 (将来 manimani/living で自動化)。

## ファイル
- `personal/facts/processes.edn` — capability ポリシ + process/step 定義 (SSoT)
- `personal/bin/datomic/queries/process.edn` — next-human / claude-ready / agent-prohibited
