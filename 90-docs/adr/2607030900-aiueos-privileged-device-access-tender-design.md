# ADR-2607030900: aiueos の生ハードウェアアクセス（device-access quartet: pci-config/dma-map/irq-subscribe/mmio-map）を実現する特権/hypervisor 協調レイヤーの設計提案

**Status**: proposed（未承認——実装前にオーナーレビュー必須）
**Date**: 2026-07-03
**Deciders**: 未確定（提案者: Claude、オーナー承認待ち）

## Context

ADR-2607022900（`accepted, implemented`、9件の follow-up を経て close 済み）は、
aiueos の native adapter 実行層と CLI 本体を Rust から JVM/Clojure + Chicory へ
完全移行した。残る恒久的ギャップとして明記された3点のうち、**生ハードウェア
アクセス**（device-access quartet: `pci-config`/`dma-map`/`irq-subscribe`/
`mmio-map`）は現在も完全に未着手のまま——`aiueos.execute`ではこれら4つの
capability は常に`0`を返す deterministic stub（`device-access-stub`）で、
capability として名前を付けられる・gate をかけられるようにはなっているが、
実際のハードウェアには一切触れない。

ADR-2607022900 follow-up 2（2026-07-02 の再調査）で、この設問の
フレーミング自体が誤りだったと判明した:「Rust か Java か」という言語選択
の問題ではなく、**「特権/hypervisor 協調レイヤーそのものが存在しない」**
という、言語非依存の本質的な問題である。根拠（Solo5 の実アーキテクチャ調査
より）:

- Solo5 の `hvt`（hardware-virtualized tender）では、**tender 側が KVM 特権を
  持ち** guest のメモリ/デバイスマッピングを設定する。**guest（unikernel）
  自身は生 MMIO に一切触れない**——guest は `solo5_net_write` のような
  narrow hostcall を発行するだけで、tender がそれを仲介する。
- `spt`（sandboxed process tender）では guest はさらに seccomp で制限された
  非特権プロセスとして動く。
- 結論: **非特権のユーザースペースプロセスは、どの言語であっても生 MMIO/DMA
  に直接触れない**——root + `/dev/mem`（近年の Linux では
  `CONFIG_STRICT_DEVMEM` で年々制限強化）か、KVM/hypervisor 権限か、
  カーネル自身であることが必須。

`java.lang.foreign`（Java 22 で正式化、本リポジトリの CI が pin する Java 21
では preview のみ）は Rust の `libc` バインディングと同等の `mmap()`/
`ioctl()` 呼び出し能力を持つため、「特権レイヤーの実装言語」としては
Java 22+ FFM と Rust のどちらも技術的に選択可能——**が、特権レイヤー自体を
設計・実装する仕事は、どちらの言語を選んでも未着手のまま**である。

本 ADR は、この特権レイヤーをどう設計するかについての**提案**であり、
実装の承認ではない。ADR-2607022700 が確立した「native adapter は capability
を自分で判定しない、CLJC の判断にのみ従う」という不変条件は、本提案でも
一切変更しない。

## Decision（提案）

### 1. 特権昇格の範囲を最小化する: 「device-access provider」という別プロセス

ADR-2607022700 のモジュール disposition table が既に確立した precedent
（`aiueos.decide`: EDN-over-stdio の decision subprocess）と同じパターンを、
**特権昇格が必要な部分だけ**に適用する:

- `aiueos.execute`（現状の JVM/Chicory tender、非特権のまま）が、
  `pci-config`/`dma-map`/`irq-subscribe`/`mmio-map` の呼び出しを受けたとき、
  **別の、最小権限の特権 helper プロセス**（`aiueos-device-provider`、仮称）
  に EDN-over-stdio でリクエストを転送する。
- `aiueos-device-provider` だけが root/CAP_SYS_RAWIO 相当の特権を持つ
  （setuid/capabilities/専用サービスアカウント経由——具体的な権限付与方式は
  デプロイ環境依存、本 ADR のスコープ外）。**JVM 本体（tender/broker/CLJC
  判定層）は非特権のまま**——攻撃対象領域を「実際に生アクセスするコード」
  だけに限定する。
- `aiueos-device-provider` 自身は capability を判定しない（ADR-2607022700
  の不変条件を継承）——`aiueos.execute` 側が既に broker の grant 判定を
  終えた後にのみ、この provider へリクエストを送る。provider は「命令された
  通りに実行するだけ」の薄い層。

### 2. 実装言語: Java 22+ FFM を第一候補とする（Rust ではなく）

理由:
- `aiueos-device-provider` の役割は「narrow なリクエストを受けて `mmap()`/
  `ioctl()`/KVM syscalls を叩くだけ」——ADR-2607022900 で確立した
  「Chicory/JVM で完結させる」方針との一貫性を保てる。
- ただし、これには **JDK 21 → 22+ へのバンプが必要**（`java.lang.foreign`
  が正式化されたのは Java 22）。現状 CI が Java 21 を pin しているため、
  このバンプ自体が本 ADR 承認後の最初の作業になる。
- 代替として Rust で書くことも技術的に可能（`libc`/`nix` クレート）だが、
  そうすると ADR-2607022900 が完了させた「Rust 依存の完全排除」を部分的に
  後退させることになる——特権 provider という**最小の**範囲に限定してでも
  Rust を再導入するかどうかは、オーナーが判断すべきトレードオフとして
  明記する（Consequences 参照）。

### 3. 段階的実装（提案・未着手）

1. **spike**: `hvt` 相当の最小 KVM ベース VMM（1個の PCI デバイスへの
   MMIO read/write だけができる、テスト用の最小実装）を Java 22+ FFM で
   prototype し、実際に生アクセスが可能か検証する。
2. `aiueos-device-provider` の EDN-over-stdio プロトコルを設計
   （`aiueos.decide` の request/response 形式を参考に、
   `{:aiueos.device/op :mmio-map :aiueos.device/args [...]}` のような形）。
3. `aiueos.execute`側のdevice-access-stub を、実際に provider へリクエストを
   転送する実装に置き換える（現状のstub関数のシグネチャは変えず、内部実装
   だけ差し替える設計にすれば、既存の186件のテストへの影響を最小化できる）。
4. 権限付与方式（setuid/capabilities/専用サービスアカウント）の選定と、
   デプロイドキュメント整備。

## Consequences

- (+) 特権昇格の範囲を「実際に生アクセスするコードだけ」に限定できる——
  JVM tender/broker/CLJC 判定層は非特権のまま、attack surface を最小化。
- (+) ADR-2607022700 の「native adapter は capability を自分で判定しない」
  という不変条件を、特権 provider にも一貫して適用できる。
- (−) **JDK 21 → 22+ へのバンプが前提**——CI・全ての `.clj`/`.cljc` 依存先
  への影響調査が必要（本 ADR のスコープ外、承認後の別作業）。
- (−) 特権 provider を Rust で書く代替案は、ADR-2607022900 が完了させた
  「Rust 依存の完全排除」を部分的に後退させる——オーナーがこのトレードオフ
  を判断する必要がある。
- (−) KVM ベースの最小 VMM は本質的に大きな実装作業——本 ADR は**設計方針の
  提案**に留め、実装コミットメントはしない。
- (−) 本 ADR は `proposed` のまま——`accepted` にする判断、JDK バンプの
  可否、Rust 再導入の可否は全てオーナー確認が必要。

## References

- ADR-2607022700（native adapter 設計、decision subprocess パターンの元）
- ADR-2607022900（Chicory/JVM 移行、Follow-up 2 で生ハードウェアアクセスの
  設問を「Rust vs Java」から「特権/hypervisor 協調レイヤーの不在」に訂正）
- ADR-2607022400（kototama = Solo5 tender パターン採用の原点）
- Solo5 architecture.md / README.md（`hvt`/`spt` の実特権分離設計）
- JEP 454（Java 22, Foreign Function & Memory API）
