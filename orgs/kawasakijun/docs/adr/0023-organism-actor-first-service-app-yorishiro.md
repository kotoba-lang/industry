# ADR-0023: organism actor first for webservice/app yorishiro automation

- **Status**: Accepted
- **Date**: 2026-06-28
- **Deciders**: 河崎純真
- **Context tags**: clojure, cljc, actor, organism, yorishiro, skill, browser-use, computer-use, arxiv
- **Related**: ADR-0013 portable Clojure agent stack, ADR-0020 three-org taxonomy, ADR-0021 rust/clj/wasm layering
- **First implementation**: `orgs/kotoba-lang/arxiv/`

## Context

API が無い、または API だけでは完結しない web service / native app / mobile app に対して、
AI agent が browser 操作や computer-use 操作で代行する必要がある。arXiv 投稿、Mac app 操作、
iOS app 操作などは、DOM・画面・利用規約・認証・人間承認・サイト変更への追従が絡むため、
単純な CLI recipe だけでは world model として弱い。

一方、外部サービスごとに単なる proxy actor を増やすだけでは、etzhayyim の
actor-as-organism モデルと断絶する。外部サービスや app は、agent から見れば対話可能な
活動主体として扱える方がよい。さらに arXiv actor には arXiv のカテゴリ、投稿状態、
ポリシー、HTML/ヘルプページ snapshot、検証ルールなどを EDN / CLJ / IPFS で保持させたい。

## Decision

webservice / app automation は **organism actor first, capability/skill backed** で設計する。

```
Organism Actor
  has dialogue interface
  has functional capabilities
  owns domain knowledge, policy, provenance, audit memory
  routes execution to skills through yorishiro surfaces
```

階層は次の通り。

| Layer | 役割 |
|---|---|
| Actor | 使命、対話、知識、方針、capability registry、監査責任を持つ organism |
| Capability | 他 agent が呼ぶ安定 operation。例: `:arxiv/validate-package` |
| Skill | capability の具体手順。EDN recipe / CLJ function / graph |
| Yorishiro | 実操作 surface。API / browser DOM / browser vision / computer-use / mobile / human handoff |

外部サービス actor は「公式の arXiv 本体」ではなく、外部制度・サイト・app を代表して
agent world に参加する **yorishiro organism** とする。manifest には `:not-official true` を明示する。

## Actor kinds

ADR-0020 の taxonomy は維持する。runtime/substrate は `com-junkawasaki`、
使命を持つ deploy organism は `etzhayyim` が原則である。ただし、個人研究・個人生産性・
再利用可能 proof-of-pattern としての actor template は `com-junkawasaki` に置ける。

本 ADR の初回実装 `org-arxiv-kotoba` は、arXiv という外部制度を代表する organism actor の
再利用可能 Clojure substrate + knowledge repo なので `com-junkawasaki` に置く。

## Interface split

同一 actor 内で対話 interface と機能 interface を分ける。

```clojure
(ask :actor/arxiv "This paper is about Wheeler-DeWitt. Which categories?")

(invoke :actor/arxiv
        {:op :arxiv/validate-package
         :paper-dir "arxiv_submission/"})

(invoke :actor/arxiv
        {:op :arxiv/create-submission
         :paper-dir "arxiv_submission/"
         :title "..."
         :human-approval true})
```

対話 interface は説明・相談・状態要約を行う。機能 interface は capability dispatch を行い、
risk policy、approval gate、route selection、run log を必ず通す。

## Safety policy

次の operation は既定で人間承認を要求する。

- public submission / final submit
- withdrawal / replacement submission
- payment / financial action
- destructive file or account change
- CAPTCHA / bot-detection bypass が絡む操作
- 利用規約上 automation が不明確な画面

automation は CAPTCHA を解かない。`human-handoff` route で停止し、operator が再開する。

## Consequences

- 他 agent は webservice / app を actor として対話的に扱える。
- 実行の泥臭さは skill + yorishiro に閉じ込められる。
- サイト変更への追従は skill repair / snapshot drift detection の対象になる。
- actor memory は EDN / CLJ / IPFS provenance で監査可能になる。
- `manimani` は CLI facade として `actor ask` / `actor invoke` / `skill run` を呼べばよい。

