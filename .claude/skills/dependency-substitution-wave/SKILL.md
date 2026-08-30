---
name: dependency-substitution-wave
description: kotoba-lang の外部依存を manifest/dependency-substitution.edn の先に rewire する波を 1 本進める。1 反復 = 1 coordinate × 最大 4 repo + west pin 前進 + verify 再測定。常駐 loop com.gftd.dependency-substitution-wave が呼ぶが、手で `/dependency-substitution-wave` でもよい。「dependency substitution」「noble 置換」「data.json 移行」「verify-dependency-substitution」で発火。
---

# dependency-substitution-wave — 外部依存 substitution を 1 波

正本: **ADR-2608260200**（ledger: `manifest/dependency-substitution.edn`）、
**ADR-2608301000**（この loop）。

**1 反復 = 1 coordinate に揃えた最大 4 repo。** tick が選んだ `:coordinate` と
`:target` をそのまま使う —— 自分で別の coordinate を混ぜない。

## 0. 前提を測る（推測しない）

```bash
cd "$COM_JUNKAWASAKI_ROOT"
git fetch origin -q && git merge --ff-only origin/main
nbb --classpath ".:scripts/nbb_compat" scripts/dependency-substitution-wave-tick.cljs --limit 4
```

- tick **exit 2** → 測れていない。**何もしない。**
- **CANDIDATES 0** → その coordinate は枯渇か全部 in-flight。
- 出力の `WAVE-COORDINATE` / 各行の `:repo` `:coordinate` `:target` を**そのまま**使う。

再測定の scoreboard:

```bash
nbb --classpath ".:scripts/nbb_compat" scripts/verify-dependency-substitution.cljs --findings \
  | rg 'substitution-available'
```

## 1. 4 本の fresh subagent に投げる

**fresh agent（`fork` 禁止）**、**model は `sonnet`**。1 agent = 1 repo。

隔離: 子リポの中で `git worktree add`（superproject 内 worktree は `orgs/` が空になる）。

ブランチ名は全 repo 共通: **`agent/dependency-substitution`**。

## 2. 波が終わったら pin を中央で 1 commit にまとめる

agent は `manifest/west.yml` を触らない。

```bash
# west entry 名を path から引く
node -e '
const fs=require("fs");let n=null;
for(const l of fs.readFileSync("manifest/west.yml","utf8").split("\n")){
  let m;
  if(m=l.match(/^\s*- name:\s*(\S+)/)) n=m[1];
  else if(m=l.match(/^\s*path:\s*(\S+)/)) if(process.argv.slice(1).includes(m[1])) console.log(m[1],n);
}' orgs/kotoba-lang/<name> ...

PINS=pins.tsv DRY=1 nbb --classpath ".:scripts/nbb_compat" scripts/west-pin-put-batch.cljs
PINS=pins.tsv     nbb --classpath ".:scripts/nbb_compat" scripts/west-pin-put-batch.cljs
nbb --classpath ".:scripts/nbb_compat" scripts/verify-west-pins.cljs
```

SHA は `gh api repos/kotoba-lang/<name>/commits/main --jq .sha` から取る。

## 3. checkout を pin に合わせ、verify で締める

```bash
git fetch origin -q && git merge --ff-only origin/main
printf '%s\n' <west-name1> <west-name2> | xargs west update --fetch smart
nbb --classpath ".:scripts/nbb_compat" scripts/verify-dependency-substitution.cljs
```

## coordinate 別の rewire 指針（ledger を読んだうえで）

### `@noble/ciphers`

ledger の `:target` は `kotoba-lang/org-ietf-chacha20-poly1305`。**実測 2026-08-30**:
repo が import しているのは Noble の `gcm` / `gcmsiv`（AES-GCM / GCM-SIV）が多い。
→ **`kotoba-lang/org-nist-aes`** の `aes.gcm` / `aes.gcm-siv` に寄せる。
ChaCha20-Poly1305 だけが要る箇所は `org-ietf-chacha20-poly1305`。
`package.json` から `@noble/ciphers` を落とし、shadow / cljs テストを通す。
先例: `kotobase-server`, `net-kotobase/control-plane`（superproject 外なら該当 repo の main を参照）。

### `@noble/hashes`

`:target` `kotoba-lang/org-nist-sha2`。ledger の `:note` どおり subpath で振り分け:
sha2/hmac → org-nist-sha2、blake2 → org-ietf-blake2、hkdf → crypto、sha1 → hash。
argon2 は別 entry（org-ietf-argon2、遅いので login path に安易に載せない）。

### `hpke-js`

→ `kotoba-lang/org-ietf-hpke`。

### `@ipld/dag-cbor`

→ `kotoba-lang/org-ietf-cbor`（既存 cljs 利用パターンを repo 内で踏襲）。

### `datascript/datascript`

→ `kotoba-lang/datalog`。**API 書き換えが要る** —— 1 repo だけでも diff が大きい。
1 波で 4 repo までだが、テストが通らなければ merge しない。

### `org.clojure/data.json` / `metosin/jsonista`

→ `kotoba-lang/json`（`json.core` / `json.compat`）。多くは landed 済み —— 残りだけ。

## agent プロンプトに必ず入れるもの

| 入れるもの | なぜ |
|---|---|
| tick が渡した **coordinate / target / repo path** | 波を混ぜない |
| **remote は org 名**（`kotoba-lang`） | west checkout の慣習 |
| **detached HEAD** — merge 先は `kotoba-lang/main` | west pin checkout |
| **新規 JVM 依存を deps.edn トップに足さない** | jvm-new-surface-guard |
| **`package.json` の dev/prod を両方見る** | shipped と tool の区別 |
| **build/test が通らなければ merge しない** | 壊れた rewire は負債 |
| **rebase / force-push 禁止** | CLAUDE.md |
| **`manifest/west.yml` を触らない** | pin は中央 |

## 測定

成功は「自己申告」ではなく **次周の `substitution-available:<coordinate>` 件数減**
または当該 repo がリストから消えること。
