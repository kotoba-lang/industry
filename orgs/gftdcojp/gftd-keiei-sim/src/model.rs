//! ゲームのドメインモデル: 経営KPIと社員エージェントの提案。

use serde::Serialize;

/// 経営KPI。AppState が権威コピーを持ち、毎ターン/承認で更新する。
/// datomic には監査用に各ターン・各意思決定を別途記録する。
#[derive(Clone, Debug, Serialize)]
pub struct Kpis {
    pub turn: i64,
    /// 現金残高 (円)
    pub cash_jpy: i64,
    /// 月次バーンレート (円)
    pub burn_jpy: i64,
    /// 社内人員 (people.edn の :person/role :internal 実数でシード)
    pub headcount: i64,
    /// 士気 0..100
    pub morale: i64,
    /// 累計売上 (円)
    pub revenue_total_jpy: i64,
    /// 商談パイプライン総額 (円) — crm.edn 上位商談からヒューリスティック算出
    pub pipeline_jpy: i64,
    /// ランウェイ (月) = cash / burn
    pub runway_months: f64,
    /// "playing" | "bankrupt"
    pub status: String,
}

impl Kpis {
    /// 派生値(ランウェイ・倒産判定)を再計算する。
    pub fn recompute(&mut self) {
        self.runway_months = if self.burn_jpy > 0 {
            (self.cash_jpy as f64) / (self.burn_jpy as f64)
        } else {
            999.0
        };
        if self.cash_jpy < 0 {
            self.status = "bankrupt".into();
        }
    }
}

/// 社員エージェントの提案カード。
#[derive(Clone, Debug, Serialize)]
pub struct Proposal {
    pub id: String,
    /// "sales" | "eng" | "finance" | "ceo"
    pub role: String,
    /// 表示用ラベル
    pub role_label: String,
    /// LLM が生成した提案文
    pub action: String,
    /// "pending" | "approved" | "rejected"
    pub status: String,
    /// 承認した場合の KPI 影響(プレビュー)
    pub effect_hint: String,
}

/// 役割表示ラベル。
pub fn role_label(role: &str) -> &'static str {
    match role {
        "sales" => "営業責任者",
        "eng" => "エンジニアリング責任者",
        "finance" => "財務責任者 (CFO)",
        "ceo" => "CEO補佐 (経営参謀)",
        _ => "社員",
    }
}

/// 承認時の KPI 影響プレビュー文。
pub fn effect_hint(role: &str) -> &'static str {
    match role {
        "sales" => "主要商談を受注: 現金+2億 / 売上+2億 / パイプライン-2億 / 士気+3",
        "eng" => "増員5名 & プロダクト改善: 人員+5 / バーン+350万/月 / パイプライン+0.8億 / 士気+5",
        "finance" => "コスト最適化: バーン-1500万/月 (複利で効く) / 士気-2",
        "ceo" => "戦略の明確化: パイプライン+1.2億 / 士気+8",
        _ => "",
    }
}

/// 役割ごとの KPI 効果を適用し、意思決定ノートを返す。
pub fn apply_effect(role: &str, k: &mut Kpis) -> String {
    let note = match role {
        "sales" => {
            k.cash_jpy += 200_000_000;
            k.revenue_total_jpy += 200_000_000;
            k.pipeline_jpy = (k.pipeline_jpy - 200_000_000).max(0);
            k.morale = (k.morale + 3).min(100);
            "営業提案を承認: 主要商談を受注 (+2億)"
        }
        "eng" => {
            k.headcount += 5;
            k.burn_jpy += 3_500_000;
            k.pipeline_jpy += 80_000_000;
            k.morale = (k.morale + 5).min(100);
            "エンジニアリング提案を承認: 増員+プロダクト改善"
        }
        "finance" => {
            k.burn_jpy = (k.burn_jpy - 15_000_000).max(10_000_000);
            k.morale = (k.morale - 2).max(0);
            "財務提案を承認: コスト最適化 (バーン-1500万/月)"
        }
        "ceo" => {
            k.pipeline_jpy += 120_000_000;
            k.morale = (k.morale + 8).min(100);
            "CEO補佐の具申を承認: 戦略の明確化"
        }
        _ => "提案を承認",
    };
    k.recompute();
    note.to_string()
}
