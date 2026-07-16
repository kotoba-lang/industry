---
id: adr-2606271930-denwaban-voice-reception-auto-booking-actor
title: "ADR-2606271930: denwaban(電話番) — 着信応対 + 音声対話 + 自動予約を束ねる voice-receptionist actor。telephony=twilio-compat / TTS=elevenlabs-compat / 予約=yotei を合成し、欠落していた STT 一次プリミティブだけを新規に足す"
status: proposed
doc_type: adr
topic: actor-design
authoritative: true
last_verified: 2026-06-27
authoritative_for:
  - 着信(電話)応対・音声対話・自動予約を1つの first-class actor(denwaban)に束ねる設計判断
  - 既存 compat/named actor(twilio-compat, elevenlabs-compat, yotei, kotoba-net webrtc)の合成境界
  - 唯一欠落している STT/ASR プリミティブを clean-room compat actor として新設する決定
  - voice 関連成果物の3-org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)
related:
  - orgs/kotoba-lang/koe                        # 再利用 voice-session kernel(共通ライブラリ) ← denwaban の依存
  - orgs/etzhayyim/root/20-actors/denwaban             # 本 ADR の actor(公益インスタンス)
  - orgs/etzhayyim/root/20-actors/whisper-compat        # 新設 STT/ASR compat(欠落プリミティブ)
  - orgs/etzhayyim/root/20-actors/twilio-compat        # Programmable Voice 相当(着信/発信/SIP) ← telephony 面
  - orgs/etzhayyim/root/20-actors/elevenlabs-compat     # TTS(音声合成) ← 発話面
  - orgs/etzhayyim/root/20-actors/yotei                 # Calendly 反転・no-double-book 予約 ← 予約面(委譲先)
  - orgs/etzhayyim/root/20-actors/yadori                # reservation state-machine の参照パターン(ドメイン特化)
  - orgs/etzhayyim/root/20-actors/toritsugi             # 行政手続コンシェルジュ(LINE 窓口) ← 非音声の姉妹 actor
  - orgs/etzhayyim/root/20-actors/agora-compat          # RTC voice 経路(代替トランスポート)
  - 90-docs/adr/2606271800-kotoba-net-webrtc-turn-transport.md  # browser/edge soft-phone の Live トランスポート
  - 90-docs/adr/2606072200-yotei                        # yotei kotoba-native 化(委譲先の現行アーキ)
supersedes: []
superseded_by: []
---

# ADR-2606271930: denwaban(電話番) — voice-reception + auto-booking actor

**Status**: proposed（design — 実装は actor 側 PR に分離。R0 = offline + intent only）
**Date**: 2026-06-27
**Deciders**: Jun Kawasaki

## Context

「今の actor に **電話対応・自動予約・音声対応** は設計されているか」を棚卸しした結果、
**3機能の素材はすべて別々の actor に既存**だが、それらを「電話で受け → 音声で対話し →
予約を取る」ユースケースに束ねる **オーケストレータ actor は未設計** だった。現状の分布:

| 機能 | 最も近い既存 actor | 実態 | 統合への不足 |
|---|---|---|---|
| 電話対応 | `twilio-compat` | clean-room Programmable Voice(着信/発信/SIP/SMS)。Datomic backed | 着信を「会話」に変える対話ループが無い |
| 音声対応(発話) | `elevenlabs-compat` | TTS(音声合成)。OpenAPI 互換 | 入力側 STT/ASR が**完全に欠落** |
| 音声対応(聞取) | — | **存在しない**(whisper/deepgram/assemblyai 等の compat actor 無し) | 一次プリミティブから新設が必要 |
| 自動予約 | `yotei` | Calendly 反転。append-only booking + **no-double-book 不変条件** + member 署名確定 + no booker-harvest | MCP tool として既に他 agent から呼べる(=委譲先に最適) |
| (予約 SM 参考) | `yadori` | reservation state-machine(`init→screened→quoted→intent_built→authorized`) | ドメイン取得に強結合。パターンのみ流用 |

つまり欠けているのは **(1) STT/ASR の一次プリミティブ** と **(2) telephony↔STT↔dialog↔
TTS↔予約 を1本のセッションに配線する actor** の2点だけ。本 ADR はこの2点を確定する。

> 命名の妥当性: `toritsugi`(取次) は既に存在するが **行政手続コンシェルジュ(LINE 公式
> アカウント役)** で電話とは別物。voice/telephony の窓口名は空いている。`電話番` は
> 「電話を預かり取り次ぐ者」で本 actor の役割に直接対応する。

## Decision

### 1. 新規 first-class actor `denwaban`(電話番) を起こす

- **DID**: `did:web:denwaban.etzhayyim.com` · **Tier**: B · **Status**: R0(scaffold) ·
  **glyph**: 電話番
- **役割**: 着信(または soft-phone)を受け、音声で来訪者と対話し、**予約は自分で持たず
  `yotei` に委譲**して取る voice receptionist。`toritsugi`(非音声・行政窓口)の
  **電話/音声版の姉妹**。
- **Charter-clean**: yotei/toritsugi と同様、本 actor は「窓口で伴走する側」であって
  buyer-of-record でも record harvester でもない(§Gates)。

### 2. セッション・パイプライン(合成境界)

```
着信 PSTN/SIP/WebRTC ─► twilio-compat (Programmable Voice / SIP ingress)
        │                       ▲ outbound 折返しも同経路(G7-gated)
        ▼
   音声ストリーム ──► [STT/ASR] ──► テキスト
                       (新設 compat #3)
        ▼
   対話オーケストレーション (kotoba runtime; LLM)
        │   ├─ 予約意図の抽出/スロット確認 ─► yotei.BookSlot / SetAvailability (MCP tool calling)
        │   └─ no-double-book 不変条件・member 署名確定は yotei 側が保証
        ▼
   応答テキスト ──► elevenlabs-compat (TTS) ──► 音声 ──► 発話(同 telephony 経路)
```

- **telephony 面 = `twilio-compat`** を一次に、`vonage-compat`/`bandwidth-compat` を
  代替経路(charter 上は等価な clean-room)。SIP/PSTN は G7 outward-gate の内側。
- **聞取 = 新設 STT compat**(下記#3)。**発話 = `elevenlabs-compat`**(既存 TTS)。
- **予約 = `yotei` に MCP tool calling で委譲**。denwaban は予約状態を保持しない
  (single source of truth は yotei の append-only booking)。yotei は既に
  「他 agent の convo から MCP で `CreateEvent`/`SetAvailability`/`BookSlot` を呼べる」
  設計(ADR-2606072200)なので、配線は新 protocol 不要。
- **browser/edge soft-phone** は `kotoba-net` の WebRTC トランスポート
  (ADR-2606271800)に載せ、PSTN を介さない Web 着信を Live 面の first-class peer として
  扱える(電話番号の無い窓口)。

### 3. 唯一欠落の一次プリミティブ: STT/ASR clean-room compat actor を新設

既存 compat 群と同形(Datomic backed · Py Kotodama WASM · OpenAPI 互換 · clean-room)で
**`whisper-compat`**(または `deepgram-compat`)を新規追加する。これが3機能統合の
**唯一の新規実装コスト**。それ以外(電話・発話・予約)は既存 actor の合成で足りる。

- I/F: 音声チャンク(stream) → 部分/確定トランスクリプト。barge-in(発話中の割込み)を
  扱えるよう streaming 前提。
- elevenlabs-compat と対になり、denwaban の音声 I/O 両端を埋める。

### 4. 成果物の org 配置(共通=com-junkawasaki / 公益=etzhayyim / 事業=gftdcojp)

voice 関連の成果物は責務で3 org に振り分ける。**再利用カーネルは com-junkawasaki、
OSS かつ公益の actor は etzhayyim、事業/プライベート配備は必要時に gftdcojp**。

| 成果物 | 種別 | org | 状態 |
|---|---|---|---|
| `koe-clj`(声) | 共通ライブラリ(port 群 + dialog loop。telephony/STT/TTS/booking を host 注入) | **com-junkawasaki** | 新設(本 PR で scaffold) |
| `denwaban`(電話番) | 公益 voice-receptionist actor(koe-clj の etzhayyim インスタンス) | **etzhayyim** | 新設(本 PR で scaffold) |
| `whisper-compat` | OSS clean-room STT compat(欠落プリミティブ) | **etzhayyim** | 新設(本 PR で scaffold) |
| 事業配備(有償受付等) | business/private deployment | **gftdcojp** | **必要時のみ**(R0 では作らない) |

- `koe-clj` は **新規 west project**。実体リポ化は `manifest/repos.edn` 登録 + remote 作成が
  必要で、これは別 PR(superproject の manifest 更新)で行う。本 PR では skeleton + テストのみ。
- denwaban は booking を持たず、port を介して既存 actor を注入するだけ(重複実装ゼロ)。

### 5. 段階(R0→R3、yadori/toritsugi/yotei の gate 規約に整合)

- **R0(本 ADR)**: scaffold + パイプライン設計 + cell stub。`.solve()` は raise。
  実音声・実着信なし(fixtures のみ)。STT compat も socket-free core から。
- **R1**: STT compat の streaming core + denwaban 対話ループを fixtures で green。
  yotei への BookSlot 委譲を mock MCP で結線。
- **R2**: WebRTC soft-phone(ブラウザ着信)で end-to-end(PSTN 無し)。録音は既定 off。
- **R3(gated)**: 実 PSTN 着信(twilio-compat live)+ 本人同意ベースの予約確定。
  Council Lv6+ + operator gate。

## Gates(immutable R0→R3)

- **G1 consent-first / no-secret-recording**: 通話録音・文字起こし保持は **明示同意が前提**。
  既定は非保持(transient)。録音する場合も冒頭告知。
- **G2 member-signed booking**: 予約確定は `yotei` の **member 署名確定**を必ず経由。
  denwaban は予約の buyer/record-of-truth にならない(yotei 委譲)。
- **G3 no-booker-harvest / PII**: 発信者データの収集・転売・プロファイリング禁止
  (yotei の no-booker-harvest を継承)。電話番号は必要時のみ・最小・暗号化。
- **G4 Murakumo-only**: 実行は Murakumo の中。外部 SaaS への素通しをしない。
- **G5 no-server-key**: telephony/registrar 等の資格情報は member-held。WebRTC の
  TURN は `kotoba-turn` の短命 credential(ADR-2606271800)で server 鍵を出さない。
- **G6 no-robocall / no-spam**: 無差別アウトバウンド発信・自動勧誘・なりすまし発信を禁止
  (発信は同意済みコールバック等に限定)。
- **G7 outward-gated**: 実着信/実発信/実 RDAP 的な外向き I/O は R0=offline + intent only。
  live は Council Lv6+ + operator gate + 明示 env フラグ。
- **G8 sourcing-honesty**: transcript/booking は representative fixtures + 実体の出所を明示。

## Non-goals

- **N1** 汎用 IVR/コールセンター製品(席課金・大量同時呼の SaaS)にしない。
- **N2** 無差別アウトバウンド(テレマーケ/オートダイヤラ)を提供しない(G6)。
- **N3** 通話の常時録音・声紋データセット化・感情解析の収益化をしない(G1/G3)。
- **N4** 予約ロジックを denwaban 内に再実装しない(yotei が single source of truth)。
- **N5** detection-evasion / なりすまし(発信者番号偽装等)の用途に供さない。

## Consequences

- **+** 3機能統合の新規実装は **STT compat 1本だけ**。電話=twilio-compat、発話=
  elevenlabs-compat、予約=yotei は既存合成で、重複実装・重複 PII 面を作らない。
- **+** `toritsugi`(行政・LINE 窓口)と `denwaban`(電話・音声窓口)が窓口役の
  text/voice 両輪になり、charter(伴走・member 署名・no-harvest)を共有。
- **+** `kotoba-net` WebRTC(ADR-2606271800)が「電話番号の無い Web 着信」を与え、
  PSTN 依存を必須にしない段階導入ができる。
- **−** STT は精度/レイテンシ/barge-in が UX を左右し、clean-room 実装の難所。
  R1 で streaming core の品質ゲートを設ける。
- **−** 実 PSTN 着信は telephony 番号調達・通信品質・各国規制(録音同意法)に触れるため
  R3 gate を厚くする(G1/G7)。
- **リスク**: 「電話で予約を取る」は member 署名確定(G2)と consent(G1)を厳守しないと
  charter 違反になりやすい。yotei 委譲を必須経路にすることで構造的に担保する。

## Status / Next

R0 = 本 ADR(設計確定) + denwaban scaffold(`did:web`, cells stub, fixtures)。
次作業の最小単位:
1. `whisper-compat`(STT) を既存 compat 同形で scaffold(socket-free core)。
2. `denwaban` actor scaffold + 対話ループ stub + yotei への mock MCP BookSlot 結線。
3. R2 で kotoba-net WebRTC soft-phone と end-to-end(PSTN 無し)を green 化。
