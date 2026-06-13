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
    /// レポート/商談要約の LLM 直接呼出用 (exec 注入済みと同一エンジン)
    pub infer: kotoba_runtime::host::InferenceFn,
    /// 相談 ReAct agent (react-consult.clj) の WASM
    pub react_wasm: Arc<Vec<u8>>,
    /// ターン統括 defgraph (turn.clj) の WASM
    pub turn_wasm: Arc<Vec<u8>>,
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
    /// 社員間ディスカッションの議事録 (speaker, text)
    pub discussion: Mutex<Vec<serde_json::Value>>,
    /// 実 M365 ライブ情報 (Outlook 受信トレイ/予定/未読) — m365-live.bb 由来
    pub live_m365: Mutex<serde_json::Value>,
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
        .route("/api/report", post(report))
        .route("/api/summarize/:org", post(summarize))
        .route("/api/m365/sync", post(m365_sync))
        .route("/api/m365/triage", post(m365_triage))
        .route("/api/m365/meeting-prep", post(m365_meeting_prep))
        .route("/api/chat", post(chat))
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
    let discussion = app.discussion.lock().unwrap().clone();
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
        // 時系列 (受注/KPI推移の可視化)
        "turn_history": intel::turn_history(&app.conn),
        // 社員間ディスカッション議事録
        "discussion": discussion,
        // 実 M365 ライブ情報 (Outlook)
        "live_m365": app.live_m365.lock().unwrap().clone(),
    })
}

/// 実 Microsoft 365 (Outlook) のライブ情報を analysis/m365-live.bb で取得して保持する。
async fn m365_sync(State(app): State<Shared>) -> Json<serde_json::Value> {
    let script = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("analysis/m365-live.bb");
    let bb = intel::bb_bin();
    let res = tokio::task::spawn_blocking(move || {
        std::process::Command::new(bb).arg(script).output()
    })
    .await;
    match res {
        Ok(Ok(out)) if out.status.success() => {
            if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&out.stdout) {
                *app.live_m365.lock().unwrap() = v;
                tracing::info!("M365 live synced");
            }
        }
        Ok(Ok(out)) => tracing::warn!("m365-live.bb failed: {}", String::from_utf8_lossy(&out.stderr)),
        _ => tracing::warn!("m365-live.bb spawn failed"),
    }
    let _ = app.tx.send("update".to_string());
    Json(state_payload(&app))
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

    // 2. ターン統括 turn.clj を 1 回実行。社員(営業/開発/財務/法務)→財務反論→CEO統括 の
    //    オーケストレーションは全て clj(defgraph)側。Rust はホストとして WASM を回し、
    //    結果(提案/議事録/観測ログ)を kqe-assert! の assert_quads から回収するだけ。
    let exec = app.exec.clone();
    let turn_wasm = app.turn_wasm.clone();
    let world = agents::World {
        kpis: kpis_snapshot.clone(),
        pipeline: app.pipeline.clone(),
        projects: app.projects.clone(),
        recent_activity: app.recent_activity.clone(),
        issued_total_jpy: app.issued_total_jpy,
        received_total_jpy: app.received_total_jpy,
        latent_leads: app.latent_leads.clone(),
        revival: app.revival.clone(),
        snapshot: vec![],
    };
    let brief = agents::common_brief(&world);
    let snapshot = intel::snapshot_quads(&app.conn, &app.live_m365.lock().unwrap().clone());
    let mut ctx = Vec::new();
    {
        let mut m = std::collections::BTreeMap::new();
        m.insert("brief".to_string(), brief);
        ciborium::into_writer(&m, &mut ctx).ok();
    }
    let quads = tokio::task::spawn_blocking(move || {
        exec.execute("turn", &turn_wasm, "did:key:z6MkGftdSim", ctx, snapshot,
                     std::collections::HashMap::new())
            .map(|r| r.assert_quads)
            .unwrap_or_default()
    })
    .await
    .unwrap_or_default();

    // assert_quads を回収: proposal(role→action) / discussion / activity(観測回数)
    let mut actions: std::collections::HashMap<String, String> = std::collections::HashMap::new();
    let mut obs: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
    let mut disc_critique = String::new();
    let mut disc_ceo = String::new();
    for q in &quads {
        let text = parse_text_obj(&q.object_cbor);
        match q.graph.as_str() {
            "sim/proposal" => { actions.insert(q.subject.clone(), text); }
            "sim/activity" => { obs.insert(q.subject.clone(), text.trim().parse().unwrap_or(0)); }
            "sim/discussion" if q.subject == "critique" => disc_critique = text,
            "sim/discussion" if q.subject == "ceo" => disc_ceo = text,
            _ => {}
        }
    }

    // 提案カード (役割順)
    let order = ["sales", "eng", "finance", "legal", "ceo"];
    let proposals: Vec<Proposal> = order
        .iter()
        .filter_map(|role| {
            let action = actions.get(*role)?.clone();
            let id = app.seq.fetch_add(1, Ordering::SeqCst);
            Some(Proposal {
                id: format!("p{id}"),
                role_label: role_label(role).to_string(),
                effect_hint: effect_hint(role).to_string(),
                rounds: *obs.get(*role).unwrap_or(&0),
                role: role.to_string(),
                action,
                status: "pending".into(),
            })
        })
        .collect();
    *app.proposals.lock().unwrap() = proposals;

    // 議事録: 各提案 + 財務反論 + CEO統括
    let mut transcript: Vec<serde_json::Value> = ["sales", "eng", "finance", "legal"]
        .iter()
        .filter_map(|r| {
            actions.get(*r).map(|a| serde_json::json!({ "speaker": role_label(r), "role": r, "text": a }))
        })
        .collect();
    if !disc_critique.is_empty() {
        transcript.push(serde_json::json!({ "speaker": "財務責任者 (反論)", "role": "finance", "text": disc_critique }));
    }
    if !disc_ceo.is_empty() {
        transcript.push(serde_json::json!({ "speaker": "CEO補佐 (統括)", "role": "ceo", "text": disc_ceo }));
    }
    *app.discussion.lock().unwrap() = transcript;

    // 4. ターン履歴を datomic に記録 (時系列可視化用に売上/パイプラインも)
    let edn = format!(
        "[{{:db/id \"turn{t}\" :sim.turn/n {t} :sim.turn/cash-jpy {cash} :sim.turn/runway {runway} :sim.turn/morale {morale} :sim.turn/headcount {hc} :sim.turn/revenue-jpy {rev} :sim.turn/pipeline-jpy {pl}}}]",
        t = kpis_snapshot.turn,
        cash = kpis_snapshot.cash_jpy,
        runway = kpis_snapshot.runway_months as i64,
        morale = kpis_snapshot.morale,
        hc = kpis_snapshot.headcount,
        rev = kpis_snapshot.revenue_total_jpy,
        pl = kpis_snapshot.pipeline_jpy,
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

/// #4 複数四半期の経営レポートを gemma4 で自動生成する。
async fn report(State(app): State<Shared>) -> Json<serde_json::Value> {
    let hist = intel::turn_history(&app.conn);
    let kpis = app.kpis.lock().unwrap().clone();
    let led = ledger(&app.conn);
    let hist_txt = hist
        .iter()
        .map(|h| format!(
            "ターン{}: 現金{:.2}億 売上累計{:.2}億 パイプライン{:.2}億 士気{}",
            h["turn"].as_i64().unwrap_or(0),
            h["cash"].as_i64().unwrap_or(0) as f64 / 1e8,
            h["revenue"].as_i64().unwrap_or(0) as f64 / 1e8,
            h["pipeline"].as_i64().unwrap_or(0) as f64 / 1e8,
            h["morale"].as_i64().unwrap_or(0),
        ))
        .collect::<Vec<_>>()
        .join("\n");
    let dec_txt = led
        .iter()
        .take(12)
        .map(|d| format!("・T{} {}", d["turn"].as_i64().unwrap_or(0), d["note"].as_str().unwrap_or("")))
        .collect::<Vec<_>>()
        .join("\n");
    let prompt = format!(
        "あなたは株式会社gftdのCEO補佐です。以下のKPI推移と意思決定履歴をもとに、複数四半期の経営レポートを日本語で作成してください。構成: 1)現状サマリ 2)良かった点 3)課題とリスク 4)次期の重点方針。各項目2-3文、合計400字程度。\n\n【KPI推移】\n{hist_txt}\n\n【意思決定履歴】\n{dec_txt}\n\n【現在の状態】現金{:.2}億 ランウェイ{:.1}ヶ月 売上累計{:.2}億 士気{} 人員{}名",
        kpis.cash_jpy as f64 / 1e8, kpis.runway_months, kpis.revenue_total_jpy as f64 / 1e8, kpis.morale, kpis.headcount
    );
    let infer = app.infer.clone();
    let text = tokio::task::spawn_blocking(move || infer(&prompt, 800))
        .await
        .ok()
        .and_then(|r| r.ok())
        .unwrap_or_else(|| "レポート生成に失敗しました".into());
    Json(serde_json::json!({ "report": text }))
}

/// #2 商談要約: 取引先のスレッド件名履歴(deal-digest)を gemma4 で要約する。
async fn summarize(Path(org): Path<String>, State(app): State<Shared>) -> Json<serde_json::Value> {
    let it = intel::views(&app.conn);
    let digest = it["deal_digests"]
        .as_array()
        .and_then(|a| a.iter().find(|d| d["subject"].as_str() == Some(&org)))
        .and_then(|d| d["note"].as_str())
        .unwrap_or("")
        .to_string();
    if digest.is_empty() {
        return Json(serde_json::json!({ "summary": "この取引先のスレッド履歴が見つかりません" }));
    }
    let prompt = format!(
        "あなたはgftdの営業担当です。取引先「{org}」との以下のメール/スレッド件名の履歴から、商談の状況と次の一手を日本語3文以内で要約してください。\n--- 件名履歴 ---\n{digest}"
    );
    let infer = app.infer.clone();
    let text = tokio::task::spawn_blocking(move || infer(&prompt, 400))
        .await
        .ok()
        .and_then(|r| r.ok())
        .unwrap_or_else(|| "要約に失敗しました".into());
    Json(serde_json::json!({ "org": org, "summary": text }))
}

/// #2 メール担当エージェント: ライブ未読メールから重要なものを gemma4 が抽出・推奨。
async fn m365_triage(State(app): State<Shared>) -> Json<serde_json::Value> {
    let live = app.live_m365.lock().unwrap().clone();
    let lines: Vec<String> = live["inbox"]
        .as_array()
        .map(|a| {
            a.iter()
                .map(|m| format!(
                    "{} {} :: {}",
                    if m["unread"].as_bool().unwrap_or(false) { "[未読]" } else { "[既読]" },
                    m["from"].as_str().unwrap_or("?"),
                    m["subject"].as_str().unwrap_or("")
                ))
                .collect()
        })
        .unwrap_or_default();
    if lines.is_empty() {
        return Json(serde_json::json!({ "triage": "先に「📡 M365同期」で受信トレイを取得してください" }));
    }
    let prompt = format!(
        "あなたは株式会社gftdのメール担当です。次の受信トレイ一覧から、ビジネス上重要・要対応のメールを最大5件抽出し、各1行で「送信者 → 件名 → 推奨アクション」形式で日本語提示してください。広告/通知/自動配信は除外。\n--- 受信トレイ ---\n{}",
        lines.join("\n")
    );
    let infer = app.infer.clone();
    let text = tokio::task::spawn_blocking(move || infer(&prompt, 600))
        .await.ok().and_then(|r| r.ok())
        .unwrap_or_else(|| "トリアージに失敗しました".into());
    Json(serde_json::json!({ "triage": text }))
}

/// #3 予定表からの会議準備サマリを gemma4 が自動生成。
async fn m365_meeting_prep(State(app): State<Shared>) -> Json<serde_json::Value> {
    let live = app.live_m365.lock().unwrap().clone();
    let lines: Vec<String> = live["events"]
        .as_array()
        .map(|a| {
            a.iter()
                .map(|e| format!(
                    "{} {} (主催: {})",
                    e["start"].as_str().unwrap_or("").chars().take(16).collect::<String>(),
                    e["subject"].as_str().unwrap_or(""),
                    e["organizer"].as_str().unwrap_or("")
                ))
                .collect()
        })
        .unwrap_or_default();
    if lines.is_empty() {
        return Json(serde_json::json!({ "prep": "先に「📡 M365同期」で予定表を取得してください" }));
    }
    let prompt = format!(
        "あなたは株式会社gftdのCEO補佐です。次の今後の予定一覧について、各会議の準備事項・確認すべき論点を1-2行で日本語提示してください(個人予定は簡潔に)。\n--- 予定表 ---\n{}",
        lines.join("\n")
    );
    let infer = app.infer.clone();
    let text = tokio::task::spawn_blocking(move || infer(&prompt, 700))
        .await.ok().and_then(|r| r.ok())
        .unwrap_or_else(|| "会議準備サマリの生成に失敗しました".into());
    Json(serde_json::json!({ "prep": text }))
}

/// 意思決定項目について CEO(意思決定者)が自由文で相談 → gemma4 が文脈を踏まえ回答。
/// 承認/却下の二択でなく対話的に判断できる。
#[derive(serde::Deserialize)]
struct ChatReq {
    context: String,
    message: String,
    #[serde(default)]
    history: String,
}

async fn chat(State(app): State<Shared>, Json(req): Json<ChatReq>) -> Json<serde_json::Value> {
    // 相談文 q = 対象の意思決定 + これまでの会話 + CEOの発言 (文脈をまとめる)
    let q = format!(
        "【対象の意思決定】{}\n【これまでの会話】{}\n【CEOの発言】{}",
        req.context, req.history, req.message
    );
    // ctx CBOR {"q": q}
    let mut ctx = Vec::new();
    {
        let mut m = std::collections::BTreeMap::new();
        m.insert("q".to_string(), q);
        ciborium::into_writer(&m, &mut ctx).ok();
    }
    // intel quad を snapshot で渡し、ReAct agent(react-consult.clj)を WASM 実行
    let snapshot = intel::snapshot_quads(&app.conn, &app.live_m365.lock().unwrap().clone());
    let exec = app.exec.clone();
    let wasm = app.react_wasm.clone();
    let out = tokio::task::spawn_blocking(move || {
        exec.execute(
            "react-consult", &wasm, "did:key:z6MkGftdSim", ctx, snapshot,
            std::collections::HashMap::new(),
        )
    })
    .await;
    let text = match out {
        Ok(Ok(res)) => decode_ok(&res.output_cbor),
        _ => "応答に失敗しました".to_string(),
    };
    Json(serde_json::json!({ "reply": text }))
}

/// CBOR {"Text": <text>} オブジェクト(kqe quad の object)から本文を取り出す。
fn parse_text_obj(bytes: &[u8]) -> String {
    match ciborium::from_reader::<ciborium::value::Value, _>(bytes) {
        Ok(ciborium::value::Value::Map(entries)) => {
            for (k, v) in entries {
                if matches!(&k, ciborium::value::Value::Text(t) if t == "Text") {
                    if let ciborium::value::Value::Text(t) = v {
                        return t;
                    }
                }
            }
            String::new()
        }
        _ => String::new(),
    }
}

/// CBOR {"ok": <text>} から ok 本文を取り出す。
fn decode_ok(bytes: &[u8]) -> String {
    match ciborium::from_reader::<ciborium::value::Value, _>(bytes) {
        Ok(ciborium::value::Value::Map(entries)) => {
            for (k, v) in entries {
                if matches!(&k, ciborium::value::Value::Text(t) if t == "ok") {
                    if let ciborium::value::Value::Text(t) = v {
                        return t;
                    }
                }
            }
            String::new()
        }
        _ => String::new(),
    }
}

async fn events(State(app): State<Shared>) -> Sse<impl Stream<Item = Result<Event, std::convert::Infallible>>> {
    let rx = app.tx.subscribe();
    let stream = BroadcastStream::new(rx)
        .map(|msg| Ok(Event::default().data(msg.unwrap_or_else(|_| "update".into()))));
    Sse::new(stream).keep_alive(KeepAlive::default())
}
