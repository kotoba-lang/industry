//! 実データ (gftdcojp m365-archive/facts) からゲーム初期状態を構築し、
//! kotoba-datomic に会社の事実と過去意思決定をシードする。
//!
//! 取り込む実データ:
//!   people.edn        — 社内人員数 (:person/role :internal)
//!   crm.edn           — 取引先の接触量
//!   contract-terms.edn— 実契約 (相手先名 + :amount_jpy)
//!   invoice-terms.edn — 実請求書 (発行=売上 / 受領=コスト の実額)
//!   events.edn        — 直近(2026)の実商談・会議
//!   triage-log.edn    — 直近(2026)の実inbound (リード/案内)
//!   projects.edn      — プロジェクト
//!   decisions.edn     — 過去の実意思決定

use std::path::PathBuf;

use anyhow::{Context, Result};
use kotoba_datomic::Connection;
use kotoba_edn::{parse, EdnValue, Keyword};

use crate::model::Kpis;

/// シード結果。AppState が保持する。
pub struct Seed {
    pub kpis: Kpis,
    /// 商談パイプライン: (相手先名, value_jpy) — 実契約由来
    pub pipeline: Vec<(String, i64)>,
    /// プロジェクト: (name, status)
    pub projects: Vec<(String, String)>,
    /// 直近(2026)の実活動 (会議/inbound) — 社員briefに注入
    pub recent_activity: Vec<String>,
    /// gftd 発行請求書の累計 (実売上, 円)
    pub issued_total_jpy: i64,
    /// gftd 受領請求書の累計 (実コスト, 円)
    pub received_total_jpy: i64,
    // ---- intel 素材 (依存/latent 導出用) ----
    /// crm 接触量上位 (確度スコア・離反リスク算出用)
    pub crm_top: Vec<CrmOrg>,
    /// 既存契約の相手先名の集合
    pub contract_parties: Vec<String>,
    /// 休眠プロジェクト (name, file_count) — 再生候補導出用
    pub dormant_projects: Vec<(String, i64)>,
}

/// crm 取引先の生シグナル (確度スコア・離反リスクの材料)。
#[derive(Clone)]
pub struct CrmOrg {
    pub domain: String,
    pub messages: i64,
    /// 最終接触 ISO 文字列 ("2026-06-11T...")
    pub last_contact: String,
    /// 関与した人数 (:people の要素数)
    pub people: i64,
}

fn facts_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../m365-archive/facts")
}

/// facts ファイルを行単位で読み、`{...}` で始まりフィルタに合致する行だけを
/// EDN マップとしてパースする。ベクタ形 (people/projects/orgs) も EDN-lines 形
/// (crm/invoice/events/triage) も、エントリが 1 行 1 マップなので同じ方法で扱える。
/// 巨大ファイル全体の一括パースを避け、必要行だけ解析して起動を高速化する。
fn parse_objs(name: &str, keep: impl Fn(&str) -> bool) -> Result<Vec<EdnValue>> {
    let path = facts_dir().join(name);
    let src = std::fs::read_to_string(&path).with_context(|| format!("read {path:?}"))?;
    let mut out = Vec::new();
    for line in src.lines() {
        let t = line.trim_start();
        if !t.starts_with('{') || !keep(line) {
            continue;
        }
        if let Ok(v) = parse(t) {
            out.push(v);
        }
    }
    tracing::info!("parse_objs {name}: {} objects", out.len());
    Ok(out)
}

fn kv<'a>(m: &'a EdnValue, key: &str) -> Option<&'a EdnValue> {
    match m {
        EdnValue::Map(map) => map.get(&EdnValue::Keyword(Keyword::bare(key))),
        _ => None,
    }
}

fn kv_ns<'a>(m: &'a EdnValue, ns: &str, name: &str) -> Option<&'a EdnValue> {
    match m {
        EdnValue::Map(map) => map.get(&EdnValue::Keyword(Keyword::namespaced(ns, name))),
        _ => None,
    }
}

fn as_i64(v: Option<&EdnValue>) -> Option<i64> {
    match v {
        Some(EdnValue::Integer(n)) => Some(*n),
        _ => None,
    }
}

fn as_str(v: Option<&EdnValue>) -> Option<String> {
    match v {
        Some(EdnValue::String(s)) if !s.is_empty() => Some(s.clone()),
        _ => None,
    }
}

fn kw_name(v: Option<&EdnValue>) -> Option<String> {
    match v {
        Some(EdnValue::Keyword(k)) => Some(k.name().to_string()),
        _ => None,
    }
}

fn is_gftd(s: &str) -> bool {
    let l = s.to_lowercase();
    l.contains("gftd") || s.contains("ギフテッ") || s.contains("ギフテット")
}

/// 実 facts から KPI 初期値・パイプライン・直近活動を構築する。
pub fn load() -> Result<Seed> {
    // --- 社内人員: people.edn の :person/role :internal を数える ---
    let people = parse_objs("people.edn", |l| l.contains(":internal")).unwrap_or_default();
    let headcount = people
        .iter()
        .filter(|p| kw_name(kv_ns(p, "person", "role")).as_deref() == Some("internal"))
        .count()
        .max(1) as i64;

    // --- 実請求書: 発行(売上) / 受領(コスト) の累計を集計 ---
    let invoices = parse_objs("invoice-terms.edn", |l| l.contains(":amount_jpy ")).unwrap_or_default();
    let mut issued_total_jpy = 0i64;
    let mut received_total_jpy = 0i64;
    for inv in &invoices {
        let amt = match as_i64(kv(inv, "amount_jpy")) {
            Some(a) if a > 0 => a,
            _ => continue,
        };
        let issuer = as_str(kv(inv, "issuer")).unwrap_or_default();
        let billed = as_str(kv(inv, "billed_to")).unwrap_or_default();
        if is_gftd(&issuer) {
            issued_total_jpy += amt;
        } else if is_gftd(&billed) {
            received_total_jpy += amt;
        }
    }

    // --- 既存契約: 相手先 + 金額 (intel の依存/latent 判定にも使う) ---
    let contracts = parse_objs("contract-terms.edn", |l| l.contains(":amount_jpy ")).unwrap_or_default();
    let mut contract_parties: Vec<String> = Vec::new();
    let mut deals: Vec<(String, i64)> = Vec::new();
    for c in &contracts {
        let party = as_str(kv(c, "party_b")).unwrap_or_default();
        let amt = as_i64(kv(c, "amount_jpy")).unwrap_or(0);
        if party.is_empty() {
            continue;
        }
        if !contract_parties.contains(&party) {
            contract_parties.push(party.clone());
        }
        if amt <= 0 || is_gftd(&party) || deals.iter().any(|(p, _)| p == &party) {
            continue;
        }
        deals.push((party, amt));
    }
    deals.sort_by_key(|(_, v)| -v);
    deals.truncate(12);

    // --- crm 接触量上位 (商談補完 + latent リード導出用) ---
    let mut crm = parse_objs("crm.edn", |_| true).unwrap_or_default();
    crm.sort_by_key(|c| -as_i64(kv(c, "message_count")).unwrap_or(0));
    let crm_top: Vec<CrmOrg> = crm
        .iter()
        .filter_map(|c| {
            Some(CrmOrg {
                domain: as_str(kv(c, "org_domain"))?,
                messages: as_i64(kv(c, "message_count")).unwrap_or(0),
                last_contact: as_str(kv(c, "last_contact")).unwrap_or_default(),
                people: match kv(c, "people") {
                    Some(EdnValue::Vector(v)) => v.len() as i64,
                    _ => 0,
                },
            })
        })
        .take(40)
        .collect();

    // 契約由来の商談が少ない場合は crm 接触量上位で補完
    if deals.len() < 8 {
        for o in &crm_top {
            if deals.len() >= 12 {
                break;
            }
            if deals.iter().any(|(p, _)| p == &o.domain) {
                continue;
            }
            deals.push((o.domain.clone(), (o.messages * 8_000).clamp(500_000, 30_000_000)));
        }
    }
    let pipeline_jpy: i64 = deals.iter().map(|(_, v)| *v).sum();

    // --- プロジェクト (status + file-count) ---
    let proj_raw = parse_objs("projects.edn", |_| true).unwrap_or_default();
    let proj_all: Vec<(String, String, i64)> = proj_raw
        .iter()
        .filter_map(|p| {
            let name = as_str(kv_ns(p, "gftd.project", "name"))?;
            let status = kw_name(kv_ns(p, "gftd.project", "status")).unwrap_or_else(|| "unknown".into());
            let fc = as_i64(kv_ns(p, "gftd.project", "file-count")).unwrap_or(0);
            Some((name, status, fc))
        })
        .collect();
    let projects: Vec<(String, String)> = proj_all
        .iter()
        .take(10)
        .map(|(n, s, _)| (n.clone(), s.clone()))
        .collect();
    // 休眠プロジェクトを file-count 降順で (再生候補導出)
    let mut dormant_projects: Vec<(String, i64)> = proj_all
        .iter()
        .filter(|(_, s, _)| s == "dormant")
        .map(|(n, _, fc)| (n.clone(), *fc))
        .collect();
    dormant_projects.sort_by_key(|(_, fc)| -fc);

    // --- 直近(2026)の実活動: 会議 + inbound ---
    let mut recent_activity: Vec<String> = Vec::new();
    let events = parse_objs("events.edn", |l| {
        l.contains(":start \"2026") && !l.contains("日本の休日")
    })
    .unwrap_or_default();
    for e in events.iter().rev() {
        if recent_activity.len() >= 8 {
            break;
        }
        if let Some(subj) = as_str(kv(e, "subject")) {
            let s = format!("📅 会議: {subj}");
            if !recent_activity.contains(&s) {
                recent_activity.push(s);
            }
        }
    }
    let triage = parse_objs("triage-log.edn", |l| l.contains(":received \"2026")).unwrap_or_default();
    let mut inbound = 0;
    for t in triage.iter() {
        if inbound >= 8 {
            break;
        }
        let subj = match as_str(kv(t, "subject")) {
            Some(s) => s,
            None => continue,
        };
        if subj.contains("scrum") || subj.contains("スクラム") || subj.starts_with("キャンセル") {
            continue;
        }
        let s = format!("📨 受信: {subj}");
        if !recent_activity.contains(&s) {
            recent_activity.push(s);
            inbound += 1;
        }
    }

    let burn_jpy = headcount * 700_000; // 月次・1人あたり概算 (215名 ≒ 1.5億/月)
    let mut kpis = Kpis {
        turn: 0,
        // 初期資金 = 約10ヶ月ランウェイ。実人員に対する再建シナリオ。
        cash_jpy: burn_jpy * 10,
        burn_jpy,
        headcount,
        morale: 70,
        // 累計売上は gftd 発行請求書の実額でシード
        revenue_total_jpy: issued_total_jpy,
        pipeline_jpy,
        runway_months: 0.0,
        status: "playing".into(),
    };
    kpis.recompute();

    Ok(Seed {
        kpis,
        pipeline: deals,
        projects,
        recent_activity,
        issued_total_jpy,
        received_total_jpy,
        crm_top,
        contract_parties,
        dormant_projects,
    })
}

/// EDN 文字列リテラルとして安全にエスケープする。
fn edn_str(s: &str) -> String {
    format!("{:?}", s)
}

/// datomic に会社の事実と過去意思決定をシードする (Datalog で読み返せる SSoT)。
pub async fn seed_datomic(conn: &Connection, seed: &Seed) -> Result<usize> {
    let mut edn = String::from("[\n");

    // 会社シングルトン (実集計値つき)
    edn.push_str(&format!(
        "{{:db/id \"company\" :sim.company/name \"gftdcojp\" :sim.company/headcount {} :sim.company/cash-jpy {} :sim.company/burn-jpy {} :gftd.fin/issued-total-jpy {} :gftd.fin/received-total-jpy {}}}\n",
        seed.kpis.headcount, seed.kpis.cash_jpy, seed.kpis.burn_jpy, seed.issued_total_jpy, seed.received_total_jpy
    ));

    // 実契約由来の商談
    for (i, (party, val)) in seed.pipeline.iter().enumerate() {
        edn.push_str(&format!(
            "{{:db/id \"pl{i}\" :gftd.contract/party {} :sim.pipeline/value-jpy {val} :sim.pipeline/stage :open}}\n",
            edn_str(party)
        ));
    }

    // プロジェクト
    for (i, (name, status)) in seed.projects.iter().enumerate() {
        edn.push_str(&format!(
            "{{:db/id \"pj{i}\" :gftd.project/name {} :gftd.project/status :{status}}}\n",
            edn_str(name)
        ));
    }

    // 過去意思決定 (decisions.edn)
    if let Ok(forms) = parse_objs("decisions.edn", |_| true) {
        for (i, d) in forms.iter().enumerate() {
            let id = as_str(kv(d, "id")).unwrap_or_default();
            let note = as_str(kv(d, "note")).unwrap_or_default();
            let policy = kw_name(kv(d, "policy")).unwrap_or_else(|| "reply".into());
            let at = as_str(kv(d, "decided_at")).unwrap_or_default();
            edn.push_str(&format!(
                "{{:db/id \"hist{i}\" :gftd.decision/id {} :gftd.decision/policy :{policy} :gftd.decision/at {} :gftd.decision/note {} :sim.decision/turn 0 :sim.decision/approved true}}\n",
                edn_str(&id), edn_str(&at), edn_str(&note)
            ));
        }
    }

    edn.push_str("]\n");
    let tx = parse(&edn).map_err(|e| anyhow::anyhow!("seed edn parse: {e}"))?;
    let report = conn.transact(tx).await?;
    Ok(report.tx_data.len())
}
