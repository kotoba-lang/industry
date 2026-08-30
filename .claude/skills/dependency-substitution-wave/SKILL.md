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

### `@noble/ciphers` — **rewire しない（2026-08-30、`:host-boundary` へ変更済み）**

**この coordinate は pool から外した。tick が出さなくなっているはずで、出たら ledger を読む。**
理由は「実装が無い」ではない —— `org-ietf-chacha20-poly1305` も `org-nist-aes` も実在し、
portable `.cljc` で RFC ベクタを通る。**落ちるのは速度で、しかもテストに映らない**
（pure 実装は正しいので rewire は緑のまま 3 桁の regression を landed させる）。

実測（nbb = 両 call site が実際に使う runtime。どちらも shadow-cljs を持たない。load 34–37）:

| | pure `.cljc` | `@noble` |
|---|---|---|
| ChaCha20-Poly1305 seal 96 B / 1 KiB | 3.25 ms / 52.4 ms | 0.0065 ms / 0.012 ms |
| AES-256-GCM seal 96 B / 8 KiB | 48.9 ms / 2144 ms | 0.046 ms / 0.560 ms |

- `noise/provider/noble.cljs` は**この発見を既に済ませている** —— sibling primitive の
  27 ms `@noble/curves` DH を逃げるためだけに `provider/node.cljs` が在り、docstring に表がある。
- ⚠ **`aes.gcm-siv` は存在しない。** `org-nist-aes` は `aes.core` と `aes.gcm` だけ。
  GCM-SIV は RFC 8452（GHASH でなく POLYVAL + nonce ごとの派生鍵）なので、
  `gcmsiv` を `aes.gcm` に置き換えると **wire format が壊れ、nonce 誤用耐性が黙って消える。**
- 再開は **coordinate 単位ではなく call site 単位で**。cold path は今も価値がある
  （`kotobase-server` の HMAC → `org-nist-sha2` はそれ）。

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
