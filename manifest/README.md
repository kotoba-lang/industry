# west manifest 運用

この superproject の submodule 群を [west](https://docs.zephyrproject.org/latest/develop/west/)
の manifest で管理する。`west.yml` は **手書きせず** `scripts/gen-west-manifest.bb`
で生成する（各 project の working tree HEAD をピン → pin 前進に自動追従）。

## 現状（移行ステータス）

- **31 リポを west 管理**（`manifest/west.yml`）。gitlink は撤去済み・`.gitignore`
  で superproject の追跡から外した（実体は west が管理）。
- **3 リポは git submodule のまま温存**（`.gitmodules` に残置）。ローカル未コミット/
  未push の作業があり、west 化すると消失リスクがあるため reconcile 待ち:
  - `orgs/etzhayyim/root` — ローカル分岐 + dirty 多数・未push
  - `orgs/gftdcojp/network-isekai` — pin より behind + dirty（未コミット作業）
  - `orgs/gftdcojp/m365-archive` — DataLad dataset（B2/git-annex で別管理）

## トポロジ

```
<superproject root>/        ← west topdir（.west/ がここに作られる）
├── .west/config            ← west init で生成（git 管理外）
├── manifest/
│   ├── west.yml            ← 生成物（self.path: manifest）
│   └── README.md           ← これ
├── .gitmodules            ← 残り 3 submodule のみ
└── orgs/<org>/<repo>/      ← project 展開先（= 旧 submodule と同一パス）
```

- topdir は「manifest ディレクトリの親」として導出されるため、チェックアウトの
  ディレクトリ名（`com-junkawasaki` 等）に依存しない。
- project の `path:` は旧 submodule と同一なので、ツール・CI のパス前提を変えずに済む。
- 全 project に `clone-depth: 1`（CLAUDE.md の shallow 既定に準拠）。
- ネスト submodule を持つリポ（`ghosthacker` / `kami-engine` /
  `ai-gftd-apps-gftdcojp`）は `submodules: true` で git の再帰取得に委ねる。

## 初回セットアップ

```bash
west init -l manifest                 # .west/ を作る（非破壊）
# zsh は引用なし変数を単語分割しないので複数 project 指定は xargs 推奨:
west list -f '{name}' | grep -v '^manifest$' | xargs west update --fetch smart
```

## 日常運用

```bash
west update --fetch smart             # 取得/同期（submodule update --recursive の置換）
west list                             # 一覧
west status                           # 状態
bb scripts/gen-west-manifest.bb       # pin を進めたら manifest 再生成（手書き禁止）
bb scripts/gen-west-manifest.bb --check   # CI: working HEAD と乖離なら exit 1
```

> west 1.5 の `west update` は `-j` 非対応（直列）。fetch は `smart` で差分のみ。

## 残り 3 submodule を west へ取り込む手順

owner が reconcile（ローカル作業を commit & push）したのち:

```bash
# 1) 当該 submodule をクリーンに（dirty 解消・push、pin を確定）
# 2) scripts/gen-west-manifest.bb の def exclude-paths から該当パスを削除
# 3) .gitmodules から撤去し gitlink を外す
git config -f .gitmodules --remove-section 'submodule.orgs/etzhayyim/root'
git add .gitmodules && git rm --cached orgs/etzhayyim/root
printf '/orgs/etzhayyim/root/\n' >> .gitignore
# 4) 再生成 → west update → commit
bb scripts/gen-west-manifest.bb
west update --fetch smart root
git add manifest/west.yml .gitignore && git commit
```
