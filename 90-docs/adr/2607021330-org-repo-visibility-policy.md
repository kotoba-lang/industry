# ADR-2607021330: org 別 repo visibility の恒久方針（kotoba-lang / etzhayyim = public）

**Status**: accepted
**Date**: 2026-07-02
**Deciders**: Jun Kawasaki

## Context

これまで新規子リポの visibility は「既定 private」（CLAUDE.md 恒久承認の文言）で
org 別の方針が明文化されておらず、実態も混在していた（kotoba-lang に private 139 /
public 133、etzhayyim に private 5 / public 170）。オーナー指示（2026-07-02）:
**kotoba-lang と etzhayyim の repo は全て public でよい。恒久化する。**

org の役割（ADR-2606302300 taxonomy）に照らすと自然な帰結でもある:
kotoba-lang は言語基盤（全 org が消費する純 CLJC ライブラリ群）、etzhayyim は
公益 scope の organism actors。gftdcojp（ビジネス）と com-junkawasaki
（私的基盤・cloud 制御面・data）は非公開が既定のまま。

## Decision

1. **visibility の org 既定を恒久方針にする**:

   | org | visibility 既定 |
   |---|---|
   | kotoba-lang | **public** |
   | etzhayyim | **public** |
   | gftdcojp | private |
   | com-junkawasaki | private |

2. **既存 repo を方針に揃えた**（2026-07-02 実施）: kotoba-lang の private 139 件
   + etzhayyim の private 5 件（com-etzhayyim-{fleet,sng,tsumugu,yomi} /
   com-google-ads）を全て public 化。両 org の非 public は 0 件を確認。
3. **恒久化の反映先**: `manifest/repos.edn` の `:orgs` に `:visibility` キーを追加
   （新規 repo 作成時の既定はここを読む）。CLAUDE.md の恒久承認節の
   「既定 private」文言を org 別既定に改める。
4. 例外を作る場合（public org に private repo を置く等）は都度オーナー確認 +
   repos.edn 側に理由をコメントで残す。

## Consequences

- (+) 新規 repo 作成（恒久承認フロー）の visibility 判断が SSoT（repos.edn :orgs）
  で決まり、セッション毎のブレが消える。
- (+) kotoba-lang（言語基盤）と etzhayyim（公益）が公開され、外部から参照可能に。
- (−) public 化は事実上不可逆（履歴が一度公開される）。secrets 混入は
  b2-creds / cacao identity の「秘密はコミットしない」規約で従来から防いでいるが、
  public org への push は今後この前提がより強く効く。

## References

- ADR-2606302300（org taxonomy）/ ADR-2607021300（kotoba-lang 46 home 実体化）
- CLAUDE.md「標準作業の常時許可」節 / `manifest/repos.edn` `:orgs`
- 本 ADR とペアの .edn
