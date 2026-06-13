//! axum サーバ: 意思決定者(あなた)のための Web ダッシュボード権威。
//! ターンを進め、社員提案を集め、承認/却下を datomic に記録し KPI を更新する。

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use axum::extract::{Path, State};
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::routing::{get, post};
use axum::{Json, Router};
use kotoba_datomic::{q, Connection};
use kotoba_edn::{parse, EdnValue};
use kotoba_runtime::WasmExecutor;
use tokio::sync::broadcast;
use tokio_stream::wrappers::BroadcastStream;
use tokio_stream::{Stream, StreamExt};
use tower_http::services::ServeDir;

use crate::agents;
use crate::intel;
use crate::model::{apply_effect, effect_hint, role_label, Kpis, Proposal};

/// 共有アプリ状態。
pub struct App {
    pub conn: Connection,
    pub exec: Arc<WasmExecutor>,
    pub agents: Arc<HashMap<String, Vec<u8>>>,
    /// 実契約由来の商談 (相手先名, 金額)
    pub pipeline: Vec<(String, i64)>,
    pub projects: Vec<(String, String)>,
    /// 直近(2026)の実会議/inbound
    pub recent_activity: Vec<String>,
    /// gftd 発行請求累計 (実売上) / 受領請求累計 (実コスト)
    pub issued_total_jpy: i64,
    pub received_total_jpy: i64,
    /// intel: 潜在リード / 再生候補 (社員brief用)
    pub latent_leads: Vec<String>,
    pub revival: Vec<String>,
    pub llm_live: bool,
    pub kpis: Mutex<Kpis>,
    pub proposals: Mutex<Vec<Proposal>>,
    pub tx: broadcast::Sender<String>,
    pub seq: AtomicU64,
}

pub type Shared = Arc<App>;

fn web_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("web")
}

pub fn router(app: Shared) -> Router {
    Router::new()
        .route("/api/state", get(get_state))
        .route("/api/turn/advance", post(advance_turn))
        .route("/api/proposal/:id/approve", post(approve))
        .route("/api/proposal/:id/reject", post(reject))
        .route("/api/events", get(events))
        .fallback_service(ServeDir::new(web_dir()))
        .with_state(app)
}

/// datomic から意思決定台帳を読む (Datalog)。
fn ledger(conn: &Connection) -> Vec<serde_json::Value> {
    let query = match parse(
        r#"{:find [?turn ?note ?approved]
            :where [[?d :sim.decision/turn ?turn]
                    [?d :gftd.decision/note ?note]
                    [?d :sim.decision/approved ?approved]]}"#,
    ) {
        Ok(q) => q,
        Err(_) => return vec![],
    };
    let rows = match q(query, &conn.db(), &[]) {
        Ok(r) => r,
        Err(_) => return vec![],
    };
    let mut out: Vec<serde_json::Value> = rows
        .iter()
        .map(|r| {
            let turn = match r.first() {
                Some(EdnValue::Integer(n)) => *n,
                _ => 0,
            };
            let note = match r.get(1) {
                Some(EdnValue::String(s)) => s.clone(),
                _ => String::new(),
            };
            let approved = matches!(r.get(2), Some(EdnValue::Bool(true)));
            serde_json::json!({ "turn": turn, "note": note, "approved": approved })
        })
        .collect();
    out.sort_by_key(|v| -(v["turn"].as_i64().unwrap_or(0)));
    out
}

fn state_payload(app: &App) -> serde_json::Value {
    let kpis = app.kpis.lock().unwrap().clone();
    let proposals = app.proposals.lock().unwrap().clone();
    let pipeline: Vec<serde_json::Value> = app
        .pipeline
        .iter()
        .map(|(name, v)| serde_json::json!({ "party": name, "value_jpy": v }))
        .collect();
    serde_json::json!({
        "kpis": kpis,
        "proposals": proposals,
        "ledger": ledger(&app.conn),
        "llm_live": app.llm_live,
        "company": "gftdcojp",
        // 実 gftd データ
        "pipeline": pipeline,
        "recent_activity": app.recent_activity,
        "issued_total_jpy": app.issued_total_jpy,
        "received_total_jpy": app.received_total_jpy,
        // datomic 由来の intel (依存/latent/再生候補)
        "intel": intel::views(&app.conn),
    })
}

async fn get_state(State(app): State<Shared>) -> Json<serde_json::Value> {
    Json(state_payload(&app))
}

/// ターンを進める: 四半期バーンを引き、社員提案を生成する。
async fn advance_turn(State(app): State<Shared>) -> Json<serde_json::Value> {
    // 1. 四半期経過: バーン適用 (倒産していなければ)
    let (kpis_snapshot, bankrupt) = {
        let mut k = app.kpis.lock().unwrap();
        if k.status == "bankrupt" {
            (k.clone(), true)
        } else {
            k.turn += 1;
            k.cash_jpy -= k.burn_jpy * 3; // 四半期=3ヶ月
            k.recompute();
            (k.clone(), false)
        }
    };

    if bankrupt {
        return Json(state_payload(&app));
    }

    // 2. 社員エージェントを実行。
    //    #1 並列推論: 機能部門(営業/開発/財務/法務)を spawn_blocking で同時実行。
    //    #4 社員間ディスカッション: その4提案を CEO補佐が受けて統括提案する(2フェーズ)。
    let exec = app.exec.clone();
    let agents_map = app.agents.clone();
    let turn_n = kpis_snapshot.turn as u64;
    let world = std::sync::Arc::new(agents::World {
        kpis: kpis_snapshot.clone(),
        pipeline: app.pipeline.clone(),
        projects: app.projects.clone(),
        recent_activity: app.recent_activity.clone(),
        issued_total_jpy: app.issued_total_jpy,
        received_total_jpy: app.received_total_jpy,
        latent_leads: app.latent_leads.clone(),
        revival: app.revival.clone(),
        snapshot: intel::snapshot_quads(&app.conn),
    });

    // フェーズ1: 機能部門を並列実行
    let mut handles = Vec::new();
    for role in ["sales", "eng", "finance", "legal"] {
        let exec = exec.clone();
        let agents_map = agents_map.clone();
        let world = world.clone();
        let role = role.to_string();
        handles.push(tokio::task::spawn_blocking(move || {
            let wasm = agents_map.get(&role).cloned().unwrap_or_default();
            let brief = agents::build_brief(&role, &world);
            agents::run_one(exec, wasm, role, brief, turn_n, world.snapshot.clone())
        }));
    }
    let mut results: Vec<(String, String)> = Vec::new();
    for h in handles {
        if let Ok(x) = h.await {
            results.push(x);
        }
    }

    // フェーズ2: CEO補佐が4提案を踏まえて統括 (社員間ディスカッション)
    let peer = results
        .iter()
        .map(|(r, a)| format!("・{}: {}", role_label(r), a))
        .collect::<Vec<_>>()
        .join("\n");
    let ceo_brief = format!(
        "{}\n--- 社内の議論(各責任者の提案) ---\n{}\n上記の議論を踏まえ、最優先の経営判断を1つ具申してください。",
        agents::build_brief("ceo", &world),
        peer
    );
    {
        let exec = exec.clone();
        let agents_map = agents_map.clone();
        let snap = world.snapshot.clone();
        if let Ok(c) = tokio::task::spawn_blocking(move || {
            let wasm = agents_map.get("ceo").cloned().unwrap_or_default();
            agents::run_one(exec, wasm, "ceo".to_string(), ceo_brief, turn_n, snap)
        })
        .await
        {
            results.push(c);
        }
    }

    // 3. 提案カードを作成
    let proposals: Vec<Proposal> = results
        .into_iter()
        .map(|(role, action)| {
            let id = app.seq.fetch_add(1, Ordering::SeqCst);
            Proposal {
                id: format!("p{id}"),
                role_label: role_label(&role).to_string(),
                effect_hint: effect_hint(&role).to_string(),
                role,
                action,
                status: "pending".into(),
            }
        })
        .collect();
    *app.proposals.lock().unwrap() = proposals;

    // 4. ターン履歴を datomic に記録
    let edn = format!(
        "[{{:db/id \"turn{t}\" :sim.turn/n {t} :sim.turn/cash-jpy {cash} :sim.turn/runway {runway} :sim.turn/morale {morale} :sim.turn/headcount {hc}}}]",
        t = kpis_snapshot.turn,
        cash = kpis_snapshot.cash_jpy,
        runway = kpis_snapshot.runway_months as i64,
        morale = kpis_snapshot.morale,
        hc = kpis_snapshot.headcount,
    );
    if let Ok(tx) = parse(&edn) {
        let _ = app.conn.transact(tx).await;
    }

    // intel をターンごとに前進させる (次の潜在リードを engaged に — datomic に積む)
    if let Some(subj) = intel::advance(&app.conn, kpis_snapshot.turn).await {
        tracing::info!("intel advanced: {subj} -> engaged (turn {})", kpis_snapshot.turn);
    }

    let _ = app.tx.send("update".to_string());
    Json(state_payload(&app))
}

async fn approve(Path(id): Path<String>, State(app): State<Shared>) -> Json<serde_json::Value> {
    decide(&app, &id, true).await;
    Json(state_payload(&app))
}

async fn reject(Path(id): Path<String>, State(app): State<Shared>) -> Json<serde_json::Value> {
    decide(&app, &id, false).await;
    Json(state_payload(&app))
}

/// 提案を承認/却下し、KPI を更新し、datomic に意思決定を記録する。
async fn decide(app: &App, id: &str, approved: bool) {
    // 1. 提案を確定
    let found = {
        let mut props = app.proposals.lock().unwrap();
        props
            .iter_mut()
            .find(|p| p.id == id && p.status == "pending")
            .map(|p| {
                p.status = if approved { "approved" } else { "rejected" }.into();
                (p.role.clone(), p.action.clone())
            })
    };
    let Some((role, action)) = found else { return };

    // 2. KPI 更新
    let (note, turn) = {
        let mut k = app.kpis.lock().unwrap();
        let note = if approved {
            apply_effect(&role, &mut k)
        } else {
            k.morale = (k.morale - 1).max(0);
            k.recompute();
            format!("{}の提案を却下", role_label(&role))
        };
        (note, k.turn)
    };

    // 3. datomic に意思決定を記録
    let edn = format!(
        "[{{:db/id \"dec-{id}\" :sim.decision/turn {turn} :sim.decision/by-role :{role} :sim.decision/approved {approved} :gftd.decision/note {note:?} :sim.decision/action {action:?}}}]"
    );
    if let Ok(tx) = parse(&edn) {
        let _ = app.conn.transact(tx).await;
    }

    // 営業提案の承認 → 商談ファネルを 1 段前進 (datomic に progress datom)
    if approved && role == "sales" {
        if let Some((subj, stage, booked)) = intel::close_deal(&app.conn, turn).await {
            tracing::info!("deal advanced: {subj} -> {stage} (turn {turn}, booked {booked})");
            // #2 受注(won)時の売上自動加算
            if booked > 0 {
                let note = {
                    let mut k = app.kpis.lock().unwrap();
                    k.cash_jpy += booked;
                    k.revenue_total_jpy += booked;
                    k.morale = (k.morale + 5).min(100);
                    k.recompute();
                    format!("🎉 受注: {subj} (+{}億)", booked as f64 / 1e8)
                };
                let edn = format!(
                    "[{{:db/id \"won-t{turn}-{}\" :sim.decision/turn {turn} :sim.decision/by-role :sales :sim.decision/approved true :gftd.decision/note {note:?} :sim.decision/action {note:?}}}]",
                    subj.len()
                );
                if let Ok(tx) = parse(&edn) {
                    let _ = app.conn.transact(tx).await;
                }
            }
        }
    }

    let _ = app.tx.send("update".to_string());
}

async fn events(State(app): State<Shared>) -> Sse<impl Stream<Item = Result<Event, std::convert::Infallible>>> {
    let rx = app.tx.subscribe();
    let stream = BroadcastStream::new(rx)
        .map(|msg| Ok(Event::default().data(msg.unwrap_or_else(|_| "update".into()))));
    Sse::new(stream).keep_alive(KeepAlive::default())
}
