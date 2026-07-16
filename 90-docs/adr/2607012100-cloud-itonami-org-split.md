# ADR-2607012100: cloud-itonami-* public blueprint repos の gftdcojp → cloud-itonami org 分離

**Status**: accepted
**Date**: 2026-07-01
**Deciders**: Jun Kawasaki

## Context

`cloud-itonami-{ISIC}`(ADR-2607011000, 26 repo)と `cloud-itonami-isco-{ISCO-08}`
(ADR-2607012000, 9 repo)の blueprint repo 群、計 35 件は当初 `gftdcojp` org 直下に
public repo として発行されていた。`gftdcojp` は ADR-2606302300 の 4-org taxonomy で
「human-centric / ビジネス(commercial)」scope と定義されている一方、これらの
blueprint は誰でも fork して独立事業を始められる forkable OSS business/occupation
blueprint であり、`gftdcojp` の自社商用製品(`ai-gftd-*`)とは性質が異なる。

base の `cloud-itonami` 本体(business-os、ADR-2606271700)は private の事業運用体
そのものであり、上記 35 件の public blueprint とは別物。

2026-06-30 に `cloud-itonami` org(https://github.com/cloud-itonami)が作成済みで、
free plan の範囲内(public repo は無料・無制限)で運用できる状態にあった。

## Decision

`cloud-itonami` org を **「cloud-itonami-\* public blueprint 専用」org** として運用する。
`gftdcojp` 配下の public な `cloud-itonami-*` 系列 35 件全てを GitHub repo transfer
(owner のみ変更、visibility は public のまま不変)で `cloud-itonami` org へ移管した。
base の `cloud-itonami`(private, business-os)は影響を受けず `gftdcojp` に残す。

方式: `gh api repos/gftdcojp/<name>/transfer -f new_owner=cloud-itonami`。実行アカウントが
`gftdcojp`/`cloud-itonami` 両方の admin だったため即時完了(承認待ちフローなし)。
ローカル checkout `orgs/gftdcojp/cloud-itonami-*` は `orgs/cloud-itonami/` へ移動し、
remote URL(`origin`/`gftdcojp` いずれの remote 名だったものも)を
`git@github.com:cloud-itonami/<name>.git` に更新した。

対象 35 件:
- ISIC(ADR-2607011000 由来、26 件): `cloud-itonami-{A0162,B0810,C2610,C3030,D3512,
  E3600,E3830,F4211,G4711,H4920,I5510,J6190,J6310,K6419,K6619,L6810,M7110,N7810,
  O8411,P8569,Q8691,Q8810,R9101,S9511,T9700,U9900}`
- ISCO-08(ADR-2607012000 由来、9 件): `cloud-itonami-isco-{1321,2221,3253,4321,5322,
  6112,7126,8332,9312}`

## Consequences

- (+) blueprint 35 件が独立 org 配下に集約され、fork 元として発見しやすくなった。
  `gftdcojp` の自社商用 repo 一覧(`ai-gftd-*` 等)から明確に分離された。
- (+) base `cloud-itonami`(private, business-os)は無変更で `gftdcojp` に残留。
- (+) `manifest/repos.edn` / `manifest/west.yml` は元々これらを管理していない
  (ADR-2607011000/2607012000 が定めた「blueprint repo は standalone」の慣例通り)ため、
  manifest 側の更新は不要だった。
- (−) ADR-2606302300 の 4-org taxonomy(`kotoba-lang` / `etzhayyim` / `gftdcojp` /
  `com-junkawasaki`)に対する明示的な例外が生まれた。`cloud-itonami` は 5 番目の
  "org" だが、既存 4 org の役割分担(language-substrate / agent-centric /
  human-centric / transitional-foundation)を変更するものではなく、あくまで
  `cloud-itonami-*` product-line 専用の public repo 置き場である。本 ADR がその
  置き場所の権威。今後 `cloud-itonami-*` 系列が増える場合、新規 repo は最初から
  `cloud-itonami` org 直下に作成してよい(`gftdcojp` 経由の transfer を経ない)。
- ADR-2607011000 / ADR-2607012000 の「blueprint repo (gftdcojp org, public,
  AGPL-3.0)」という記述は本 ADR 時点で古くなったため、両 ADR に addendum を追記した
  (原文の decision/consequences は移管当時の事実として保持し、書き換えない)。

## References

- ADR-2607011000(cloud-itonami robotics premise + ISIC 21/21) — 移管元 26 repo の出自。
- ADR-2607012000(cloud-itonami-isco occupation blueprints) — 移管元 9 repo の出自。
- ADR-2606271700(cloud-itonami business-os) — private base repo、本 ADR の対象外。
- ADR-2606302300(4-org taxonomy) — 本 ADR が明示する例外の親規範。
- 本 ADR とペアの `.edn`
