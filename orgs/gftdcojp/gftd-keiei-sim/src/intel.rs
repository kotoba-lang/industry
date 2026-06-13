//! インテリジェンス層 — kotoba-datomic を本当のエンジンにする。
//!
//! 実 facts から「依存関係(売上集中) / latent(潜在リード) / project(再生候補)」を
//! 導出して datomic に intel datom として書き込み、Datalog で読み返す。KPI も
//! datomic クエリから導出する。intel は append-only の progress datom でターン
//! ごとに前進する(datomic の事実ログとして積み上がる)。

use anyhow::Result;
use kotoba_datomic::{q, Connection};
use kotoba_edn::{parse, EdnValue};

use crate::seed::Seed;

/// 営業リードとして無意味なインフラ/ベンダ/通知系ドメインを除外する。
const NOISE: &[&str] = &[
    "vercel", "google", "microsoft", "amazonaws", "github", "openai", "slack",
    "notion", "figma", "stripe", "pandadoc", "docusign", "zoom", "atlassian",
    "cloudflare", "sendgrid", "mailchimp", "apple", "adobe", "163.com", "gmail",
    "outlook", "noreply", "no-reply", "2checkout", "paypal",
];

fn is_noise(dom: &str) -> bool {
    let d = dom.to_lowercase();
    NOISE.iter().any(|n| d.contains(n))
}

fn edn_str(s: &str) -> String {
    format!("{:?}", s)
}

/// brief 用: 潜在リード名(接触量上位・非ベンダ)。
pub fn latent_lead_names(seed: &Seed) -> Vec<String> {
    seed.crm_top
        .iter()
        .filter(|(d, _)| !is_noise(d))
        .take(8)
        .map(|(d, _)| d.clone())
        .collect()
}

/// brief 用: 再生候補プロジェクト名(file-count上位の休眠)。
pub fn revival_names(seed: &Seed) -> Vec<String> {
    seed.dormant_projects.iter().take(6).map(|(n, _)| n.clone()).collect()
}

/// 実 facts から intel を導出して datomic に書き込む。
pub async fn build(conn: &Connection, seed: &Seed) -> Result<usize> {
    let mut edn = String::from("[\n");

    // --- latent: 接触量上位だが定型ベンダでない org = 潜在リード ---
    let mut leads = 0;
    for (dom, mc) in seed.crm_top.iter() {
        if leads >= 12 {
            break;
        }
        if is_noise(dom) {
            continue;
        }
        edn.push_str(&format!(
            "{{:db/id \"lead{leads}\" :gftd.intel/kind :latent-lead :gftd.intel/subject {} :gftd.intel/score {mc} :gftd.intel/stage :new}}\n",
            edn_str(dom)
        ));
        leads += 1;
    }

    // --- project: file-count 大の休眠プロジェクト = 再生候補 ---
    for (i, (name, fc)) in seed.dormant_projects.iter().take(6).enumerate() {
        edn.push_str(&format!(
            "{{:db/id \"rev{i}\" :gftd.intel/kind :revival :gftd.intel/subject {} :gftd.intel/score {fc} :gftd.intel/stage :new}}\n",
            edn_str(name)
        ));
    }

    // --- 依存(売上集中): gftd → 各商談相手への依存エッジ ---
    for (i, (party, val)) in seed.pipeline.iter().take(10).enumerate() {
        edn.push_str(&format!(
            "{{:db/id \"dep{i}\" :gftd.dep/from \"gftd\" :gftd.dep/to {} :gftd.dep/value-jpy {val} :gftd.dep/kind :revenue}}\n",
            edn_str(party)
        ));
    }

    edn.push_str("]\n");
    let tx = parse(&edn).map_err(|e| anyhow::anyhow!("intel edn parse: {e}"))?;
    let report = conn.transact(tx).await?;
    Ok(report.tx_data.len())
}

/// KPI を datomic クエリから導出する (headcount, pipeline_jpy)。
/// 「メモリではなく datomic で動いている」ことを担保する経路。
pub fn kpis_from_datomic(conn: &Connection) -> (i64, i64) {
    let db = conn.db();
    let headcount = q(
        parse("{:find [?h] :where [[?c :sim.company/headcount ?h]]}").unwrap(),
        &db,
        &[],
    )
    .ok()
    .and_then(|rows| rows.into_iter().next())
    .and_then(|r| match r.first() {
        Some(EdnValue::Integer(n)) => Some(*n),
        _ => None,
    })
    .unwrap_or(0);

    let pipeline_jpy = q(
        parse("{:find [?v] :where [[?p :sim.pipeline/value-jpy ?v]]}").unwrap(),
        &db,
        &[],
    )
    .map(|rows| {
        rows.iter()
            .filter_map(|r| match r.first() {
                Some(EdnValue::Integer(n)) => Some(*n),
                _ => None,
            })
            .sum()
    })
    .unwrap_or(0);

    (headcount, pipeline_jpy)
}

fn i64_at(r: &[EdnValue], i: usize) -> i64 {
    match r.get(i) {
        Some(EdnValue::Integer(n)) => *n,
        _ => 0,
    }
}
fn str_at(r: &[EdnValue], i: usize) -> String {
    match r.get(i) {
        Some(EdnValue::String(s)) => s.clone(),
        _ => String::new(),
    }
}
fn kw_at(r: &[EdnValue], i: usize) -> String {
    match r.get(i) {
        Some(EdnValue::Keyword(k)) => k.name().to_string(),
        _ => String::new(),
    }
}

/// intel を Datalog で読み返してダッシュボード用 JSON にする。
pub fn views(conn: &Connection) -> serde_json::Value {
    let db = conn.db();

    // latent リード (kind=latent-lead) と進捗ステージ
    let progressed = progressed_subjects(conn);
    let leads: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?score ?kind ?stage] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?score][?e :gftd.intel/kind ?kind][?e :gftd.intel/stage ?stage]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "latent-lead")
    .map(|r| {
        let subj = str_at(r, 0);
        let stage = progressed.get(&subj).cloned().unwrap_or_else(|| "new".into());
        serde_json::json!({ "subject": subj, "score": i64_at(r,1), "stage": stage })
    })
    .collect();

    let revival: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?score ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?score][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "revival")
    .map(|r| serde_json::json!({ "subject": str_at(r,0), "score": i64_at(r,1) }))
    .collect();

    let deps: Vec<serde_json::Value> = q(
        parse("{:find [?to ?val] :where [[?e :gftd.dep/to ?to][?e :gftd.dep/value-jpy ?val]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .map(|r| serde_json::json!({ "to": str_at(r,0), "value_jpy": i64_at(r,1) }))
    .collect();

    let progress_count = progressed.len();

    serde_json::json!({
        "latent_leads": sort_by_score(leads),
        "revival": sort_by_score(revival),
        "dependencies": deps,
        "intel_depth": progress_count,
    })
}

fn sort_by_score(mut v: Vec<serde_json::Value>) -> Vec<serde_json::Value> {
    v.sort_by_key(|x| -(x["score"].as_i64().unwrap_or(0)));
    v
}

/// progress イベントから「最新ステージ」を subject ごとに畳み込む。
fn progressed_subjects(conn: &Connection) -> std::collections::HashMap<String, String> {
    let db = conn.db();
    let mut map = std::collections::HashMap::new();
    let rows = q(
        parse("{:find [?subj ?turn ?stage] :where [[?e :gftd.progress/subject ?subj][?e :gftd.progress/turn ?turn][?e :gftd.progress/stage ?stage]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default();
    // turn 昇順で上書き → 最新ステージが残る
    let mut sorted = rows.clone();
    sorted.sort_by_key(|r| i64_at(r, 1));
    for r in &sorted {
        map.insert(str_at(r, 0), kw_at(r, 2));
    }
    map
}

/// ターンを進めるとき、未着手の latent リードを 1 件「engaged」へ前進させる。
/// append-only の progress datom として datomic に積む(intel が前進する)。
pub async fn advance(conn: &Connection, turn: i64) -> Option<String> {
    let db = conn.db();
    let done = progressed_subjects(conn);
    // latent リード一覧 (score 降順)
    let mut leads: Vec<(String, i64)> = q(
        parse("{:find [?subj ?score ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?score][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "latent-lead")
    .map(|r| (str_at(r,0), i64_at(r,1)))
    .collect();
    leads.sort_by_key(|(_, s)| -s);

    let next = leads.into_iter().find(|(s, _)| !done.contains_key(s))?;
    let edn = format!(
        "[{{:db/id \"prog-t{turn}\" :gftd.progress/subject {} :gftd.progress/turn {turn} :gftd.progress/stage :engaged :gftd.progress/note \"営業接触を開始(intel前進)\"}}]",
        edn_str(&next.0)
    );
    if let Ok(tx) = parse(&edn) {
        let _ = conn.transact(tx).await;
    }
    Some(next.0)
}
