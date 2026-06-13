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
use kotoba_runtime::host::WitQuad;

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
pub fn bb_bin() -> String {
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

    // 潜在リード (多角的: 確度/関与/リスク/未返信/path-weight/市場/関係種別/商流額/ステージ)
    let mut leads: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?score ?conf ?ppl ?risk ?open ?pw ?mkt ?rel ?money ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?score][?e :gftd.intel/confidence ?conf][?e :gftd.intel/people ?ppl][?e :gftd.intel/risk ?risk][?e :gftd.intel/open-threads ?open][?e :gftd.intel/path-weight ?pw][?e :gftd.intel/market ?mkt][?e :gftd.intel/rel-type ?rel][?e :gftd.intel/money-jpy ?money][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 10) == "latent-lead")
    .map(|r| {
        let subj = str_at(r, 0);
        let stage = progressed.get(&subj).cloned().unwrap_or_else(|| "new".into());
        serde_json::json!({
            "subject": subj, "score": i64_at(r,1), "confidence": i64_at(r,2),
            "people": i64_at(r,3), "risk": kw_at(r,4), "open_threads": i64_at(r,5),
            "path_weight": i64_at(r,6), "market": kw_at(r,7), "rel_type": kw_at(r,8),
            "money_jpy": i64_at(r,9), "stage": stage
        })
    })
    .collect();
    leads.sort_by_key(|x| -(x["path_weight"].as_i64().unwrap_or(0)));

    // 商談ファネル集計 (ステージ別件数)
    let funnel = serde_json::json!({
        "new": leads.iter().filter(|l| l["stage"] == "new").count(),
        "engaged": leads.iter().filter(|l| l["stage"] == "engaged").count(),
        "qualified": leads.iter().filter(|l| l["stage"] == "qualified").count(),
        "won": leads.iter().filter(|l| l["stage"] == "won").count(),
    });

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

    // 商談ダイジェスト (スレッド件名履歴) — 要約の素材
    let deal_digests: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?note ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/note ?note][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "deal-digest")
    .map(|r| serde_json::json!({ "subject": str_at(r,0), "note": str_at(r,1) }))
    .collect();

    // 不良債権(売掛金・回収要確認)
    let bad_debts: Vec<serde_json::Value> = q(
        parse("{:find [?subj ?amt ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/score ?amt][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "bad-debt")
    .map(|r| serde_json::json!({ "subject": str_at(r,0), "amount_jpy": i64_at(r,1) }))
    .collect();
    let bad_debt_total: i64 = bad_debts.iter().filter_map(|d| d["amount_jpy"].as_i64()).sum();

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

    // 市場分析 (セグメント別 org 数・接触量)
    let mut markets: Vec<serde_json::Value> = q(
        parse("{:find [?seg ?orgs ?msgs] :where [[?e :gftd.market/segment ?seg][?e :gftd.market/orgs ?orgs][?e :gftd.market/messages ?msgs]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default()
    .iter()
    .map(|r| serde_json::json!({ "segment": kw_at(r,0), "orgs": i64_at(r,1), "messages": i64_at(r,2) }))
    .collect();
    markets.sort_by_key(|x| -(x["messages"].as_i64().unwrap_or(0)));

    // 人物ノード (org → 担当者emailリスト) — 関係グラフ用
    let mut people: std::collections::BTreeMap<String, Vec<String>> = std::collections::BTreeMap::new();
    for r in q(
        parse("{:find [?org ?email] :where [[?e :gftd.person/org ?org][?e :gftd.person/email ?email]]}").unwrap(),
        &db, &[],
    ).unwrap_or_default().iter() {
        people.entry(str_at(r, 0)).or_default().push(str_at(r, 1));
    }

    serde_json::json!({
        "latent_leads": leads,
        "revival": revival,
        "renewal_risks": renewal,
        "dependencies": deps,
        "funnel": funnel,
        "markets": markets,
        "people": people,
        "bad_debts": bad_debts,
        "bad_debt_total_jpy": bad_debt_total,
        "deal_digests": deal_digests,
        "intel_depth": progressed.len(),
    })
}

/// 社員エージェントが kqe で読むための intel ブリーフ quad を作る。
/// graph "sim/intel" / subject "all" / predicate "sim.intel/brief" / object {"Text": <要約>}。
pub fn snapshot_quads(conn: &Connection, live: &serde_json::Value) -> Vec<WitQuad> {
    let v = views(conn);
    let lead = v["latent_leads"].as_array().and_then(|a| a.first());
    let renewal = v["renewal_risks"].as_array().and_then(|a| a.first());
    let mut s = String::new();
    if let Some(l) = lead {
        s.push_str(&format!(
            "最有力リード: {} (確度{}, 未返信{}件, {}){}",
            l["subject"].as_str().unwrap_or(""),
            l["confidence"].as_i64().unwrap_or(0),
            l["open_threads"].as_i64().unwrap_or(0),
            l["stage"].as_str().unwrap_or("new"),
            if l["risk"] == "churn" { " ※離反リスク" } else { "" },
        ));
    }
    if let Some(r) = renewal {
        s.push_str(&format!(" / 更新要確認: {}", r["subject"].as_str().unwrap_or("")));
    }
    let bdt = v["bad_debt_total_jpy"].as_i64().unwrap_or(0);
    if bdt > 0 {
        s.push_str(&format!(
            " / 不良債権(回収要確認): {}社 計{:.2}億",
            v["bad_debts"].as_array().map(|a| a.len()).unwrap_or(0),
            bdt as f64 / 1e8
        ));
    }
    // 実 M365 ライブ情報を注入 (今日の予定/未読を踏まえた提案ができるように)
    if let Some(u) = live.get("unread").and_then(|x| x.as_i64()) {
        s.push_str(&format!(" / 📡実Outlook: 未読{u}件"));
        if let Some(ev) = live.get("events").and_then(|x| x.as_array()) {
            let names: Vec<String> = ev
                .iter()
                .take(3)
                .filter_map(|e| e.get("subject").and_then(|x| x.as_str()).map(String::from))
                .collect();
            if !names.is_empty() {
                s.push_str(&format!(", 直近予定[{}]", names.join("/")));
            }
        }
    }
    if s.is_empty() {
        return vec![];
    }
    // 詳細(detail) quad: ReAct agent が2ラウンド目以降に深掘りする具体データ
    let mut detail = String::new();
    if let Some(leads) = v["latent_leads"].as_array() {
        let t: Vec<String> = leads.iter().take(5).map(|l| format!(
            "{}(確度{} pw{} {})",
            l["subject"].as_str().unwrap_or(""), l["confidence"].as_i64().unwrap_or(0),
            l["path_weight"].as_i64().unwrap_or(0), l["rel_type"].as_str().unwrap_or("")
        )).collect();
        detail.push_str(&format!("有力/離反リード: {}\n", t.join(", ")));
    }
    if let Some(rn) = v["renewal_risks"].as_array() {
        let t: Vec<String> = rn.iter().take(4).map(|r| r["subject"].as_str().unwrap_or("").to_string()).collect();
        detail.push_str(&format!("契約更新要確認: {}\n", t.join(", ")));
    }
    if let Some(bd) = v["bad_debts"].as_array() {
        let t: Vec<String> = bd.iter().take(4).map(|b| format!(
            "{} ¥{}万", b["subject"].as_str().unwrap_or(""), b["amount_jpy"].as_i64().unwrap_or(0) / 10000
        )).collect();
        detail.push_str(&format!("不良債権: {}\n", t.join(", ")));
    }
    if let Some(dg) = v["deal_digests"].as_array() {
        for d in dg.iter().take(2) {
            detail.push_str(&format!("商談履歴[{}]: {}\n", d["subject"].as_str().unwrap_or(""), d["note"].as_str().unwrap_or("")));
        }
    }

    let mk = |subj: &str, pred: &str, text: String| {
        let mut obj = Vec::new();
        let mut map = std::collections::BTreeMap::new();
        map.insert("Text".to_string(), text);
        ciborium::into_writer(&map, &mut obj).ok();
        WitQuad { graph: "sim/intel".into(), subject: subj.into(), predicate: pred.into(), object_cbor: obj }
    };
    vec![
        mk("all", "sim.intel/brief", s),
        mk("all", "sim.intel/detail", detail),
    ]
}

/// 商談ファネルを 1 段進める (営業提案の承認時)。
/// 最も進んだ未成約リードを次ステージへ (new→engaged→qualified→won)。
/// 戻り値 (相手先, 新ステージ, 受注額)。won 到達時のみ受注額>0 (#2 売上自動加算)。
pub async fn close_deal(conn: &Connection, turn: i64) -> Option<(String, String, i64)> {
    let done = progressed_subjects(conn);
    let order = |s: &str| match s { "won" => 3, "qualified" => 2, "engaged" => 1, _ => 0 };
    let next = |s: &str| match s { "new" => "engaged", "engaged" => "qualified", _ => "won" };

    // latent リード一覧 (商流額つき: 受注額の算定に使う)
    let leads: Vec<(String, i64)> = q(
        parse("{:find [?subj ?money ?kind] :where [[?e :gftd.intel/subject ?subj][?e :gftd.intel/money-jpy ?money][?e :gftd.intel/kind ?kind]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default()
    .iter()
    .filter(|r| kw_at(r, 2) == "latent-lead")
    .map(|r| (str_at(r, 0), i64_at(r, 1)))
    .collect();

    // 最も進んだ(ただし won 未満)リードを選ぶ。無ければ未着手を engaged へ。
    let pick = leads
        .iter()
        .map(|(s, m)| (s.clone(), *m, done.get(s).cloned().unwrap_or_else(|| "new".into())))
        .filter(|(_, _, st)| order(st) < 3)
        .max_by_key(|(_, _, st)| order(st));
    let (subj, money, cur) = pick?;
    let nx = next(&cur);
    // 受注(won)なら売上を計上: 実商流額があればそれ、無ければ標準商談額 8000万
    let booked = if nx == "won" { money.max(80_000_000) } else { 0 };
    let edn = format!(
        "[{{:db/id \"deal-t{turn}\" :gftd.progress/subject {:?} :gftd.progress/turn {turn} :gftd.progress/stage :{nx} :gftd.progress/note \"商談ステージ前進\"}}]",
        subj
    );
    if let Ok(tx) = parse(&edn) {
        let _ = conn.transact(tx).await;
    }
    Some((subj, nx.to_string(), booked))
}

/// ターン履歴 (時系列可視化用) を datomic から読む。
pub fn turn_history(conn: &Connection) -> Vec<serde_json::Value> {
    let mut rows: Vec<serde_json::Value> = q(
        parse("{:find [?n ?cash ?rev ?morale ?pl] :where [[?t :sim.turn/n ?n][?t :sim.turn/cash-jpy ?cash][?t :sim.turn/revenue-jpy ?rev][?t :sim.turn/morale ?morale][?t :sim.turn/pipeline-jpy ?pl]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default()
    .iter()
    .map(|r| serde_json::json!({
        "turn": i64_at(r,0), "cash": i64_at(r,1), "revenue": i64_at(r,2),
        "morale": i64_at(r,3), "pipeline": i64_at(r,4)
    }))
    .collect();
    rows.sort_by_key(|x| x["turn"].as_i64().unwrap_or(0));
    rows
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
