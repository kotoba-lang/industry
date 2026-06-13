//! 実LLM推論エンジン: ローカル Ollama の gemma4 (e4b) に接続する。
//!
//! gemma4 は thinking モデルのため、OpenAI互換 `/v1/chat/completions` だと
//! 思考が `reasoning` に流れ `content` が空になりがち。Ollama ネイティブ
//! `/api/chat` の `think:false` で思考を切り、短い結論だけを得る。

use std::sync::Arc;
use std::time::Duration;

use kotoba_runtime::host::InferenceFn;
use serde_json::json;

/// 既定のエンドポイントとモデル (ローカル Ollama の gemma4 e4b)。
pub fn url() -> String {
    std::env::var("KOTOBA_INFERENCE_URL").unwrap_or_else(|_| "http://localhost:11434".into())
}
pub fn model() -> String {
    std::env::var("KOTOBA_INFERENCE_MODEL").unwrap_or_else(|_| "gemma4:latest".into())
}

/// Ollama が起動していて対象モデルがあるか確認する。
pub fn reachable() -> bool {
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_millis(800))
        .timeout_read(Duration::from_secs(3))
        .build();
    matches!(agent.get(&format!("{}/api/tags", url())).call(), Ok(_))
}

/// LLM出力を提案カード用に整形する: Markdown装飾・前置きラベルを除去し1段落化。
fn sanitize(raw: &str) -> String {
    let mut s = raw.replace("**", "").replace('*', "").replace('#', "");
    // 「提案アクション：」等の前置きラベル行を落とし、本文だけ残す
    if let Some(idx) = s.find('\n') {
        let head = s[..idx].trim();
        if head.ends_with('：') || head.ends_with(':') || head.chars().count() <= 12 {
            s = s[idx + 1..].to_string();
        }
    }
    // 改行を読点でつなぎ1段落に
    let joined: Vec<&str> = s.lines().map(|l| l.trim()).filter(|l| !l.is_empty()).collect();
    joined.join(" ").trim().to_string()
}

/// gemma4 (think:false) を呼ぶ InferenceFn を作る。
pub fn make_infer_fn() -> InferenceFn {
    let url = url();
    let model = model();
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(2))
        .timeout_read(Duration::from_secs(180))
        .build();
    Arc::new(move |prompt: &str, max: usize| {
        let num_predict = if max == 0 { 200 } else { max.clamp(80, 512) } as i64;
        let body = json!({
            "model": model,
            "messages": [{ "role": "user", "content": prompt }],
            "think": false,
            "stream": false,
            "options": { "num_predict": num_predict, "temperature": 0.7 }
        });
        let resp = agent
            .post(&format!("{url}/api/chat"))
            .send_json(body)
            .map_err(|e| anyhow::anyhow!("ollama request: {e}"))?;
        let v: serde_json::Value = resp
            .into_json()
            .map_err(|e| anyhow::anyhow!("ollama decode: {e}"))?;
        let raw = v
            .get("message")
            .and_then(|m| m.get("content"))
            .and_then(|c| c.as_str())
            .unwrap_or("")
            .trim();
        if raw.is_empty() {
            anyhow::bail!("empty content from model");
        }
        Ok(sanitize(raw))
    })
}
