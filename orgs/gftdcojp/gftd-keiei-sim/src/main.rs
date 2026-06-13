//! gftd-keiei-sim — gftdcojp 経営シミュレーションゲーム。
//!
//! 意思決定者(あなた)が判断・可視化し、LLMエージェント社員が経営を進めるゲーム。
//! - 状態SSoT: kotoba-datomic (m365-archive の実データでシード)
//! - 社員: kotoba-clj defgraph エージェント (営業/開発/財務/CEO補佐) + 実LLM
//! - 可視化: kotoba-runtime-web 系の Web ダッシュボード (サーバ権威 + SSE)
//!
//! 実LLM接続: 環境変数 KOTOBA_INFERENCE_URL / KOTOBA_INFERENCE_MODEL /
//!            KOTOBA_INFERENCE_API_KEY (OpenAI互換)。未設定ならスタブで動作。

mod agents;
mod infer;
mod intel;
mod model;
mod seed;
mod server;

use std::sync::atomic::AtomicU64;
use std::sync::{Arc, Mutex};

use anyhow::Result;
use kotoba_datomic::Connection;
use tokio::sync::broadcast;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .init();

    // 1. 実 facts からゲーム初期状態を構築
    let mut seed = seed::load()?;
    tracing::info!(
        "seeded KPIs: headcount={} cash={}円 burn={}円/月 pipeline={}円 ({}商談)",
        seed.kpis.headcount,
        seed.kpis.cash_jpy,
        seed.kpis.burn_jpy,
        seed.kpis.pipeline_jpy,
        seed.pipeline.len()
    );
    tracing::info!(
        "real gftd data: 発行請求(売上累計)={}円 / 受領請求(コスト累計)={}円 / 直近活動={}件",
        seed.issued_total_jpy,
        seed.received_total_jpy,
        seed.recent_activity.len()
    );

    // 2. datomic を起動し会社の事実をシード
    let conn = Connection::new();
    let n = seed::seed_datomic(&conn, &seed).await?;
    tracing::info!("datomic seeded with {n} datoms");

    // 2b. Clojure(babashka) で intel を導出して datomic に書き込む (分析ロジックは clj 側)
    let intel_n = intel::build(&conn).await?;
    tracing::info!("datomic intel layer (via analysis/intel.bb): {intel_n} datoms");

    // 2c. KPI を datomic クエリから導出し直す (メモリではなく datomic が源泉)
    let (hc, pl) = intel::kpis_from_datomic(&conn);
    seed.kpis.headcount = hc;
    seed.kpis.pipeline_jpy = pl;
    seed.kpis.burn_jpy = hc * 700_000;
    seed.kpis.cash_jpy = seed.kpis.burn_jpy * 10;
    seed.kpis.recompute();
    tracing::info!("KPIs derived from datomic: headcount={hc} pipeline={pl}円");

    // brief 用の intel 抽出 (datomic 由来 = clj が算出した確度順)
    let latent_leads = intel::latent_lead_names(&conn);
    let revival = intel::revival_names(&conn);

    // 3. 社員エージェントをコンパイル + 推論エンジン
    let compiled = agents::compile_all()?;
    let (exec, llm_live) = agents::make_executor()?;
    tracing::info!(
        "executor ready (LLM: {})",
        if llm_live { "REAL (gemma4 e4b @ Ollama)" } else { "STUB" }
    );

    // 4. 共有状態を組み立て
    let (tx, _rx) = broadcast::channel::<String>(64);
    let app = Arc::new(server::App {
        conn,
        exec: Arc::new(exec),
        agents: Arc::new(compiled),
        pipeline: seed.pipeline,
        projects: seed.projects,
        recent_activity: seed.recent_activity,
        issued_total_jpy: seed.issued_total_jpy,
        received_total_jpy: seed.received_total_jpy,
        latent_leads,
        revival,
        llm_live,
        kpis: Mutex::new(seed.kpis),
        proposals: Mutex::new(Vec::new()),
        tx,
        seq: AtomicU64::new(1),
    });

    // 5. サーバ起動
    let port: u16 = std::env::var("PORT").ok().and_then(|p| p.parse().ok()).unwrap_or(8787);
    let addr = format!("0.0.0.0:{port}");
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    println!("\n🎮 gftd-keiei-sim — 経営シミュレーション");
    println!("   ダッシュボード: http://localhost:{port}");
    println!("   LLM: {}\n", if llm_live { "実LLM接続" } else { "スタブ (KOTOBA_INFERENCE_URL未設定)" });

    axum::serve(listener, server::router(app)).await?;
    Ok(())
}
