# Hugging Face

## Hub token（投稿・アップロード用）

- **場所**: `~/.cache/huggingface/token`（plain file、huggingface_hub の token store。mode 600）
- **identity**: user `com-junkawasaki`、orgs `gftd` / `awai-network` / `com-kotobalabs`（いずれも admin）。OAuth token。
- **書込権限**: 実証済み（2026-09-13、dataset repo `com-junkawasaki/dllm-qwen38-ar-baseline` を create → upload 成功）。
- **Modal 側**: modal secret `hf-token`（アプリ `dllm-qwen38` がコンテナ内 DL 用に使用。CLI から値は読めない）。
- **読み方**: 値を出力せず `from huggingface_hub import get_token, whoami` 経由で使う
  （`whoami()` で identity 確認、`HfApi()` は token store から自動解決）。
