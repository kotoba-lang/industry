//! 社員エージェント: kotoba-clj で WASM 化し、WasmExecutor + 実LLM で実行する。

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{Context, Result};
use kotoba_runtime::host::{InferenceFn, WitQuad};
use kotoba_runtime::WasmExecutor;

use crate::model::{role_label, Kpis};

pub const GAS: u64 = 10_000_000;

/// 役割 → cljソースファイル名。
pub const AGENTS: &[(&str, &str)] = &[
    ("sales", "sales.clj"),
    ("eng", "engineering.clj"),
    ("finance", "finance.clj"),
    ("ceo", "ceo_advisor.clj"),
];

fn wit_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../etzhayyim/kotoba/crates/kotoba-runtime/wit")
        .canonicalize()
        .expect("kotoba-runtime/wit must exist")
}

fn agents_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("agents")
}

/// 1 つの clj 社員を kotoba-node コンポーネントにコンパイルする (prelude 前置)。
fn compile_one(file: &str) -> Result<Vec<u8>> {
    let path = agents_dir().join(file);
    let body = std::fs::read_to_string(&path).with_context(|| format!("read {path:?}"))?;
    let src = format!("{}\n{}", kotoba_clj::prelude(), body);
    let wit = wit_dir();
    let bytes = kotoba_clj::component::compile_kais_component_str(&src, wit.to_str().unwrap())
        .map_err(|e| anyhow::anyhow!("compile {file}: {e}"))?;
    kotoba_clj::component::assert_loads(&bytes).map_err(|e| anyhow::anyhow!("load {file}: {e}"))?;
    Ok(bytes)
}

/// 全社員をコンパイルしてバイト列をキャッシュする。
pub fn compile_all() -> Result<HashMap<String, Vec<u8>>> {
    let mut out = HashMap::new();
    for (role, file) in AGENTS {
        let bytes = compile_one(file)?;
        tracing::info!("compiled agent {role} ({file}) -> {} bytes", bytes.len());
        out.insert(role.to_string(), bytes);
    }
    Ok(out)
}

/// 推論エンジンを作る。ローカル Ollama (gemma4 e4b) が起動していれば実LLM、
/// 届かなければスタブにフォールバックする。`GFTD_SIM_STUB=1` で強制スタブ。
pub fn make_executor() -> Result<(WasmExecutor, bool)> {
    let force_stub = std::env::var("GFTD_SIM_STUB").is_ok();
    if !force_stub && crate::infer::reachable() {
        tracing::info!("connecting real LLM: {} @ {}", crate::infer::model(), crate::infer::url());
        let f = crate::infer::make_infer_fn();
        return Ok((WasmExecutor::with_inference(GAS, f)?, true));
    }
    let f: InferenceFn = Arc::new(|prompt: &str, _max: usize| {
        let head: String = prompt.lines().last().unwrap_or("").chars().take(40).collect();
        Ok(format!("（スタブ提案: 「{head}…」を踏まえた具体策を実行する）"))
    });
    Ok((WasmExecutor::with_inference(GAS, f)?, false))
}

/// 社員briefに渡す世界状態 (実データ込み)。spawn_blocking 越しに渡せるよう owned。
#[derive(Clone)]
pub struct World {
    pub kpis: Kpis,
    /// 実契約由来の商談 (相手先名, 金額)
    pub pipeline: Vec<(String, i64)>,
    pub projects: Vec<(String, String)>,
    /// 直近(2026)の実会議/inbound
    pub recent_activity: Vec<String>,
    /// gftd 発行請求累計 (実売上)
    pub issued_total_jpy: i64,
    /// gftd 受領請求累計 (実コスト)
    pub received_total_jpy: i64,
    /// intel: 潜在リード(接触量上位org) / 再生候補プロジェクト
    pub latent_leads: Vec<String>,
    pub revival: Vec<String>,
}

/// 役割ごとの現状況 brief を組み立てる (LLM プロンプトに渡す)。実データを注入する。
pub fn build_brief(role: &str, w: &World) -> String {
    let k = &w.kpis;
    let oku = |v: i64| format!("{:.2}億円", v as f64 / 100_000_000.0);
    let man = |v: i64| format!("{}万円", v / 10_000);
    let common = format!(
        "ターン{}(四半期) / 現金残高{} / 月次バーン{} / ランウェイ{:.1}ヶ月 / 社内人員{}名 / 士気{} / 累計売上{} / パイプライン{}",
        k.turn,
        oku(k.cash_jpy),
        man(k.burn_jpy),
        k.runway_months,
        k.headcount,
        k.morale,
        oku(k.revenue_total_jpy),
        oku(k.pipeline_jpy),
    );
    // 直近の実活動 (会議/inbound) を最大5件
    let activity: Vec<String> = w.recent_activity.iter().take(5).cloned().collect();
    let activity_block = if activity.is_empty() {
        String::new()
    } else {
        format!("\n直近の実活動(gftd実データ):\n  {}", activity.join("\n  "))
    };

    match role {
        "sales" => {
            let top: Vec<String> = w
                .pipeline
                .iter()
                .take(6)
                .map(|(d, v)| format!("{}({})", d, man(*v)))
                .collect();
            let leads = w.latent_leads.iter().take(6).cloned().collect::<Vec<_>>().join(", ");
            format!(
                "{common}\n主要商談先(実契約): {}\n潜在リード(接触多・未契約): {}{activity_block}",
                top.join(", "),
                leads
            )
        }
        "eng" => {
            let pj: Vec<String> = w
                .projects
                .iter()
                .take(6)
                .map(|(n, s)| format!("{n}[{s}]"))
                .collect();
            let rev = w.revival.iter().take(5).cloned().collect::<Vec<_>>().join(", ");
            format!(
                "{common}\n主要プロジェクト(実データ): {}\n再生候補(休眠・資産大): {}",
                pj.join(", "),
                rev
            )
        }
        "finance" => format!(
            "{common}\n実財務(累計): 発行請求(売上){} / 受領請求(コスト){}",
            oku(w.issued_total_jpy),
            oku(w.received_total_jpy)
        ),
        "ceo" => format!(
            "{common}\n商談数{} / プロジェクト数{} / 実売上累計{} / 実コスト累計{}{activity_block}",
            w.pipeline.len(),
            w.projects.len(),
            oku(w.issued_total_jpy),
            oku(w.received_total_jpy)
        ),
        _ => common,
    }
}

/// 1 社員を実行し、(提案文, assert_quads) を返す。同期(spawn_blocking から呼ぶ)。
pub fn run_agent(
    exec: &WasmExecutor,
    wasm: &[u8],
    role: &str,
    brief: &str,
    turn: u64,
) -> Result<String> {
    let ctx = encode_ctx(brief, role, turn);
    let snapshot: Vec<WitQuad> = Vec::new();
    let res = exec
        .execute(role, wasm, "did:key:z6MkGftdSim", ctx, snapshot, HashMap::new())
        .map_err(|e| anyhow::anyhow!("execute {role}: {e}"))?;
    let action = decode_ok(&res.output_cbor);
    Ok(action)
}

fn encode_ctx(brief: &str, role: &str, turn: u64) -> Vec<u8> {
    #[derive(serde::Serialize)]
    struct Ctx<'a> {
        brief: &'a str,
        role: &'a str,
        turn: u64,
    }
    let mut buf = Vec::new();
    ciborium::into_writer(&Ctx { brief, role, turn }, &mut buf).expect("cbor encode ctx");
    buf
}

fn decode_ok(bytes: &[u8]) -> String {
    let val: ciborium::value::Value = match ciborium::from_reader(bytes) {
        Ok(v) => v,
        Err(_) => return String::from_utf8_lossy(bytes).into_owned(),
    };
    if let ciborium::value::Value::Map(entries) = val {
        for (kk, vv) in entries {
            if matches!(&kk, ciborium::value::Value::Text(t) if t == "ok") {
                if let ciborium::value::Value::Text(t) = vv {
                    let trimmed = t.trim();
                    if trimmed.is_empty() {
                        return "（提案を生成できませんでした）".into();
                    }
                    return trimmed.to_string();
                }
            }
        }
    }
    "（提案を生成できませんでした）".into()
}

/// 全社員を 1 ターン分実行し、(role, action) のリストを返す。
pub fn run_all(
    exec: &WasmExecutor,
    agents: &HashMap<String, Vec<u8>>,
    w: &World,
) -> Vec<(String, String)> {
    AGENTS
        .iter()
        .filter_map(|(role, _)| {
            let wasm = agents.get(*role)?;
            let brief = build_brief(role, w);
            let action = match run_agent(exec, wasm, role, &brief, w.kpis.turn as u64) {
                Ok(a) => a,
                Err(e) => {
                    tracing::warn!("agent {role} failed: {e}");
                    format!("（{}の提案生成に失敗: {e}）", role_label(role))
                }
            };
            Some((role.to_string(), action))
        })
        .collect()
}
