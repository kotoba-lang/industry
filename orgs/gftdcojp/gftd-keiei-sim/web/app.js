// gftd-keiei-sim ダッシュボード — サーバ権威 (/api) + SSE ライブ更新。

const $ = (id) => document.getElementById(id);
const oku = (v) => (v / 1e8).toFixed(2) + "億";
const man = (v) => Math.round(v / 1e4).toLocaleString() + "万";

function kpiClass(name, k) {
  if (name === "runway") return k.runway_months < 4 ? "bad" : k.runway_months < 8 ? "warn" : "good";
  if (name === "cash") return k.cash_jpy < 0 ? "bad" : k.cash_jpy < 3e7 ? "warn" : "good";
  if (name === "morale") return k.morale < 30 ? "bad" : k.morale < 55 ? "warn" : "good";
  return "";
}

function renderKpis(k) {
  const items = [
    ["現金残高", oku(k.cash_jpy) + "円", kpiClass("cash", k)],
    ["ランウェイ", k.runway_months.toFixed(1) + "ヶ月", kpiClass("runway", k)],
    ["社内人員", k.headcount + "名", ""],
    ["士気", k.morale + " / 100", kpiClass("morale", k)],
    ["累計売上", oku(k.revenue_total_jpy) + "円", ""],
    ["パイプライン", oku(k.pipeline_jpy) + "円", ""],
  ];
  $("kpis").innerHTML = items
    .map(
      ([label, value, cls]) =>
        `<div class="kpi"><div class="label">${label}</div><div class="value ${cls}">${value}</div></div>`
    )
    .join("");
}

function renderProposals(props) {
  const wrap = $("proposals");
  const pending = props.filter((p) => p.status === "pending");
  $("empty-proposals").style.display = props.length ? "none" : "block";
  wrap.innerHTML = props
    .map((p) => {
      const resolved = p.status !== "pending";
      const tag = resolved
        ? `<span class="tag ${p.status}">${p.status === "approved" ? "✓ 承認済み" : "✕ 却下"}</span>`
        : "";
      const btns = resolved
        ? ""
        : `<div class="btns">
             <button class="approve" onclick="decide('${p.id}','approve')">承認する</button>
             <button class="reject" onclick="decide('${p.id}','reject')">却下する</button>
           </div>`;
      return `<div class="card ${p.role} ${resolved ? "resolved" : ""}">
          <div class="role">${p.role_label} ${tag}</div>
          <div class="action">${escapeHtml(p.action)}</div>
          <div class="effect">承認時: ${escapeHtml(p.effect_hint)}</div>
          ${btns}
        </div>`;
    })
    .join("");
}

function renderLedger(rows) {
  $("ledger").innerHTML = rows
    .map(
      (r) =>
        `<li class="${r.approved ? "" : "rej"}"><span class="t">T${r.turn}</span>${escapeHtml(r.note)}</li>`
    )
    .join("");
}

function renderIntel(it) {
  if (!it) return;
  $("intel-depth").textContent = it.intel_depth || 0;
  const stageBadge = (st) =>
    st === "engaged" ? '<span class="stage eng">engaged</span>' : '<span class="stage new">new</span>';
  $("leads").innerHTML = (it.latent_leads || [])
    .slice(0, 6)
    .map((l) => `<li class="kv"><span>${escapeHtml(l.subject)}</span><span class="r">${l.score} ${stageBadge(l.stage)}</span></li>`)
    .join("");
  $("revival").innerHTML = (it.revival || [])
    .slice(0, 5)
    .map((r) => `<li class="kv"><span>${escapeHtml(r.subject)}</span><span class="amt">${r.score}件</span></li>`)
    .join("");
  $("deps").innerHTML = (it.dependencies || [])
    .slice(0, 6)
    .map((e) => `<li class="kv"><span>${escapeHtml(e.to)}</span><span class="amt">${man(e.value_jpy)}万</span></li>`)
    .join("");
}

function renderReal(s) {
  $("fin").innerHTML = `
    <div class="fin-row"><span>発行請求 (売上累計)</span><strong class="good">${oku(s.issued_total_jpy)}円</strong></div>
    <div class="fin-row"><span>受領請求 (コスト累計)</span><strong class="bad">${oku(s.received_total_jpy)}円</strong></div>
    <div class="fin-row"><span>差引</span><strong>${oku(s.issued_total_jpy - s.received_total_jpy)}円</strong></div>`;
  $("pipeline").innerHTML = (s.pipeline || [])
    .slice(0, 8)
    .map((p) => `<li><span class="party">${escapeHtml(p.party)}</span><span class="amt">${man(p.value_jpy)}万</span></li>`)
    .join("");
  $("activity").innerHTML = (s.recent_activity || [])
    .slice(0, 8)
    .map((a) => `<li class="act">${escapeHtml(a)}</li>`)
    .join("");
}

function escapeHtml(s) {
  return (s || "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}

async function refresh() {
  const r = await fetch("/api/state");
  const s = await r.json();
  renderKpis(s.kpis);
  renderProposals(s.proposals);
  renderLedger(s.ledger || []);
  renderReal(s);
  renderIntel(s.intel);
  $("turn-badge").textContent = "ターン " + s.kpis.turn;
  const badge = $("llm-badge");
  badge.textContent = s.llm_live ? "実LLM接続" : "スタブLLM";
  badge.className = "badge " + (s.llm_live ? "live" : "stub");
  $("gameover").classList.toggle("hidden", s.kpis.status !== "bankrupt");
  $("advance").disabled = s.kpis.status === "bankrupt";
}

async function advance() {
  $("advance").disabled = true;
  $("advance").textContent = "社員が思考中…";
  await fetch("/api/turn/advance", { method: "POST" });
  $("advance").textContent = "次の四半期へ ▶";
  await refresh();
  $("advance").disabled = false;
}

async function decide(id, what) {
  await fetch(`/api/proposal/${id}/${what}`, { method: "POST" });
  await refresh();
}
window.decide = decide;

$("advance").addEventListener("click", advance);

// SSE: サーバ側の変化で再取得
const es = new EventSource("/api/events");
es.onmessage = () => refresh();

refresh();
