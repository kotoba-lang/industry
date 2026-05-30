# ADR-0003: Personal Data Warehouse — DataLad + git-annex + Encrypted IPFS

- Status: Accepted
- Date: 2026-05-29
- Deciders: Jun Kawasaki
- Implementation: `personal/` (this repo)
- Related: ADR-0001 (top-level orchestrator / sensors), ADR-0002 (pregel DAG)

## 1. Context

ADR-0001 で `com-junkawasaki` を最上位 orchestrator と定め、その入力として
**Sensors (read-only)**: Gmail / Calendar / Drive / git / 端末 等から KPI を収集する
と宣言した。しかし収集した個人データの **保管・暗号化・分析整理の基盤** は未定義
だった (ADR-0001 §4.3 で「シークレットを Living System が持つ必要」とだけ言及)。

要件:

1. **この端末の情報** (OS / ハードウェア / インストール済みパッケージ / dotfiles) と
   **アカウント系データ** (Gmail / Calendar / Drive / GitHub) を一箇所に ingest する。
2. 分析できるよう構造化して整理する (Pregel sensor の供給源)。
3. データを **datalad** で版管理し、**IPFS remote pin** で内容アドレス保管する。
4. リポジトリは GitHub private (`com-junkawasaki/com-junkawasaki`) に push される。

制約: IPFS は内容アドレス型で、CID を知る者は誰でも・永久に取得できる。
個人データ (金融・メール・アカウント) を平文で pin することは恒久的な漏洩に等しい。

## 2. Decision

`personal/` を **暗号化個人データウェアハウス** として構築する。

1. **DataLad dataset**: この repo を `datalad create --force` で dataset 化。
   `origin` (GitHub) は `annex-ignore` とし、annex 実体は GitHub に送らない。
2. **git-annex 管理**: `personal/**` のデータは annex (`annex.largefiles=anything`)。
   git にはポインタ (symlink) のみ → **GitHub に平文が乗らない**。
   スクリプト/README/ADR は git 平文 (PII 無し)。
3. **暗号 = gpg + git-annex `encryption=hybrid`**:
   - 鍵 `09EE841334482F5A0F5C4958A70BB2C220DE88CA` (暗号副鍵 `D1CA341CF2327694`)。
   - パスフレーズは **macOS Keychain** 項目 `gpg:personal-data` に保管 (Touch ID 保護)。
   - 非対話運用: `personal/bin/gpg-unlock.sh` (Keychain → gpg-agent preset)。
   - **復旧鍵 = 1Password** (Private vault, armored 秘密鍵 + パスフレーズ)。ディスク .asc は安全消去。
4. **IPFS special remote (external, 自作)**: `personal/bin/git-annex-remote-ipfs`。
   git-annex が `hybrid` で **暗号化した後** の暗号文だけを `ipfs add --pin`。
   key→CID は git-annex branch の SETSTATE で版管理。**平文は IPFS に出ない**。
5. **取り込み範囲 (合意)**: 端末情報 / Gmail / Calendar / Drive / アカウント情報。
   - 秘匿値は **収集しない**: 環境変数は名前のみ、GitHub トークン値は非保存。

### Layout

```
personal/
├─ bin/{git-annex-remote-ipfs, ingest-device.sh, gpg-unlock.sh}   # git 平文
├─ device/    OS / hardware / packages / dotfiles / disk / env-names
├─ mail/      Gmail labels(107) + recent-activity
├─ calendar/  Google Calendar events
├─ drive/     Drive file inventory (metadata only)
├─ accounts/  GitHub user/orgs/repos/ssh-keys/scopes (no token value)
└─ analysis/  observations.md
```

### Data flow

```
sensors (Gmail/Calendar/Drive/gh/device)
        │  ingest (MCP / gh / scripts)
        ▼
   personal/<domain>/  ── git-annex add ──▶  annex object (plaintext, local disk)
        │                                          │
   git (pointer only) ──▶ GitHub private           │ git annex copy --to ipfs
                                                    ▼
                                       gpg hybrid encrypt → ipfs add --pin
                                                    ▼
                                          IPFS (ciphertext only, by CID)
```

## 3. Rationale

- **責務の分離**: git = 構造/履歴/ポインタ、annex = 実体、IPFS = 内容アドレス保管、
  gpg = 機密性。各層が単一責務。
- **GitHub に平文を出さない**: annex-ignore + largefiles=anything で構造的に保証。
- **IPFS の恒久公開リスクを暗号で無力化**: pin されるのは gpg 暗号文 (OpenPGP packet)
  のみ。検証で平文リーク 0 を確認。
- **ADR-0001 sensor 層の実体化**: Pregel の sensor 供給を再現可能なデータセットにした。
- **datalad 採用理由**: git-annex の上に provenance/run-record を載せられ、将来 sensor
  パイプラインを `datalad run` で再現可能化できる。

## 4. Consequences

### 4.1 Positive

- 個人データが版管理・内容アドレス・暗号化された単一 SSoT に集約。
- GitHub private へ push しても **平文は流出しない** (annex ポインタのみ)。
- IPFS CID が漏れても **強パスフレーズ付き gpg 鍵宛の暗号文**しか得られない。
- 分析層 (`analysis/`) が orchestrator の意思決定入力になる。

### 4.2 Negative / Risks

- ローカル annex オブジェクト (`.git/annex/objects`) は **平文** → FileVault 前提。
- 鍵 custody が Apple/Keychain に依存。Apple ID 事故時はオフライン復旧鍵のみが頼り。
- IPFS pin は **取消不能** (unpin してもネットワーク上に残存しうる)。pin は明示操作時のみ。
- gpg 秘密鍵紛失 = 全データ復号不能 → オフライン復旧鍵の保管が必須。

### 4.3 Mitigations

- annex 平文 → **FileVault** + 鍵パスフレーズ。
- 鍵紛失 → **1Password** (Private vault) に復旧鍵 + パスフレーズを保管 (ADR-0001 §4.3 の
  「1Password Gftd Japan vault」方針に整合。復旧手順は README に明記)。
- 取消不能 pin → ingest 時に秘匿値を除外 (env 名のみ / token 非保存) し、pin 前に内容を確認。
- 多端末同期 → iCloud Keychain or 復旧鍵共有 (将来 ADR で再検討)。

## 5. Implementation status

- ✅ datalad dataset 化 + `personal/` 構造 + annex ルール
- ✅ gpg 鍵生成 → パスフレーズ付与 → Keychain 保管 → 復旧鍵を 1Password (Private) に保管
- ✅ 暗号化 IPFS external special remote (自作) + 往復・暗号文検証
- ✅ ingest: device / Gmail / Calendar / Drive / GitHub
- ✅ 全 23 ファイルを暗号化して IPFS に pin (平文リーク 0 を検証)
- ⏳ ingest の `datalad run` 化 (provenance 記録)
- ⏳ Pregel sensor (`reverse_topo_pregel.py`) から `personal/` を直接参照
- ⏳ iCloud Keychain 多端末同期の正式化

## 6. References

- `personal/README.md` — 運用手順 (ingest / 暗号化 / pin / 復号 / 復旧)
- `personal/analysis/observations.md` — 初期分析
- ADR-0001 — sensor 層を定義した上位 ADR
- git-annex external special remote protocol / DataLad handbook
