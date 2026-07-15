---
id: adr-2607061650-kotoba-lang-cacao-rename
title: "ADR-2607061650: cacao → org-chainagnostic-cacao"
status: accepted
doc_type: adr
topic: naming
authoritative: true
last_verified: 2026-07-06
authoritative_for:
  - kotoba-lang/cacao を org-chainagnostic-cacao へ改称する判断
    (CAIP-122/CAIP-74、chainagnostic.org が管理する Chain Agnostic
    Improvement Proposals)
  - kotoba-lang/authentication の cacao 参照はローカル未コミットWIPのみで
    実際には依存していないと判明した調査結果
related:
  - orgs/kotoba-lang/org-chainagnostic-cacao
  - 90-docs/adr/2607061620-kotoba-lang-ed25519-rename.md
  - 90-docs/adr/2607061640-kotoba-lang-dag-cbor-rename.md
supersedes: []
superseded_by: []
---

# ADR-2607061650: cacao rename(このバッチの最終repo)

## 背景

README で実体確認: "CAIP-122 / SIWE (EIP-4361) CACAO mint + verify in pure
Clojure" — CACAO は CAIP-74(Chain Agnostic CApability Object)で定義され、
CAIP-122 がその Sign-In-With-X への適用を定める。両方とも
chainagnostic.org(Chain Agnostic Standards Alliance)が管理する Chain
Agnostic Improvement Proposals。よって `org-chainagnostic-cacao`。

このリポジトリは ed25519/dag-cbor の依存元(消費者)でもあるため、本ADRは
ed25519(2607061620)・dag-cbor(2607061640)のrename完了後に実施した。

## 依存グラフ調査 — 1件は偽陽性だった

事前 grep で6件の依存を検出したが、実際に fresh clone で検証したところ
**1件(`kotoba-lang/authentication`)は偽陽性**だった:

- ローカル共有 checkout(`orgs/kotoba-lang/authentication`)の `deps.edn`
  には cacao への依存が書かれていたが、`origin` remote が
  `com-junkawasaki/root`(superproject 自身!)を指す**壊れた設定**になって
  おり、明らかに信頼できない状態だった。
- `git@github.com:kotoba-lang/authentication.git` から素の fresh clone を
  取って確認したところ、`deps.edn` は `:deps {}`(依存ゼロ)で、
  `git log -- deps.edn` も初回コミット1件のみ — **GitHub上の実体には
  cacao への依存は一度も存在しなかった**。
- 結論: ローカル checkout 側の**未コミットWIP**(誰かの作業中の変更、push
  されていない)を見ていただけ。実際には何も更新不要。共有 checkout の
  WIP は破棄せず、そのまま触らずに残した。

実依存4件(fresh clone/worktree で確認・更新済み):

| repo | ファイル |
|---|---|
| `gftdcojp/cloud-itonami` | deps.edn |
| `gftdcojp/local-manimani` | agents/deps.edn |
| `gftdcojp/net-kotobase` | cli/deps.edn, cli/nbb.edn |
| `kotoba-lang/kotoba` | deps.edn(2箇所: git座標 + `:dev` alias の `:local/root`) |

`etzhayyim/root` は ed25519/dag-cbor と同じ理由でスキップ。

## 決定

`kotoba-lang/cacao` → `kotoba-lang/org-chainagnostic-cacao`。手順は
ADR-2607061620/2607061640 と同一。

## 検証

```
gh api repos/kotoba-lang/org-chainagnostic-cacao --jq '.full_name'
cd cloud-itonami && clojure -Spath   # (worktree外symlink制約で完全な-Spathは未実施、diffは目視確認)
cd kotoba         && clojure -Spath  # 解決OK
nbb scripts/gen-west-manifest.cljs --entry org-chainagnostic-cacao
```

## Consequences

- (+) 4つの実依存先の座標が canonical に。
- (+) `authentication` の偽陽性を見抜けたことで、無関係な誤った変更を
  実リポジトリに加えずに済んだ。
- これで ipfs/svg/cacao/ed25519/dag-cbor の5件すべてのrenameが完了。
