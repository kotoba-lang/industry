//! HTR — Hypothesis-Tree Refinement (Arbor) + Elo トーナメント (AI Co-Scientist)。
//!
//! 経営判断を「反証可能な経営仮説」のノードとして永続ツリー上で扱う。
//!   - Executor: `World`(KPI)のクローンに施策を適用し数四半期フォワードシムして
//!     dev-score / worst-case(minimax ストレス) を出す。隔離 worktree = 非commit クローン。
//!   - Ranking: pending leaf を Elo トーナメント(round-robin self-play)でランク付け。
//!   - 永続化: datomic に `:keiei.htr/*` ノード + 状態/Elo を append-only イベントで積む。
//!     merge/prune/continue は status イベントを畳み込んで現状態を復元する(progress と同型)。
//!
//! スコア合成式は本モジュール(Rust)に持つ — intel(分析)は clj だが、Executor の
//! 前進シミュレーションは `model::apply_effect` を権威として再利用する純計算のため。

use std::collections::HashMap;

use kotoba_datomic::{q, Connection};
use kotoba_edn::{parse, EdnValue};

use crate::model::{apply_effect, Kpis};

/// Executor のシミュレーション結果。
#[derive(Clone, Debug)]
pub struct SimResult {
    /// 何もしない基準線に対する合成改善シグナル(億円相当, 高いほど良い)
    pub dev_score: f64,
    /// minimax 最悪ケース: バーン+20%・受注率半減のストレス下の dev-score(held-out 検証)
    pub worst_case: f64,
    pub cash_end: i64,
    pub runway_end: f64,
    pub bankrupt: bool,
}

/// 1施策を `quarters` 四半期フォワードシムする(Executor の本体)。
/// base を一切変更せずクローン上で評価する(隔離サンドボックス)。
pub fn simulate(role: &str, base: &Kpis, quarters: i64) -> SimResult {
    let oku = |v: i64| v as f64 / 1e8;

    // 基準線: 何もせず quarters 四半期バーンし続ける
    let mut bk = base.clone();
    forward(&mut bk, quarters, 1.0);

    // 施策適用後を burn_mult / close_rate を変えて評価する
    let run = |burn_mult: f64, close_rate: f64| -> Kpis {
        let mut k = base.clone();
        apply_effect(role, &mut k); // 役割ごとの権威的 KPI デルタ
        k.cash_jpy += expected_revenue(role, &k, close_rate); // 期待受注(営業/統括/進化)
        forward(&mut k, quarters, burn_mult);
        k
    };

    let nom = run(1.0, 1.0); // 名目シナリオ
    let strs = run(1.2, 0.5); // ストレス(held-out)シナリオ

    let dpipe = oku(nom.pipeline_jpy - base.pipeline_jpy); // パイプライン増 = 将来売上の割引現在価値
    let dmor = (nom.morale - base.morale) as f64;
    let dev = oku(nom.cash_jpy - bk.cash_jpy)
        + dpipe * 0.2
        + (nom.runway_months - bk.runway_months) * 0.05
        + dmor * 0.01
        - if nom.status == "bankrupt" { 5.0 } else { 0.0 };
    let worst = oku(strs.cash_jpy - bk.cash_jpy)
        + oku(strs.pipeline_jpy - base.pipeline_jpy) * 0.1
        - if strs.status == "bankrupt" { 6.0 } else { 0.0 };

    SimResult {
        dev_score: dev,
        worst_case: worst,
        cash_end: nom.cash_jpy,
        runway_end: nom.runway_months,
        bankrupt: nom.status == "bankrupt",
    }
}

fn forward(k: &mut Kpis, quarters: i64, burn_mult: f64) {
    for _ in 0..quarters.max(0) {
        k.cash_jpy -= ((k.burn_jpy as f64) * 3.0 * burn_mult) as i64; // 四半期=3ヶ月
    }
    k.recompute();
}

/// 役割ごとの期待受注(horizon 内, close_rate で割引)。apply_effect が現金化しない
/// 営業/統括/進化のパイプライン転換を、シミュ上だけ期待値として現金に反映する。
fn expected_revenue(role: &str, k: &Kpis, close_rate: f64) -> i64 {
    let p = k.pipeline_jpy as f64;
    match role {
        "sales" => (p * 0.15 * close_rate) as i64,
        "evolution" | "ceo" => (p * 0.08 * close_rate) as i64,
        "legal" => (p * 0.04 * close_rate) as i64,
        _ => 0,
    }
}

// ---- Elo トーナメント (AI Co-Scientist の Ranking) --------------------------

/// pending leaf 同士の round-robin self-play で Elo を更新する。
/// 勝敗は dev-score の大小で決まる(決定論的・再現可能)。rounds = test-time compute ノブ。
pub fn tournament(seed: &HashMap<String, f64>, cands: &[(String, f64)], rounds: i64) -> HashMap<String, f64> {
    let mut elo: HashMap<String, f64> = cands
        .iter()
        .map(|(id, _)| (id.clone(), *seed.get(id).unwrap_or(&1200.0)))
        .collect();
    let k = 24.0;
    for _ in 0..rounds.max(1) {
        for i in 0..cands.len() {
            for j in (i + 1)..cands.len() {
                let (ai, asc) = &cands[i];
                let (bi, bsc) = &cands[j];
                let ra = elo[ai];
                let rb = elo[bi];
                let ea = 1.0 / (1.0 + 10f64.powf((rb - ra) / 400.0));
                let sa = if asc > bsc { 1.0 } else if asc < bsc { 0.0 } else { 0.5 };
                elo.insert(ai.clone(), ra + k * (sa - ea));
                elo.insert(bi.clone(), rb + k * ((1.0 - sa) - (1.0 - ea)));
            }
        }
    }
    elo
}

// ---- datomic 永続化 (Hypothesis Tree) --------------------------------------

/// HTR ノード 1 件(永続ツリーの leaf/枝)。
pub struct Node {
    pub id: String,
    pub turn: i64,
    pub role: String,
    pub hypothesis: String,
    pub parent: String,
    pub sim: SimResult,
}

/// ノード(不変属性)を datomic に transact する。id を :db/id にして冪等。
pub async fn record_nodes(conn: &Connection, nodes: &[Node]) {
    for n in nodes {
        let edn = format!(
            "[{{:db/id {id:?} :keiei.htr/id {id:?} :keiei.htr/turn {turn} :keiei.htr/role :{role} :keiei.htr/hypothesis {hyp:?} :keiei.htr/parent {parent:?} :keiei.htr/dev-score {dev} :keiei.htr/worst-case {worst}}}]",
            id = n.id,
            turn = n.turn,
            role = n.role,
            hyp = n.hypothesis,
            parent = n.parent,
            dev = (n.sim.dev_score * 100.0) as i64,
            worst = (n.sim.worst_case * 100.0) as i64,
        );
        if let Ok(tx) = parse(&edn) {
            let _ = conn.transact(tx).await;
        }
    }
}

/// Elo を append-only イベントで記録する(畳み込みで最新値を復元)。
pub async fn record_elos(conn: &Connection, turn: i64, elos: &[(String, i64)]) {
    for (i, (node, val)) in elos.iter().enumerate() {
        let edn = format!(
            "[{{:db/id \"htr-elo-t{turn}-{i}\" :keiei.htr.elo/node {node:?} :keiei.htr.elo/turn {turn} :keiei.htr.elo/value {val}}}]"
        );
        if let Ok(tx) = parse(&edn) {
            let _ = conn.transact(tx).await;
        }
    }
}

/// 状態遷移(merge/prune/continue)を append-only イベントで記録する。
pub async fn record_status(conn: &Connection, turn: i64, node: &str, status: &str) {
    let edn = format!(
        "[{{:db/id \"htr-st-t{turn}-{slug}\" :keiei.htr.status/node {node:?} :keiei.htr.status/turn {turn} :keiei.htr.status/value :{status}}}]",
        slug = node.replace(['-', '/'], "")
    );
    if let Ok(tx) = parse(&edn) {
        let _ = conn.transact(tx).await;
    }
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

/// node → 最新ステータス(無ければ pending)を畳み込む。
pub fn current_status(conn: &Connection) -> HashMap<String, String> {
    let mut rows = q(
        parse("{:find [?node ?turn ?val] :where [[?e :keiei.htr.status/node ?node][?e :keiei.htr.status/turn ?turn][?e :keiei.htr.status/value ?val]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default();
    rows.sort_by_key(|r| i64_at(r, 1));
    let mut map = HashMap::new();
    for r in &rows {
        map.insert(str_at(r, 0), kw_at(r, 2));
    }
    map
}

/// node → 最新 Elo を畳み込む。
pub fn current_elos(conn: &Connection) -> HashMap<String, f64> {
    let mut rows = q(
        parse("{:find [?node ?turn ?val] :where [[?e :keiei.htr.elo/node ?node][?e :keiei.htr.elo/turn ?turn][?e :keiei.htr.elo/value ?val]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default();
    rows.sort_by_key(|r| i64_at(r, 1));
    let mut map = HashMap::new();
    for r in &rows {
        map.insert(str_at(r, 0), i64_at(r, 2) as f64);
    }
    map
}

/// merge/prune 済みでない(=探索中の)leaf ノードを (id, dev-score) で返す。
/// 次ターンのトーナメントに前ターン生存分を持ち越すための候補集合。
pub fn surviving_leaves(conn: &Connection) -> Vec<(String, f64)> {
    let status = current_status(conn);
    node_rows(conn)
        .into_iter()
        .filter(|(id, _, _, _, _, _)| {
            !matches!(status.get(id).map(String::as_str), Some("merged") | Some("pruned"))
        })
        .map(|(id, _, _, _, dev, _)| (id, dev))
        .collect()
}

/// ノードの生データ (id, turn, role, hypothesis, dev-score, worst-case)。
fn node_rows(conn: &Connection) -> Vec<(String, i64, String, String, f64, f64)> {
    q(
        parse("{:find [?id ?turn ?role ?hyp ?dev ?worst] :where [[?e :keiei.htr/id ?id][?e :keiei.htr/turn ?turn][?e :keiei.htr/role ?role][?e :keiei.htr/hypothesis ?hyp][?e :keiei.htr/dev-score ?dev][?e :keiei.htr/worst-case ?worst]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default()
    .iter()
    .map(|r| (str_at(r, 0), i64_at(r, 1), kw_at(r, 2), str_at(r, 3), i64_at(r, 4) as f64 / 100.0, i64_at(r, 5) as f64 / 100.0))
    .collect()
}

/// 仮説ツリー全体を JSON で返す(/api/htr とダッシュボード用)。
pub fn tree(conn: &Connection) -> serde_json::Value {
    let status = current_status(conn);
    let elos = current_elos(conn);
    let parents: HashMap<String, String> = q(
        parse("{:find [?id ?parent] :where [[?e :keiei.htr/id ?id][?e :keiei.htr/parent ?parent]]}").unwrap(),
        &conn.db(), &[],
    ).unwrap_or_default()
    .iter()
    .map(|r| (str_at(r, 0), str_at(r, 1)))
    .collect();

    let mut nodes: Vec<serde_json::Value> = node_rows(conn)
        .into_iter()
        .map(|(id, turn, role, hyp, dev, worst)| {
            let st = status.get(&id).cloned().unwrap_or_else(|| "pending".into());
            let elo = *elos.get(&id).unwrap_or(&1200.0);
            serde_json::json!({
                "id": id, "turn": turn, "role": role, "hypothesis": hyp,
                "parent": parents.get(&id).cloned().unwrap_or_else(|| "root".into()),
                "status": st, "elo": elo.round() as i64,
                "dev_score": dev, "worst_case": worst,
            })
        })
        .collect();
    // Elo 降順(Select の順序)。同点は turn 新しい順。
    nodes.sort_by(|a, b| {
        b["elo"].as_i64().unwrap_or(0).cmp(&a["elo"].as_i64().unwrap_or(0))
            .then(b["turn"].as_i64().unwrap_or(0).cmp(&a["turn"].as_i64().unwrap_or(0)))
    });

    serde_json::json!({
        "root": "18ヶ月ランウェイを維持しつつ売上を伸ばす",
        "nodes": nodes,
        "counts": {
            "pending": nodes.iter().filter(|n| n["status"] == "pending").count(),
            "merged": nodes.iter().filter(|n| n["status"] == "merged").count(),
            "pruned": nodes.iter().filter(|n| n["status"] == "pruned").count(),
            "continued": nodes.iter().filter(|n| n["status"] == "continued").count(),
        }
    })
}
