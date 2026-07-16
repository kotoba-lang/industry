# ADR-2607125300: `cloud-itonami-isic-5820`/`6201` に Docker パッケージング + CI を追加(CI workflow push は OAuth scope 不足で一部保留)

- Status: Accepted (2026-07-12)
- 関連: ADR-2607124600、ADR-2607124800、ADR-2607125100
- Scope: `/loop` 自己ペース継続タスク「成熟度を実運用まで」の第8サイクル

## Context

前サイクルまでで両actorはHTTPサービス化・永続化・実LLMアダプタ配線
まで到達した。次の安全な一歩として、認証情報や外部調整を要しない
「Dockerパッケージング + CI」に着手した。

## Decision

1. 両repoにmulti-stage `Dockerfile`を追加: builder(`eclipse-temurin:
   21-jdk-*`)がsibling repo(`kotoba-lang/crm`/`langgraph`)をclone
   しdeps解決、runtime(`eclipse-temurin:21-jre-*`)は非rootユーザー・
   秘密情報は一切イメージに焼き込まずコンテナ環境変数のみから読む・
   `GET /health`への`HEALTHCHECK`付き。
2. **実際にdocker build/runして検証済み**(docファイルの主張ではなく
   実行結果): `curl /health`・認証あり/なしの`/dashboard`・
   `POST /propose`(5820)/`/send`等(6201)がコンテナ経由で正しく動作、
   file-store永続化がhost bind mount上に反映されることも確認。
   非rootでの実行(`uid=10001`)も確認。
3. GitHub Actions CI(`.github/workflows/ci.yml`)を作成: sibling repo
   のcheckout込みでtest/lintを実行するjobと、`docker build`のみ行う
   jobの2本。**deploy/registry push stepは含めない**(認証情報・
   インフラ判断が必要なためスコープ外、workflow内にコメントで明記)。
4. **push時に問題発覚**: `gh` CLIのOAuth tokenに`workflow` scopeが
   無く、`.github/workflows/*.yml`を含むpushをGitHubがrejectした。
   `gh auth refresh -s workflow`を試みたが、これはブラウザでの
   デバイスコード認証(人間の操作)を要求するもので、agentだけでは
   完了できない。**そのため、CI workflowファイルをpushから一旦除外し、
   Dockerfile・ドキュメント等それ以外の変更だけを先にmainへ着地させた**。
   workflowファイル自体は各作業ディレクトリにファイルとして温存して
   あり、オーナーがscope付与すれば追加pushできる状態。

## Consequences

- (+) 両actorが実際にビルド・起動・検証済みのコンテナイメージを
  持つに至った——「デプロイ可能」に一歩前進。
- (+) 秘密情報のイメージ焼き込みなし、非root実行——セキュリティ面でも
  健全。
- (-) GitHub Actions CIはファイルとして完成しているが、mainには
  まだ着地していない(オーナーのOAuth scope付与待ち)。
- (-) 実クラウドへのデプロイ(レジストリpush・実インフラでの起動)は
  依然未達——本サイクルはコンテナイメージの構築・検証までで、
  「本当に外部到達可能な状態で動いている」段階には至っていない。
- (-) 6202は引き続き対象外。

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| workflow fileを含む一括commitのpushを諦め全体を保留 | ❌ | Dockerfile等それ以外の検証済み価値ある変更まで止める理由が無い。workflow fileだけ除外して先に着地させる方が合理的 |
| `--force`や別の権限バイパス手段を試す | ❌ | 認証情報の投入・スコープ昇格は人間の判断・操作を要する領域。バイパスを試みない |

## References

- ADR-2607124600、ADR-2607124800、ADR-2607125100
- `cloud-itonami-isic-5820`/`cloud-itonami-isic-6201`の`Dockerfile`・
  `.github/workflows/ci.yml`(後者は未push、follow-up待ち)

## Verification Notes

- `cloud-itonami-isic-5820`: commit `3a3f168`(Dockerfile等、push済み)。
  実docker build/run検証済み(`/health`・`/propose`・`/dashboard`・
  file-store永続化・非root実行を確認)。
- `cloud-itonami-isic-6201`: commit `1f3e555`(Dockerfile等、push済み)。
  同様に実検証済み。
- 両repoともCI workflowファイルはローカルに温存、`workflow` scope
  付与後に別途push予定。
