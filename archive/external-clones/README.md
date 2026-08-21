# external-clones — 旧ローカル clone の固有差分キャプチャ (ADR-0007 cleanup)

`/Users/junkawasaki/gftdcojp` と `/Users/junkawasaki/github` に残っていた旧 clone は、
すべて com-junkawasaki 配下（submodule または vendored）に配置済みだったため削除した。
削除前に、各 clone にしか無かった **未push commit / 未コミット変更（dirty）** を
git bundle として本ディレクトリに退避した（2026-06-09）。

各 clone は削除直前に `git add -A && git commit -m "capture: WIP before archival"` で
dirty を WIP コミット化し、その上で bundle 化している。

| bundle | 種別 | 元 clone | remote | 回収した固有差分 |
|---|---|---|---|---|
| `archive_ai-gftd-lf-case-lingling.delta.bundle` | delta | gftdcojp/archive_ai-gftd-lf-case-lingling | com-junkawasaki/ai-gftd-lf-case-lingling | dirty 2 |
| `etzhayyim-root.delta.bundle` | delta | gftdcojp/etzhayyim-root | etzhayyim/root | dirty 16 |
| `260208-spirit-in-physics.delta.bundle` | delta | github/260208-spirit-in-physics | com-junkawasaki/260208-spirit-in-physics | dirty 3 |
| `spirit-in-physics.delta.bundle` | delta | github/spirit-in-physics | com-junkawasaki/spirit-in-physics | unpushed 9 commits |
| `mangaka-ghosthacker-assets.full.bundle` | full | gftdcojp/mangaka-ghosthacker-assets | （なし） | unpushed 32 + dirty 4（全履歴。annex 実体は B2 既出） |
| `yukkuri-assets-nist-csf-cis-scs.full.bundle` | full | gftdcojp/yukkuri-assets-nist-csf-cis-scs | （なし） | unpushed 7 + dirty 1（全履歴。annex 実体は B2 既出） |

- **delta** = `git bundle create --all --not --remotes`。履歴本体は remote(GitHub) に在る前提で、
  未push commit と WIP(dirty) コミットだけを含む。復元には remote からの clone が必要。
- **full** = `git bundle create --all`。remote が無いため全履歴を含む（git 履歴のみ。
  git-annex 実体は本体 `com-junkawasaki-annex` バケットに既出のため bundle には入らない）。

## 復元方法

### delta バンドル（remote 有り）
```bash
git clone <remote-url> restored && cd restored
git bundle verify /path/to/<name>.delta.bundle      # 前提 commit が満たされるか確認
git fetch /path/to/<name>.delta.bundle '*:refs/capture/*'
git log --all --oneline | head                       # WIP/未push commit を確認
```

### full バンドル（remote 無し）
```bash
git clone /path/to/<name>.full.bundle restored
cd restored && git log --oneline
# ファイル実体（annex）は com-junkawasaki の vendored コピー or B2 から取得済み
```

## メモ
- これらは「念のため」の退避。実ファイル内容は既に com-junkawasaki（submodule gitlink /
  vendored 実体 + Backblaze B2）に存在する。
- `webmaster` は dirty 0 / unpushed 0 の完全重複だったため bundle 不要で削除。
