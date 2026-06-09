# yukkuri sample: NIST CSF v2 / CIS Controls / SCS制度

`ai.gftd.apps.yukkuri.compose` に渡す topic + outline。`generate_script` graph に
食わせると `nist-csf-cis-scs.json` 相当の `scenes[]` が得られる想定。

## topic

```
NIST CSF v2、CIS Controls、SCS制度の違いを3分で解説
```

## outline (任意 — compose に渡すと scriptwriter のヒントになる)

- 想定尺: 約3〜4分 (6 scenes)
- 視聴対象: 日本の中小企業 CISO / 情シス / 経営層
- 「SCS制度」= 経産省・IPA「サプライチェーン強化に向けたセキュリティ対策評価制度」
  (2026年度本格運用予定、★1〜★5 の評価ラベル方式) を想定
- 3つは「競合」ではなく **層が違う**: NIST CSF = 目的の地図、CIS = 手段のチェックリスト、
  SCS = 取引先に見せるラベル
- mid-video の surprising fact: SCS の質問票は NIST/CIS の項目をマッピングして作られているため、
  CIS Controls IG1 を実装している企業は SCS の★2〜★3を取りやすい
- 個人名・実在組織名のディスりは NG (critic で reject される)
- VOICEVOX clip license credit は `upload_youtube` graph が自動で description に付与

## voice mapping

| 役割 | speaker | VOICEVOX preset | style_id (normal) |
|---|---|---|---|
| LEFT (霊夢ポジ、ゆきり) | `left` | 四国めたん | 2 |
| RIGHT (魔理沙ポジ、まりり) | `right` | ずんだもん | 3 |

emotion → style_id の解決は `voicevox_client.py` の `resolve_style_id()` に委譲。

## reproduce

```bash
# (1) Helm deploy 後、yukkuri pod に compose を投げる
curl -X POST https://yukkuri.gftd.ai/xrpc/ai.gftd.apps.yukkuri.compose \
  -H 'Content-Type: application/json' \
  -d '{
    "topic": "NIST CSF v2、CIS Controls、SCS制度の違いを3分で解説",
    "outline": "<上の outline 全文>"
  }'
# → { "videoUri": "at://did:web:y5kk5r1x.gftd.ai/ai.gftd.apps.yukkuri.video/<rkey>" }

# (2) generate_script は subscribeRepos commit hook で自動発火 (status: queued → script)
# (3) DB 確認:
psql "$RW_URL" -c "
  SELECT scene_index, line_index, speaker, emotion, text
  FROM vertex_yukkuri_line
  WHERE video_id = '<rkey>'
  ORDER BY scene_index, line_index;
"
```

実 LLM 出力は確率的なので `nist-csf-cis-scs.json` は **想定例** として置く。
台本のコア構造 (6 scenes / 各 4-5 lines / RIGHT 起点・両者まとめ・surprising fact 中盤)
は維持する。
