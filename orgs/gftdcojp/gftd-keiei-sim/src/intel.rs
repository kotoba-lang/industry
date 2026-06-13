//! インテリジェンス層 — kotoba-datomic を本当のエンジンにする。
//!
//! 分析ロジック(確度スコアリング/離反・更新リスク/依存導出)は Clojure(babashka)
//! 側 `analysis/intel.bb` に置く。この Rust モジュールは:
//!   - bb を実行して出力された intel EDN を datomic に transact する (build)
//!   - KPI を datomic クエリから導出する (kpis_from_datomic)
//!   - intel を Datalog で読み返してダッシュボード/brief に渡す (views / *_names)
//!   - ターンごとに intel を前進させる (advance)
//! だけを担う。スコアの計算式は一切 Rust に持たない。

use std::path::PathBuf;
use std::process::Command;

use anyhow::{Context, Result};
use kotoba_datomic::{q, Connection};
use kotoba_edn::{parse, EdnValue};

fn manifest() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}
fn facts_dir() -> PathBuf {
    manifest().join("../m365-archive/facts")
}
fn script() -> PathBuf {
    manifest().join("analysis/intel.bb")
}

/// babashka 実行ファイルを探す (PATH に無くても動くよう既知の場所も試す)。
fn bb_bin() -> String {
    for cand in ["bb", "/opt/homebrew/bin/bb"] {
        if Command::new(cand).arg("--version").output().is_ok() {
            return cand.to_string();
        }
    }
    if let Ok(home) = std::env::var("HOME") {
        let p = format!("{home}/.local/bin/bb");
        if Command::new(&p).arg("--version").output().is_ok() {
            return p;
        }
    }
    "bb".to_string()
}

/// `analysis/intel.bb` (Clojure) を実行して intel を導出し、datomic に書き込む。
pub async fn build(conn: &Connection) -> Result<usize> {
    let facts = facts_dir().canonicalize().context("facts dir")?;
    let out = Command::new(bb_bin())
        .arg(script())
        .arg(&facts)
        .output()
        .context("run analysis/intel.bb (babashka)")?;
    if !out.status.success() {
        anyhow::bail!("intel.bb failed: {}", String::from_utf8_lossy(&out.stderr));
    }
    let edn = String::from_utf8(out.stdout).context("intel.bb stdout utf8")?;
    let tx = parse(edn.trim()).map_err(|e| anyhow::anyhow!("intel edn parse: {e}"))?;
    let report = conn.transact(tx).await?;
    Ok(report.tx_data.len())
}

/// KPI を datomic クエリから導出する (headcount, pipeline_jpy)。
pub fn kpis_from_datomic(conn: &Connection) -> (i64, i64) {
    let db = conn.db();
    let headcount = q(
        parse("{:find [?h] :where [[?c :sim.company/headcount ?h]]}").unwrap(),
        &db, &[],
    ).ok()
    .and_then(|rows| rows.into_iter().next())
    .map(|r| i64_at(&r, 0))
    .unwrap_or(0);

    let pipeline_jpy = q(
        parse("{:find [?v] :where [[?p :sim.pipeline/value-jpy ?v]]}").unwrap(),
        &db, &[],
    ).map(|rows| rows.iter().map(|r| i64_at(r, 0)).sum())
    .unwrap_or(0);

    (headcount, pipeline_jpy)
}

fn i64_at(r: &[EdnValue], i: usize) -> i64 {
    match r.get(i) { Some(EdnValue::Integer(n)) => *n, _ => 0 }
}
fn str_at(r: &[EdnValue], i: usize) -> String {
    match r.get(i) { Some(EdnValue::String(s)) => s.clone(), _ => String::new() }
}
fn kw_at(r: &[EdnValue], i: usize) -> String {
    match r.get(i) { Some(EdnValue::Keyword(k)) => k.name().to_string(), _ => String::new() }
}

/// brief 用: 確度の高い潜在リード名 (datomic 由来、確度降順)。
pub fn latent_lead_names(conn: &Connection) -> Vec<String> {
    let mut v: Vec<(String, i64)> = q(
        parse("{:find [?subj ?conf ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/confidence ?conf][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "latent-lead")
    .map(|r| (str_at(r, 0), i64_at(r, 1)))
    .collect();
    v.sort_by_key(|(_, c)| -c);
    v.into_iter().take(8).map(|(s, _)| s).collect()
}

/// brief 用: 再生候補プロジェクト名 (datomic 由来、資産量降順)。
pub fn revival_names(conn: &Connection) -> Vec<String> {
    let mut v: Vec<(String, i64)> = q(
        parse("{:find [?subj ?score ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?score][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "revival")
    .map(|r| (str_at(r, 0), i64_at(r, 1)))
    .collect();
    v.sort_by_key(|(_, s)| -s);
    v.into_iter().take(6).map(|(s, _)| s).collect()
}

/// intel を Datalog で読み返してダッシュボード用 JSON にする。
pub fn views(conn: &Connection) -> serde_json::Value {
    let db = conn.db();
    let progressed = progressed_subjects(conn);

    // 潜在リード (確度/関与人数/離反リスク つき)
    let mut leads: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?score ?conf ?ppl ?risk ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?score][?e :gftd.intel/confidence ?conf][?e :gftd.intel/people ?ppl][?e :gftd.intel/risk ?risk][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 5) == "latent-lead")
    .map(|r| {
        let subj = str_at(r, 0);
        let stage = progressed.get(&subj).cloned().unwrap_or_else(|| "new".into());
        serde_json::json!({
            "subject": subj, "score": i64_at(r,1), "confidence": i64_at(r,2),
            "people": i64_at(r,3), "risk": kw_at(r,4), "stage": stage
        })
    })
    .collect();
    leads.sort_by_key(|x| -(x["confidence"].as_i64().unwrap_or(0)));

    // 再生候補
    let mut revival: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?score ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?score][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "revival")
    .map(|r| serde_json::json!({ "subject": str_at(r,0), "score": i64_at(r,1) }))
    .collect();
    revival.sort_by_key(|x| -(x["score"].as_i64().unwrap_or(0)));

    // 契約更新リスク
    let renewal: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?note ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/note ?note][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "renewal-risk")
    .map(|r| serde_json::json!({ "subject": str_at(r,0), "note": str_at(r,1) }))
    .collect();

    // 売上集中(依存)
    let deps: Vec<serde_json::Value> = q(
        parse("{:find [?to ?val] :where [[?e :gftd.dep/to ?to][?e :gftd.dep/value-jpy ?val]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .map(|r| serde_json::json!({ "to": str_at(r,0), "value_jpy": i64_at(r,1) }))
    .collect();

    serde_json::json!({
        "latent_leads": leads,
        "revival": revival,
        "renewal_risks": renewal,
        "dependencies": deps,
        "intel_depth": progressed.len(),
    })
}

/// progress イベントから subject ごとの最新ステージを畳み込む。
fn progressed_subjects(conn: &Connection) -> std::collections::HashMap<String, String> {
    let mut map = std::collections::HashMap::new();
    let mut rows = q(
        parse("{:find [?subj ?turn ?stage] :where [[?e :gftd.progress/subject ?subj][?e :gftd.progress/turn ?turn][?e :gftd.progress/stage ?stage]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default();
    rows.sort_by_key(|r| i64_at(r, 1));
    for r in &rows {
        map.insert(str_at(r, 0), kw_at(r, 2));
    }
    map
}

/// ターンを進めるとき、未着手の潜在リードを 1 件「engaged」へ前進させる。
/// append-only の progress datom として datomic に積む。
pub async fn advance(conn: &Connection, turn: i64) -> Option<String> {
    let done = progressed_subjects(conn);
    let mut leads: Vec<(String, i64)> = q(
        parse("{:find [?subj ?conf ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/confidence ?conf][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "latent-lead")
    .map(|r| (str_at(r, 0), i64_at(r, 1)))
    .collect();
    leads.sort_by_key(|(_, c)| -c);

    let next = leads.into_iter().find(|(s, _)| !done.contains_key(s))?;
    let edn = format!(
        "[{{:db/id \"prog-t{turn}\" :gftd.progress/subject {:?} :gftd.progress/turn {turn} :gftd.progress/stage :engaged :gftd.progress/note \"営業接触を開始(intel前進)\"}}]",
        next.0
    );
    if let Ok(tx) = parse(&edn) {
        let _ = conn.transact(tx).await;
    }
    Some(next.0)
}
