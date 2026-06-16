//! 実LLM推論エンジン: OpenRouter にルーティングする (役割ごとにモデルを使い分け)。
//!
//! `llm-infer` はモデル名を InferenceFn に渡さないため、各 clj エージェントは
//! プロンプト先頭に `[[M:<model>]]` タグを付ける。本エンジンはそれを読んで
//! OpenRouter の該当モデルへ振り分ける(タグが無ければ既定の高速モデル)。
//! reasoning モデル(minimax/kimi/qwen)は reasoning:{effort} を付け、content が空なら
//! reasoning をフォールバックに使う。出力は提案カード用に整形する。

use std::sync::Arc;
use std::time::Duration;

use kotoba_runtime::host::InferenceFn;
use serde_json::json;

const DEFAULT_MODEL: &str = "google/gemma-3n-e4b-it";

/// OpenRouter API キー (環境変数 → Keychain フォールバック)。
pub fn api_key() -> Option<String> {
    if let Ok(k) = std::env::var("OPENROUTER_API_KEY") {
        if !k.is_empty() {
            return Some(k);
        }
    }
    std::process::Command::new("security")
        .args(["find-generic-password", "-s", "gftd.openrouter", "-w"])
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

/// 推論が利用可能か (OpenRouter キーがあるか)。
pub fn reachable() -> bool {
    api_key().is_some()
}

pub fn model() -> String {
    DEFAULT_MODEL.to_string()
}
pub fn url() -> String {
    "https://openrouter.ai".to_string()
}

/// 先頭の `[[M:<model>]]` タグを剥がして (model, 本文) を返す。
fn parse_model_tag(prompt: &str) -> (String, String) {
    if let Some(rest) = prompt.strip_prefix("[[M:") {
        if let Some(end) = rest.find("]]") {
            let model = rest[..end].trim().to_string();
            let body = rest[end + 2..].trim_start().to_string();
            return (model, body);
        }
    }
    (DEFAULT_MODEL.to_string(), prompt.to_string())
}

fn is_reasoning(model: &str) -> bool {
    let m = model.to_lowercase();
    m.contains("minimax") || m.contains("kimi") || m.contains("qwen") || m.contains("thinking")
}

/// 出力を提案カード用に整形 (Markdown装飾・前置きラベル除去、1段落化)。
fn sanitize(raw: &str) -> String {
    let mut s = raw.replace("**", "").replace('#', "").replace("*", "");
    if let Some(idx) = s.find('\n') {
        let head = s[..idx].trim();
        if head.ends_with('：') || head.ends_with(':') || head.chars().count() <= 14 {
            s = s[idx + 1..].to_string();
        }
    }
    let joined: Vec<&str> = s.lines().map(|l| l.trim()).filter(|l| !l.is_empty()).collect();
    joined.join(" ").trim().to_string()
}

/// OpenRouter ルーティングの InferenceFn を作る (役割別モデル使い分け)。
pub fn make_infer_fn() -> InferenceFn {
    let key = api_key().unwrap_or_default();
    let agent = ureq::AgentBuilder::new()
        .timeout_connect(Duration::from_secs(3))
        .timeout_read(Duration::from_secs(300))
        .build();
    Arc::new(move |prompt: &str, _max: usize| {
        let (model, body) = parse_model_tag(prompt);
        // reasoning モデルは思考でトークンを消費するため、最終 content を出せるよう
        // 大きめに固定する(ランタイムが渡す max は小さいので無視)。
        let reasoning = is_reasoning(&model);
        let max_tokens: i64 = if reasoning { 8000 } else { 3000 };
        let mut req = json!({
            "model": model,
            "messages": [{ "role": "user", "content": body }],
            "max_tokens": max_tokens,
            "temperature": 0.7
        });
        if reasoning {
            // 速度と質のバランス: 経営者(minimax)は high、社員は medium
            let effort = if model.contains("minimax") { "high" } else { "medium" };
            req["reasoning"] = json!({ "effort": effort });
        }
        let resp = agent
            .post("https://openrouter.ai/api/v1/chat/completions")
            .set("Authorization", &format!("Bearer {key}"))
            .set("Content-Type", "application/json")
            .send_json(req)
            .map_err(|e| anyhow::anyhow!("openrouter request ({model}): {e}"))?;
        let v: serde_json::Value = resp.into_json().map_err(|e| anyhow::anyhow!("openrouter decode: {e}"))?;
        let msg = &v["choices"][0]["message"];
        let content = msg["content"].as_str().unwrap_or("").trim();
        let raw = if content.is_empty() {
            msg["reasoning"].as_str().unwrap_or("").trim()
        } else {
            content
        };
        if raw.is_empty() {
            anyhow::bail!("empty content from {model}");
        }
        Ok(sanitize(raw))
    })
}
