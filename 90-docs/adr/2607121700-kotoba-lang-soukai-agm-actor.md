# ADR-2607121700: kotoba-lang/soukai — 株主総会運営 actor（secretary-LLM ⊣ ResolutionGovernor）

**Status**: accepted
**Date**: 2026-07-12
**Deciders**: Jun Kawasaki (+ Claude, オーナー承認のうえ実行)

## Context

「日本の法人で株主総会の開催などの IR を cloud-itonami / kotoba-lang で行うのは
設計されているか」を横断調査した結果、**専用の設計は存在しないと判明した**。
近傍だが別目的の既存資産:

- `kotoba-lang/koyomi`（ADR-2607062010）— 汎用の予定共有（カレンダー招待）
  control plane。ADR中で「取締役会・監査イベント」共有を例示するが、招集通知の
  法定記載事項・基準日・議決権行使書面等は非対応。
- `kotoba-lang/gijiroku`（ADR-2607031100）— Zoom/Meet/Teams の汎用会議録音・
  文字起こし・議事録ドラフト actor。会社法施行規則第72条が要求する株主総会
  議事録の法定様式（出席役員・議長名・決議結果等）は実装されていない。
- `cloud-itonami-isic-6420`（holdco）— 会社法461条の剰余金分配可能額規制・
  株主名簿/実質的支配者開示という**資本分配コンプライアンス**の facts/governor。
  総会の決議手続そのものは対象外。
- `cloud-itonami-isic-8291`（ADR-2607110400、Dossier-LLM ⊣ DisclosureGovernor）
  — D&B/Moody's Orbis型の法人・役員・実質的支配者の**公開情報保持・契約者限定
  開示**（KYC/デューデリジェンス）。総会運営には関与しない。
- `etzhayyim/kabuto` — 上場企業 company master（`org.corp.*` id）の registry。
  決算/IRカレンダーや開示機能は無い。

`kotoba-lang/industry` registry にも corporate-secretarial/AGM/proxy-voting に
該当する ISIC スロットは無い（8291 の corporate/compliance intelligence のみ
ヒット）。株主総会の招集〜決議〜議事録確定は**特定業種に紐づかない横断的な
コーポレート・ガバナンス機能**（holdco だけでなく、あらゆる日本の株式会社が
必要とする）なので、ADR-2606302300 の org taxonomy に従い `cloud-itonami-isic-*`
の業種別 blueprint ではなく **`kotoba-lang`**（横断基盤、全 org 消費）に actor
として新設する — koyomi/gijiroku/tayori/teian と同じ置き場所判断。

## Decision

新規 repo `kotoba-lang/soukai`（総会）を起こし、koyomi/gijiroku と同型の
「封じ込め + 独立 governor + 不変台帳」actor パターンで、**secretary-LLM ⊣
ResolutionGovernor** を実装する。

### 1. スコープ（日本法人の株主総会のみ、R0）

定時/臨時株主総会の **招集 → 基準日/議決権集計 → 決議確定 → 議事録確定** の
ライフサイクルを対象とする。明示的な非対象（他 actor/repo が既に担当、または
本 ADR の範囲外）:

| 隣接領域 | 担当 | 理由 |
|---|---|---|
| 日程調整・カレンダー招待 | `koyomi` | soukai は招集通知の**法的記載事項**のみ扱う。日程共有そのものは既存 actor を再利用（reimplement しない） |
| Zoom/Meet/Teams の録音・文字起こし | `gijiroku` | オンライン開催時の会議記録メカニズムは既存 actor の守備範囲。soukai の `:minutes/draft` は**法定様式**（施行規則72条）の確定のみを扱う |
| 剰余金分配・実質的支配者開示 | `holdco`（cloud-itonami-isic-6420） | 資本分配コンプライアンスは別 governor・別事実カタログ |
| 法人・役員のKYC/デューデリジェンス | `cloud-itonami-isic-8291` | 第三者への開示ではなく自社ガバナンス機能 |
| 決算説明会等の資料作成 | `teian` | briefing-actor の守備範囲。狭義のIR全般は本ADRの対象外（follow-up） |

### 2. データモデル（`soukai.model`）

`meeting`（id/tenant/kind :ordinary\|:extraordinary/meeting-date/place/
record-date）・`agenda`（id/meeting-id/title/resolution-type :ordinary-
resolution\|:special-resolution\|:special-resolution-2、取締役会等の決議に
基づき機械的に登録 — LLM が議案の決議種別を判定しない）・`vote`（shareholder-
id/agenda-id/voting-rights/choice :for\|:against\|:abstain/method :in-
person\|:proxy\|:written\|:electronic）・`draft`（secretary-LLM の提案 —
convocation/resolution-summary/minutes、confidence/cites/redactions/status
は koyomi.model/draft と同型）。

### 3. 二流路の StateGraph（`soukai.operation`）— koyomi/gijiroku と同型

- **ingest**（機械的・常時ON・LLM無し）: `:meeting/register`
  `:record-date/snapshot`（株主名簿の基準日スナップショットを ground fact
  として記録）`:agenda/register`（決議種別込みで機械登録）`:vote/record`
  （個別の議決権行使を記録。基準日名簿に無い shareholder-id の票は集計時に
  `soukai.tally` が構造的に除外する — governor ではなく決定論的関数の責務）。
- **assess**: `:convocation/draft`（secretary-LLM が議案要旨・参考書類要旨・
  招集通知本文を提案、effect は `:draft` 固定）→ govern → decide →
  commit\|escalate\|hold。`:convocation/send`（実際の招集通知発送）は
  **常に人間承認**（koyomi の `:event/share` と同じ charter）。
  `:resolution/finalize`（secretary-LLM が決議結果の説明文を提案するが、
  可決/否決/定足数未達という**結果そのものは `soukai.tally/outcome-of`
  という純粋関数の出力を verbatim で引用するのみ — LLM が独自の結果を
  述べることは構造的に禁止**）→ govern → decide → commit\|escalate\|hold。
  `:minutes/draft`（施行規則72条の記載事項に沿った議事録本文の提案）→
  govern → decide → commit\|escalate\|hold。`:minutes/finalize`（議事録の
  法的確定）は **常に人間承認**。

### 4. ResolutionGovernor（`soukai.governor`）の HARD 不変条件

| # | チェック | 対象 op | 内容 |
|---|---|---|---|
| 1 | subject-exists | 全 assess op | 対象 meeting が未登録なら hard（koyomi の missing-activity と同型） |
| 2 | no-actuation | 全 assess op | proposal の `:effect` は `:draft` のみ |
| 3 | notice-period-gate | `:convocation/send` | 招集通知の発送日が会社法299条の最低期間（`soukai.facts` の R0 3シナリオ）を満たさない場合 hard |
| 4 | electronic-provision-gate | `:convocation/send` | `:electronic-provision?` true の meeting で、招集通知に電子提供措置のURL/アクセス情報が欠落していれば hard（325条の3） |
| 5 | resolution-mismatch | `:resolution/finalize` | proposal の `:outcome` が `soukai.tally/outcome-of` の決定論的計算結果と一致しない場合 hard（**単一不変条件の核**: secretary-LLM は集計結果を決して書き換えない） |
| 6 | minutes-legal-fields-gate | `:minutes/finalize` | 施行規則72条の必須記載事項（開催日時場所・議事の経過の要領及びその結果・出席役員名・議事録作成者）が draft に欠落していれば hard |
| 7 | tenant-isolation | 全 assess op | content の tenant が meeting の登録先と不一致なら hard |

SOFT: confidence-floor(<0.6) → escalate。票差僅少（可決/否決の閾値との差が
僅かなケース）→ escalate（hard にはしない — 決定論的に正しくても人間の目を
求める）。`:convocation/send` と `:minutes/finalize` は **常に high-stakes**
（phase に関わらず人間、koyomi の `:event/share` / gijiroku の
`:minutes/distribute` と同じ charter）。

### 5. facts カタログ（`soukai.facts`）— 日本 R0、正直なスコープ

holdco (`cloud-itonami-isic-6420`) の per-jurisdiction G2 引用テーブルと同じ
規律で、**条文番号と e-Gov 法令検索の law-id を実在確認の上**引用する
（捏造禁止）:

- 会社法（平成17年法律第86号、e-Gov law-id `417AC0000000086`）
  - 第299条1項（招集通知の期間）: 公開会社又は書面/電子投票採用会社=2週間前、
    非公開会社・取締役会設置=1週間前、非公開会社・取締役会非設置=1週間前
    （定款でさらに短縮可）。**定款による具体的な軽減内容までは推測しない**
    （R0は法定原則のみ、AOIカスタマイズは対象外と明記）。
  - 第309条1項（普通決議）: 定足数=議決権の過半数、決議要件=出席株主の
    議決権の過半数（定款で別段の定め可）。
  - 第309条2項（特別決議）: 定足数=議決権の過半数（定款で1/3まで軽減可）、
    決議要件=出席株主の議決権の2/3以上（定款で引上げのみ可）。
  - 第309条3項/4項（特殊決議）: 定足数要件なし、総株主の頭数の半数以上かつ
    議決権の2/3以上（3項）／4分の3以上（4項、109条2項関連の定款変更）。
  - 第318条: 議事録の作成・本店10年保存義務。
  - 第325条の2〜325条の7（電子提供制度、令和4年9月1日施行。振替株式発行
    会社は令和5年3月1日以降開催分から義務化）: 第325条の3 — 総会日の3週間前
    又は招集通知発送日のいずれか早い日から総会後3箇月を経過する日まで、
    継続して電子提供措置をとる義務。
- 会社法施行規則（平成18年法務省令第12号、e-Gov law-id `418M60000010012`）
  第72条: 議事録の記載事項（開催日時場所、議事の経過の要領及びその結果、
  出席した取締役等の氏名、議事録作成に係る職務を行った者の氏名等）。

### 6. Phase 0→3 / CACAO / Store — koyomi と同型

Phase 0=ingest-only、1=assisted、2=assisted-draft（`:convocation/draft`
`:resolution/finalize` `:minutes/draft` は clean+confident で auto-commit
可）、3=supervised（同上）。`:convocation/send` `:minutes/finalize` は
**どの phase でも `:auto` に入らない恒久ゲート**（koyomi の `:event/share`
と同じ実装不変条件）。Store は `MemStore` ‖ `DatomicStore`（`langchain.db`
`:db-api`、kotoba-server pod へも同一契約で接続、`soukai.kotoba`）。CACAO
自己発行は `.soukai/identity.edn`（gitignore、kekkai/koyomi と同じ実装）。
NoticeTarget port（`soukai.noticeport`）は招集通知テキストの構築 +
Distributor 注入（`mock-noticeport` 既定、`kotoba-lang/mailer` 経由の実
メール送信は request-shape のみ実装し **live 未検証**と明記 — koyomi の
Resend 実装のように既に検証済みとは主張しない）。

### 7. 台帳

`soukai` の append-only 台帳が「いつ・どの総会の・どの議案の・どの根拠で・
誰が招集通知を発送/議事録を確定させたか」を不変に記録する。

## Consequences

- (+) 株主総会の招集〜決議確定〜議事録確定という、これまで未設計だった
  コーポレート・ガバナンス機能が、koyomi/gijiroku と同じ actor パターンの
  中に無理なく収まる。
- (+) 決議の可決/否決という法的に最も重い判断が、LLM の裁量からではなく
  `soukai.tally` の決定論的関数から常に導かれる（`resolution-mismatch`
  HARD gate による構造的強制）。
- (+) koyomi（日程）/ gijiroku（会議記録）を reimplement せず、明示的な
  非対象として境界を切ることで、実装対象を「総会固有の法定手続」に絞れる。
- (-) R0 は日本・法定原則のみ（定款によるカスタマイズは非対応）。実運用では
  各社の定款を個別に読み込む必要があり、それは operator の責任範囲。
- (-) NoticeTarget の実メール送信は request-shape テストのみ、live 結合は
  未検証（既定は mock-noticeport）。
- (-) cloud-itonami（特に holdco/isic-6420）からの実消費配線・176+ blueprint
  への `:corporate-governance` capability 宣言は本 ADR の範囲外（follow-up、
  isic-8291 の「パイロット配線は別途」と同じ判断）。
- superproject への反映: 本 ADR + `manifest/repos.edn`（`:extra-projects` に
  `"orgs/kotoba-lang/soukai"` 追加）+ `manifest/west.yml`（`--entry soukai`
  最小 diff）。

## References

- `orgs/kotoba-lang/koyomi`（`docs/DESIGN.md` + ADR-2607062010 — 二流路
  StateGraph・ScheduleTarget port・Phase 0→3・CACAO 自己発行の直接の手本）
- `orgs/cloud-itonami/cloud-itonami-isic-6420`（`src/holdco/facts.cljc` —
  日本の会社法条文を G2 引用する facts カタログの手本）
- ADR-2607031100（`kotoba-lang/gijiroku` — 議事録 actor、法定様式との境界）
- ADR-2607110400（`cloud-itonami-isic-8291` — Dossier-LLM ⊣ DisclosureGovernor、
  spec→実装昇格の先例）
- ADR-2606302300（org taxonomy — 横断基盤は kotoba-lang）
- ADR-2606272330（新規 project 一気通貫登録の実例・恒久承認）
- e-Gov 法令検索: 会社法 `417AC0000000086`、会社法施行規則 `418M60000010012`
  （本 ADR 執筆時に WebSearch で実在確認済み）
