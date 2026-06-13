//! 初期状態の構築と datomic シード。
//!
//! facts→状態の導出ロジックは Clojure `analysis/seed.bb` に集約してある。
//! この Rust モジュールは bb を実行して出力 EDN を解析し、AppState を満たす
//! `Seed` を組み立てつつ `:datoms` を datomic に transact するだけの薄い層。

use std::path::PathBuf;
use std::process::Command;

use anyhow::{Context, Result};
use kotoba_datomic::Connection;
use kotoba_edn::{parse, EdnValue, Keyword};

use crate::model::Kpis;

/// シード結果。AppState が保持する。
pub struct Seed {
    pub kpis: Kpis,
    pub pipeline: Vec<(String, i64)>,
    pub projects: Vec<(String, String)>,
    pub recent_activity: Vec<String>,
    pub issued_total_jpy: i64,
    pub received_total_jpy: i64,
}

fn manifest() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn kv<'a>(m: &'a EdnValue, key: &str) -> Option<&'a EdnValue> {
    match m {
        EdnValue::Map(map) => map.get(&EdnValue::Keyword(Keyword::bare(key))),
        _ => None,
    }
}
fn as_i64(v: Option<&EdnValue>) -> i64 {
    match v {
        Some(EdnValue::Integer(n)) => *n,
        _ => 0,
    }
}
fn as_str(v: Option<&EdnValue>) -> String {
    match v {
        Some(EdnValue::String(s)) => s.clone(),
        _ => String::new(),
    }
}
fn as_vec(v: Option<&EdnValue>) -> Vec<EdnValue> {
    match v {
        Some(EdnValue::Vector(xs)) => xs.clone(),
        _ => vec![],
    }
}

/// `analysis/seed.bb` を実行して初期状態を構築し、:datoms を datomic に投入する。
pub async fn load_and_seed(conn: &Connection) -> Result<Seed> {
    let facts = manifest()
        .join("../m365-archive/facts")
        .canonicalize()
        .context("facts dir")?;
    let out = Command::new(crate::intel::bb_bin())
        .arg(manifest().join("analysis/seed.bb"))
        .arg(&facts)
        .output()
        .context("run analysis/seed.bb (babashka)")?;
    if !out.status.success() {
        anyhow::bail!("seed.bb failed: {}", String::from_utf8_lossy(&out.stderr));
    }
    let edn = String::from_utf8(out.stdout).context("seed.bb stdout utf8")?;
    let root = parse(edn.trim()).map_err(|e| anyhow::anyhow!("seed edn parse: {e}"))?;

    // :datoms を transact
    if let Some(d) = kv(&root, "datoms") {
        conn.transact(d.clone()).await?;
    }

    // :world から AppState を組み立てる
    let w = kv(&root, "world").cloned().unwrap_or(EdnValue::Nil);
    let headcount = as_i64(kv(&w, "headcount"));
    let pipeline: Vec<(String, i64)> = as_vec(kv(&w, "pipeline"))
        .iter()
        .filter_map(|e| {
            let pair = if let EdnValue::Vector(p) = e { p } else { return None };
            Some((as_str(pair.first()), as_i64(pair.get(1))))
        })
        .collect();
    let projects: Vec<(String, String)> = as_vec(kv(&w, "projects"))
        .iter()
        .filter_map(|e| {
            let pair = if let EdnValue::Vector(p) = e { p } else { return None };
            Some((as_str(pair.first()), as_str(pair.get(1))))
        })
        .collect();
    let recent_activity: Vec<String> = as_vec(kv(&w, "recent-activity"))
        .iter()
        .map(|e| if let EdnValue::String(s) = e { s.clone() } else { String::new() })
        .collect();
    let issued_total_jpy = as_i64(kv(&w, "issued"));
    let received_total_jpy = as_i64(kv(&w, "received"));

    let mut kpis = Kpis {
        turn: 0,
        cash_jpy: as_i64(kv(&w, "cash")),
        burn_jpy: as_i64(kv(&w, "burn")),
        headcount,
        morale: 70,
        revenue_total_jpy: as_i64(kv(&w, "revenue")),
        pipeline_jpy: as_i64(kv(&w, "pipeline-jpy")),
        runway_months: 0.0,
        status: "playing".into(),
    };
    kpis.recompute();

    Ok(Seed {
        kpis,
        pipeline,
        projects,
        recent_activity,
        issued_total_jpy,
        received_total_jpy,
    })
}
