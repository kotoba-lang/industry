---
id: adr-2606290740-aiueos-computer-surface-isolated-computer-use
title: "ADR-2606290740: computer-use を aiueos の能力隔離 surface として実施する"
status: active
doc_type: adr
topic: aiueos-computer-surface
authoritative: true
last_verified: 2026-06-29
authoritative_for:
  - computer-use（画面キャプチャ + pointer/keyboard 操作）を host の実 HID から隔離する正準アーキテクチャ
  - host の env/key/secrets を ambient 継承させない原則（明示・scoped・監査つき capability のみ）
  - wasm component と コンテナ/microVM の役割分担（component=wasm、コンテナ=surface backing/TCB）
related:
  - com-junkawasaki/aiueos/90-docs/adr/0007-virtual-computer-surface.md
  - com-junkawasaki/aiueos/90-docs/adr/0005-multi-surface-providers.md
  - com-junkawasaki/aiueos/90-docs/adr/0004-code-as-data-admit.md
  - "https://github.com/com-junkawasaki/aiueos/pull/5"
supersedes: []
superseded_by: []
---

# ADR-2606290740: computer-use を aiueos の能力隔離 surface として実施する

**Status**: accepted
**Date**: 2026-06-29
**Deciders**: Jun Kawasaki

## Context

ブラウザ/UX の動作検証で computer-use（画面を撮り、pointer/keyboard を操作する
エージェント）を使うと、host 結合のドライバ（macOS の `CGEvent`/Accessibility、
`xdotool` の `:0` ディスプレイ）が **オペレータの実デスクトップを乗っ取る** — 作業中は
マウス/キーボードを奪われ、誤操作が実画面に着弾する。一方 computer-use の「任意 GUI
アプリを操作できる自由度」は手放したくない。

「VM のウィンドウに host 側 computer-use を向ける」では解決しない（host カーソルが
その窓に入って動く＝ host HID を使う）。真の隔離は、エージェントを **仮想ディスプレイ +
合成入力**に対して動かし、host デバイスをそこに配線しないこと。

aiueos はこれを解く機構を既に持つ: surface（ADR-0005、能力の実装＝provider を surface が
決める／offer しない能力は loud denial）、admit（ADR-0004、提出コードを `:ai-generated`
に trust floor 固定し自己昇格を不可能化）、deny-by-default gate（ADR-0002）、append-only
監査（ADR-0001）。足りないのは「provider が仮想コンピュータである surface」だけだった。

## Decision

computer-use を **aiueos の `computer` surface family** として実施する。実装と設計の正準は
aiueos リポの **ADR-0007** + **PR #5（main マージ済み）**。本 ADR は org レベルの登録・
位置づけ。

### 1. 隔離は「設定の約束」ではなく「構造的保証」

`computer` surface は **host の実 HID（`pointer/host`・`keyboard/host`・`display/host`）に
provider を offer しない**。computer-use component はそれらを import すらしない。呼べば
`unresolved-capability` で **loud denial**、到達不能。合成入力は仮想 surface（Xvfb
コンテナ / microVM）にしか解決しない。さらに admit が trust を `:ai-generated` に floor、
policy の `:host-input` forbid と `:net-allow` 減衰、全操作の監査が多層で重なる。

### 2. 1 manifest・3 backing

| surface | backing | host 隔離 | GPU | 用途 |
|---|---|---|---|---|
| `computer-virtual` | Linux + Xvfb + Chrome（OrbStack/Lima） | 完全 | software/WebGL2 | headless CI・UX QA |
| `computer-vm` | Parallels/QEMU microVM（virtio-gpu） | 完全 | 実 WebGPU 寄り | GPU 正確な描画検証 |
| `computer-host` | host WindowServer（従来の macos-computer-use） | 無し | native | **署名必須**の opt-in 脱出口 |

従来あなたの画面を奪っていたのは実質 `computer-host`。本決定で **既定が virtual** になり、
実デスクトップ駆動は「署名・明示・監査つきの意図的選択」に格下げされる。

### 3. host の env/key は ambient 継承しない（原則）

deny-by-default のため shell `env`・API キー・1Password・Keychain は wasm サンドボックスに
自動で入らない（既定 policy は `:ai-generated` に network/secrets/persistent-write を forbid）。
必要な秘密だけ渡す場合は **明示・scoped・監査つき capability**（`net/fetch` を
`:net-allow` で origin に絞ったのと同型: `secrets/get :the-key` を当該 component にだけ grant、
鍵の実値は host 側 provider=TCB が保持し wasm には渡さない）。

### 4. wasm 基盤とコンテナの役割分担

- **component = Wasm**（wasmtime, fuel/メモリ制限）。wasm 自身はコンテナを起動できない
  （exec/process 能力は deny-by-default で無い）。
- **コンテナ/microVM は surface の backing（provider）= host 側 TCB**。`computer-virtual` の
  `pointer/move` 等の実体がコンテナ（Xvfb）、`computer-vm` が microVM。wasm は能力を呼ぶだけで
  背後を知らない・制御しない。→ wasm 隔離 と コンテナ/VM 隔離が二重に合成される。

## Consequences

- (+) computer-use の自由度を保ちつつ host のキーボード/マウス/画面を奪わない。
- (+) 隔離が構造的（host HID は un-offered 能力 = 同一 gate で deny）。約束事でなく機構。
- (+) 危険な `computer-host` は削除でなく **署名・明示・監査つき**の脱出口に格下げ。
- (+) 同一 component が CI（virtual）と GPU 正確（vm）で manifest 無改変に動く。
- (−) provider の host 側コード（Xvfb/X 入力 binding、microVM 入力エージェント、
  WindowServer adapter）は TCB。Phase-7 MMIO/DMA adapter 同様に監査対象。
- (−) `computer-virtual` の WebGPU は software/WebGL2 fallback。pixel 厳密検証は `computer-vm`。

## References

- aiueos ADR-0007 — The `computer` surface: capability-isolated computer-use
- aiueos ADR-0005 — Multi-surface capability providers / ADR-0004 — code-as-data admit
- 実装 PR: com-junkawasaki/aiueos #5（main マージ済み。verify/admit で隔離を実機証明、lib test green）
