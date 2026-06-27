# west manifest 運用

この superproject の子リポは全て [west](https://docs.zephyrproject.org/latest/develop/west/)
の manifest で管理する。**plain な git submodule は廃止**（`.gitmodules` は無い）。

- **source of truth = `manifest/repos.edn`**（ポリシー: remote / 既定 / group-filter /
  DataLad / B2）。`manifest/west.yml` は **手書きせず** `scripts/gen-west-manifest.bb`
  が EDN + git の事実（各 working tree HEAD）から生成する。
- **34 project を west 管理**。うち **33 は通常の git repo**、**1 は DataLad dataset**
  （`m365-archive`、git-annex + B2）。

## トポロジ

```
<superproject root>/        ← west topdir（.west/ がここに作られる）
├── .west/config            ← west init で生成（git 管理外）
├── manifest/
│   ├── repos.edn           ← ★ source of truth（人が編集する）
│   ├── west.yml            ← 生成物（self.path: manifest）
│   ├── west-commands.yml   ← west 拡張コマンド登録
│   ├── west_annex.py       ← `west annex-get/annex-drop`（DataLad/B2 統合）
│   └── README.md           ← これ
└── orgs/<org>/<repo>/      ← project 展開先（= 旧 submodule と同一パス）
```

- topdir は「manifest ディレクトリの親」として導出されるため、チェックアウトの
  ディレクトリ名に依存しない。
- project の `path:` は旧 submodule と同一なので、ツール・CI のパス前提を変えずに済む。
- 全 project に `clone-depth: 1`（CLAUDE.md の shallow 既定）。
- ネスト submodule を持つ repo（`ghosthacker` / `kami-engine` / `root` /
  `ai-gftd-apps-gftdcojp`）は `submodules: true` で git の再帰取得に委ねる。

## DataLad 連携（m365-archive）

DataLad dataset は west project にしつつ、大容量の実体は git-annex 経由で
Backblaze B2 に置く。git には annex キー（ポインタ）だけが入る。

- `manifest/repos.edn` の `:datalad` に登録 → west.yml で `userdata.datalad: true` と
  `datalad` グループ（既定 `group-filter` の `-datalad` で **off**）が付く。
- そのため通常の `west update` では取得されない（重い annex を引かない）。明示取得:

```bash
# git/annex スケルトンを取得（opt-in グループ）
west update --group-filter +datalad m365-archive
# 実体を B2 から取得 / 破棄
west annex-get         # 認証は自動解決（下記）。実体を B2 から取得
west annex-drop        # ローカル実体を捨てて B2 のコピーだけ残す
```

### B2 認証の解決（env → 1Password → Keychain）

`west annex-get/annex-drop` は `scripts/b2-creds.bb` で B2 認証を解決する。順序と
参照先は `manifest/repos.edn` の `:b2 :credentials`（既定
`[:env :1password :keychain]`）。**秘密はリポジトリに置かず**、参照先（`op://` パス /
Keychain service 名）だけを EDN に書く。初回は自分の保管先に合わせて `★` を編集する。

```bash
# 1Password: op に signin 済みなら op read で解決
# Apple Keychain: security find-generic-password で解決（macOS ローカル）
# CI 等: B2_KEY_ID / B2_APP_KEY / B2_BUCKET を環境変数で渡せば env が最優先
eval "$(bb scripts/b2-creds.bb)"     # 手元の環境に流し込む（任意）
bb scripts/b2-creds.bb --json        # プログラム用（west_annex.py が利用）
```

## 日常運用

```bash
west init -l manifest                                   # 初回（非破壊）
west list -f '{name}' | grep -v '^manifest$' | xargs west update --fetch smart
west list ; west status
bb scripts/gen-west-manifest.bb                         # pin 前進後に再生成（手書き禁止）
bb scripts/gen-west-manifest.bb --check                 # CI: 乖離で exit 1
```

> west 1.5 の `west update` は `-j` 非対応（直列）。fetch は `smart` で差分のみ。
> zsh は引用なし変数を単語分割しないため、複数 project 指定は `xargs` を使う。

## 新しいリポを追加するには

1. その repo を作って push（origin に存在させる）。
2. ローカルに `orgs/<org>/<repo>` として clone（または `west` で取得）。
3. `bb scripts/gen-west-manifest.bb` で再生成（working HEAD を pin）。
4. `.gitignore` に `/orgs/<org>/<repo>/` を追加し、`manifest/west.yml` をコミット。

remote(org) が新規なら `manifest/repos.edn` の `:remotes` に追記する。
DataLad dataset なら `:datalad` に登録する。
