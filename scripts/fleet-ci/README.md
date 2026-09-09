# fleet-ci — murakumo mac-mini fleet を git の CD/CI として使う

**GitHub Actions ではなく自分の mac mini fleet で、commit ごとにテストを実行し、
署名付き receipt を append-only 台帳に積み、結果を commit status として書き戻し、
green なら west pin を前進させる。** 設計 ADR: ADR-2607178000（standing runner の初版）
+ ADR-2607255500（本 tip 駆動 CI/CD 拡張）。

```
tip 変化を検出 → gate をノードへ fan-out → 署名 receipt を fleet-ci.edn に追記
              → commit status を書き戻し → green なら west pin を前進（CD）
```

## 静かに壊れる 2 つを直した（2026-08-17）

**CD の pin 前進が push race を再試行していなかった。** landing repo の ref 更新
そのものが楽観ロックなので、fleet が忙しいと別セッションに負けて `push rejected`
が返る。receipt landing は最初から 3 回まで再試行していた（`land-receipt!`）が、
`advance-pin!` は 1 回で諦めていた —— **同じ race を、片方だけが吸収していた。**

症状は green のときにしか出ない: gate は通り、署名 receipt は着地し、pin だけが
取り残される。**赤い gate としては現れないので、誰も気付かない。** 実測 2026-08-17、
3 本走らせて 2 本（`cloud-itonami-isco-4311` と `tehai`）が置き去りになった。

再試行するのは `push rejected` のときだけ。pin verification の拒否や entry 不在は
何度やっても同じ答えなので即返す —— 正直な "no" を 3 回に増やしてログに埋めない。
回帰テストは `tick-unit-test.cljs`（4 本）。

**`tick.cljs` に生の NUL バイトが 1 個あり、`grep` がこのファイル全体を binary
として扱っていた。** `file` は `binary data` と答え、**`grep` は常に静かに 0 件を
返していた** —— exit 1 で、「一致なし」と見分けがつかない。実測: 1 セッション中に
このファイルへの検索が 3 回空振りし、コードの不在として読まれた。

正体は `gate-spec-hash` の domain separator で、意味はあった。unicode エスケープで
書けば同じ 1 文字に読まれるので digest は不変、ファイルは text に戻る。

⚠ **`manifest/fleet-ci.edn` にも ESC バイトが 8 個ある**（`app-yorishiro` の
vitest 色付き出力が receipt の `:detail` に入った）。台帳全体が同じく grep 不可視
だが、**これらは署名対象のペイロード内なので触っていない** —— 書き換えると
receipt の signature が壊れる。前向きの対策は gate 出力の ANSI 除去で、それは
receipt を組み立てる `kagami` 側の仕事であってここではない。

## ファイル

| path | 役割 |
|---|---|
| `gates.edn` | **対象 repo と gate の matrix（唯一の対象リスト）**。1 行足せば対象が増える |
| `nodes.edn` | ノードの capability 台帳（**生成物** — `probe.cljs` が書く。手編集しない） |
| `probe.cljs` | 各ノードに SSH して java/clojure/node/空き容量を実測 → `nodes.edn` |
| `provision.cljs` | 足りないノードに homebrew で clojure/openjdk/node を入れる（冪等） |
| `tick.cljs` | 本体（tip 検出 → gate 実行 → receipt → status → pin 前進） |
| `tick-unit-test.cljs` | batch 内 git dependency dedupe と一時 worktree cleanup の回帰テスト |
| `gates/docs-edn-check.cljs` | EDN-only ドキュメント repo 用の gate（ノードへ配って実行） |
| `cloud.itonami.bot.fleet-ci-tip-tick.plist` | 5 分間隔の LaunchAgent（install 手順はファイル冒頭のコメント） |
| `sweep.edn` | **ディスク回収ポリシー（allowlist）**。1 行足せば対象が増える |
| `sweep.cljs` | ノードのディスクを回収する（既定 dry-run、`--apply` で実行） |
| `cloud.itonami.bot.fleet-ci-sweep.plist` | 日次 04:17 の sweep LaunchAgent |

状態・ログはリポジトリ外（`~/.itonami/`）:
`fleet-ci-state.edn`（repo → 最後に検証した sha）/ `fleet-ci-tick.log`（正本ログ）/
`fleet-ci-tick.lock` / `fleet-ci-cache/`（tarball と kagami tree のキャッシュ）。

## 日常運用

```bash
# 今何が回るのか（API を叩くだけ、gate は走らせない）
nbb scripts/fleet-ci/tick.cljs --plan

# runner 自身の副作用なし回帰テスト
FLEET_CI_LIBRARY_MODE=1 nbb --classpath scripts/fleet-ci scripts/fleet-ci/tick-unit-test.cljs

# 1 repo だけ手で回す（変化が無くても回る。landing/status/CD はしない）
nbb scripts/fleet-ci/tick.cljs --only kagitaba --dry-run

# 全部強制的に回す（landing/status/CD あり）
nbb scripts/fleet-ci/tick.cljs --all

# ノードを増やした / provision した後
nbb scripts/fleet-ci/provision.cljs --from-nodes --need jvm
nbb scripts/fleet-ci/probe.cljs

# 常駐（5 分間隔）
cp scripts/fleet-ci/cloud.itonami.bot.fleet-ci-tip-tick.plist ~/Library/LaunchAgents/
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/cloud.itonami.bot.fleet-ci-tip-tick.plist
launchctl kickstart -p gui/$(id -u)/cloud.itonami.bot.fleet-ci-tip-tick   # 1 回だけ手動起動
```

Receipt landing uses a no-checkout sparse worktree containing only
`manifest/fleet-ci.edn`. This keeps each optimistic-lock retry bounded to the
append-only ledger instead of materializing the full superproject tree.

## ディスクを回収する（sweep）

**満杯のノードは gate を走らせる前に殺す。** `tar` が "No space left on device" で
落ちるので、CI は赤にすらならず signal が一切出ない。2026-07-30 に `asher` が実際に
この状態（空き 117MiB / 使用率 100%）で、`dan` 847MiB・`issachar` 1.2GiB も同様。
この 4 ノードは probe の capability が空で、fleet の実行プールから外れていた。

```bash
nbb scripts/fleet-ci/sweep.cljs                 # dry-run（既定）。何を消すかだけ出す
nbb scripts/fleet-ci/sweep.cljs --apply         # 実際に消す
nbb scripts/fleet-ci/sweep.cljs --all           # low-water(20GiB) を無視して全ノード
nbb scripts/fleet-ci/sweep.cljs --only dan,levi # ノードを絞る
```

対象は `sweep.edn` の **allowlist**。`~/.ollama/models`（21G/ノードの実データ）や
`~/comfyui` は *列挙されていないので触られない* — denylist だと新しい実データが
増えたとき黙って巻き込む。

**消す前に孤児であることを証明する。** `:require-absent-binaries` /
`:require-no-launchd-match` / `:require-untouched-days` のどれか 1 つでも
満たされなければ、その entry は skip して理由を出す。実例: llama.cpp cache は
10 ノードで 173G あったが、稼働中の推論は全ノード `ollama serve`、llama バイナリは
未インストール、参照する launchd も 0、最終書き込みは 4 週間前 — この 4 つが
揃って初めて「退役した serving 経路の残骸」と言える。

回収量は **df の前後差で実測**する（コマンドの exit code は成功の証拠にしない）。

初回実績（2026-07-30）: 安全 cache で +13.2GiB、llama.cpp 孤児で +173.1GiB。
`dan` は 848MiB → 31.4GiB。

## 対象を増やす

`gates.edn` の `:repos` に 1 行足すだけ（org は west.yml の remote から解決される）:

```clojure
{:name "<west project name>" :gate :jvm-test :cd true}
```

- `:gate :jvm-test` — `deps.edn` の `:test` alias を `clojure -M:test` で回す（node cap `:jvm`）
- `:gate :nbb-test` — `:entry` / `:classpath` を nbb で回す（node cap `:node`）
- `:gate :nbb-script` — `gates/*.cljs` を配って repo tree に対して回す（node cap `:node`）
- `:cd true` — green かつ pin が遅れていれば **west pin を自動前進**（外したい repo は false）
- `:include-ext [".edn"]` — ノードへ送る tree を絞る（asset 重量 repo。`:min-files` を必ず添える）

## 設計上の不変条件（壊さないこと）

1. **tree は毎回 GitHub から取る。** ローカル checkout は信用しない（stale/dirty 混入の排除）。
2. **exit 0 を信用しない。** gate はノード側で「展開が成功したか」と
   「`Ran N tests` の summary が出たか（N>0）」を assert し、満たさなければ
   exit 90/91/93/94 で fail する。receipt の `:detail` にも出力末尾が入る。
   （ADR-2607178000 の false-pass 事故 = REPL に落ちた clojure が exit 0 を返した）
3. **鍵はノードに配らない。** private repo の tree は operator が認証付きで取得し、
   tarball を **ssh stdin** でノードへ流す。ノードは token も署名鍵も持たない。
4. **署名鍵は弱権限のまま。** `fleet-agents.edn` に enroll した `fleet-ci/*` grant のみ。
   `fleet-keys.edn` には入れない（pin 前進も governance quorum も持たない）。
   **machine ごとに別 identity を enroll する（鍵をコピーしない）。**
5. **pin 前進は必ずサーバ側検証を通す。** `scripts/verify-west-pins.cljs`
   （存在 + default branch 到達性 + 前進のみ）→ branch + contents PUT + server-side merge。
   直接 main に push しない。west.yml は当該 entry の revision 1 行だけ書き換える。
6. **台帳は append-only。** `manifest/fleet-ci.edn` は追記のみ（既存行を書き換えない）。

## LaunchAgent（常駐）の credential

**tick 自体は対話セッションから走らせれば完全に動く**（本番実証済み）。しかし
LaunchAgent（`gui/<uid>`）から起動すると macOS Keychain に触る 2 経路が両方だめになる:

| 経路 | launchd 下での挙動 | 対処 |
|---|---|---|
| `kagi get`（署名鍵） | Keychain の unlock prompt を出せず **timeout** | `FLEET_CI_SIGNER_PEM`（mode 600 の PEM、ADR-2607178000 §3 が認めた経路）を plist で渡す。**設定済み** |
| `gh`（token） | keyring を読めず **匿名にフォールバック**。public repo の GET だけ通り、private repo は 404 / 書き込みは 401 | **owner ごとの fine-grained PAT**（最小権限、オーナー判断 2026-07-25）を `FLEET_CI_GH_TOKEN_DIR/<owner>`（mode 600）に置く。plist は設定済み・**PAT 発行だけ人手** |

`preflight!` が起動直後に「landing 先の private repo を読めるか」で auth を検査し
（`gh api user` では弱い — public repo の GET は匿名でも通るので途中まで動いてしまう）、
使えない context ではその tick を理由付きで skip する。

### 必要な fine-grained PAT（2 本 — PAT は resource owner 1 つにしか紐付かない）

`~/.itonami/fleet-ci-gh-tokens/`（dir 700）に **owner 名のファイル**（mode 600）として置く。
tick は endpoint の owner を見て token を選び、**子プロセスの env にだけ**載せる
（argv に出さないので `ps` に見えない）。

| ファイル | 対象 | 権限 |
|---|---|---|
| `com-junkawasaki` | `com-junkawasaki/root` | **Contents: Read and write**（receipt 追記 + west.yml pin 前進 + branch 作成/削除/merge） |
| | `com-junkawasaki/org-spirit-in-physics-comics` | Contents: Read（trees/blobs）+ **Commit statuses: Read and write** |
| `kotoba-lang` | 対象 12 repo（kagami, nekko, bonsai, langgraph, kagitaba, org-ietf-ed25519, org-ietf-cbor, io-multiformats, io-ipld, chain, org-chainagnostic-cacao, tech-ipfs-specs-ipns） | Contents: Read（tarball）+ **Commit statuses: Read and write** |

```bash
mkdir -p -m 700 ~/.itonami/fleet-ci-gh-tokens
# GitHub の Fine-grained token 画面でコピーしたあと（shell 履歴に残さないため pbpaste 経由）
pbpaste > ~/.itonami/fleet-ci-gh-tokens/com-junkawasaki && chmod 600 ~/.itonami/fleet-ci-gh-tokens/com-junkawasaki
pbpaste > ~/.itonami/fleet-ci-gh-tokens/kotoba-lang     && chmod 600 ~/.itonami/fleet-ci-gh-tokens/kotoba-lang
# 効いているか（launchd と同じ経路を対話セッションで確認）
FLEET_CI_GH_TOKEN_DIR=~/.itonami/fleet-ci-gh-tokens nbb scripts/fleet-ci/tick.cljs --plan
```

pin を進められるのは **root の Contents: write** だけで、子 repo 側は status 以外書けない
（CI が誤って子 repo の履歴に触れない）。

`ProcessType` は **Standard**。`Background` にすると macOS の throttling で 15 分 CPU
0.09 秒しか進まない（実測）。

## 既知の限界（ADR-2607255500 の :not-done）

- **trigger は 5 分 polling**（webhook ではない）。push から最大 5 分の遅延。
- **検証するのは default branch の tip のみ。** PR head（未 merge branch）は回さない
  → status は付くが「merge 前に PR を止める」用途にはまだならない。
- GitHub Actions の `ci.yml` は **並存**（置き換えていない）。branch protection の
  required status check に `fleet-ci/murakumo/*` を指定する運用切り替えは別決定。
- **LaunchAgent の 2 本の fine-grained PAT が未発行**（発行は GitHub Web UI なので人手。
  上記手順どおり置けば次の tick から自走する。それまで LaunchAgent は毎 tick preflight で
  理由付き skip し、tick は対話セッションから走らせる）。
- ADR-2607178000 の 6 時間 pin 回帰 runner は**別マシンで動き続けている**（このマシンには
  script も plist も鍵も無い。所在は未特定 — 重複 receipt は害が無いので放置している）。
