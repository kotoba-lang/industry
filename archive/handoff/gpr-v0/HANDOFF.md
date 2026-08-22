# handoff: kotoba-lang/gpr v0

**この dir は `kotoba-lang/gpr` として押し出されるのを待っている child repo の中身である。
root の一部ではない。** 押し出して west に登録したら、この dir ごと消すこと。

## なぜ root に置いてあるのか

実装したセッションの GitHub App が `com-junkawasaki` にスコープされており、
`kotoba-lang` に repo を作れなかった（実測 2026-08-22）:

```
POST /orgs/kotoba-lang/repos              -> 403 Resource not accessible by integration
POST /orgs/com-junkawasaki/repos          -> 404 Not Found
add_repo kotoba-lang/voxel                -> cross-tier adds are not supported in v1
```

remote container は破棄されるので、作業を失わないためにここに置いた。
**scope を持つセッション（kotoba-lang を source に持つ session、または手元）から
押し出す**のが正しい着地で、root に置き続けることではない。

## 押し出す手順

```bash
cd archive/handoff/gpr-v0
npx --yes nbb run-tests.cljs          # 23 tests / 48 assertions が緑であることを先に確認

git init -b main && git add -A
git commit -m "gpr: 地中レーダーの trace → 断面 → 埋設物候補（v0）"
gh repo create kotoba-lang/gpr --public --source=. --push \
  -d "地中レーダー（ground-penetrating radar）の trace → 断面 → 埋設物候補。純 .cljc、依存ゼロ。"
```

## west に登録する（押し出した後）

```bash
# 1) manifest/repos.edn の :manifest.repos/extra-projects に "orgs/kotoba-lang/gpr" を足す
# 2) 当該 entry のみ生成（wholesale 再生成は禁止）
nbb scripts/gen-west-manifest.cljs --entry gpr
# 3) pin が upstream default branch から到達可能か検査
nbb scripts/verify-west-pins.cljs
# 4) 登録漏れが無いことを確認
nbb scripts/west-orphan-audit.cljs --blocking
```

登録が終わったら **この dir を削除する commit** を同じ PR に入れる。
`archive/handoff/` に残った child repo の中身は、west の checkout と衝突する。

## 状態

- 実装・検査の中身は [README.md](README.md) の「測ったこと / 測っていないこと」が正本。
- `kotoba/candidate_core.kotoba` は**未コンパイル**（この環境に clojure CLI が無い）。
  押し出した先で `amu compile` を通し、`resources/candidate-decision-vectors.edn` の
  表と突き合わせる parity harness を書くまで、その decision core は「書いてある」だけ。
- 設計の正本は superproject の **ADR-2608750000**。
