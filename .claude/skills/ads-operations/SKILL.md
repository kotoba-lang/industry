---
name: ads-operations
description: この workspace の「広告」は 4 つの別物が同じ語の下に同居している（自社配信 / 自社 campaign lifecycle / 外部出稿の判定層 / 外部出稿の実行層）。どれが live でどれが設計だけかを実測付きで示し、混同したまま「出稿できるはず」と結論するのを止める。広告・ad・ads・出稿・campaign・adnet・adserver・Google Ads・ChatGPT Ads・placement・house ad・広告収益・RPM で発火。Claude 自身に広告を出す話（Anthropic の広告プロダクト）もここが答える。
---

# 広告 — 4 つの面。1 つだと思って読まない

ADR-2607302100 が固定した境界がこの skill の中心である。**同じ「広告出稿」という
言葉の下に 4 つの別物が同居しており、混同すると「設計済みだから動いているはず」
という誤読が発生する。**

| # | 面 | 何をするか | 正本 | 2026-08-22 実測 |
|---|---|---|---|---|
| 1 | **自社配信（serve）** | 自分の面に広告枠を出し、承認済み在庫か house copy を返す | `kotoba-lang/adnet`（pure `.cljc` auction）/ `cloud-itonami/adserver`（Worker + x402 USDC）/ `cloud-murakumo` の `ads.cljc` + `ads_http.cljs` | murakumo だけ **live**。adserver は **not live** |
| 2 | **自社 campaign lifecycle** | propose → govern → commit で自社 ad network の campaign を動かす | `network-awai/cloud-itonami` の `adnetwork.cljc` / `advertiser_ops.cljc` | 純 `.cljc`、**ネットワーク I/O ゼロ** |
| 3 | **外部出稿の判定層** | 法域（景表法/FTC/CAP/UWG）× 媒体ポリシー（chatgpt-ads / google-ads / meta-ads / microsoft-advertising）の HARD gate | `cloud-itonami/cloud-itonami-isic-7310` | 実装済み。ただし `:actuation/place-campaign` は**台帳にレコードを起票するだけ** |
| 4 | **外部出稿の実行層** | 実際に Google/OpenAI に campaign を作る・止める | かつて `cloud-itonami/yukkuri` の Python + Playwright | **repo から撤去済み**（下記） |

**5 つ目と紛らわしいもの**: `kotoba-lang/senden`（宣伝）は**自社の campaign を測る**
marketing 分析ライブラリで、他人の広告を配信する adnet とは別物。

## Claude（Anthropic）に広告は出せない — プロダクトが存在しない

**Anthropic は 2026-02-04 の「Claude is a space to think」で Claude を ad-free に
保つと公に宣言している**（"Claude will remain ad-free. Our users won't see
'sponsored' links adjacent to their conversations."）。広告 API も sponsored
placement も developer 向け広告プロダクトも無い。**「Claude Ads」への出稿を計画に
入れない。** ChatGPT Ads（`ads.openai.com`）は別物で、そちらは 7310 のポリシー
カタログに一級市民として入っている（ADR-2607301200）。

## 今日 live なのは 1 つだけ（実測 2026-08-22）

```bash
# 面 1: murakumo の first-party placement — 200 / kind:"house"
curl -sS 'https://murakumo.cloud/api/v1/ads/placement?placement=go-sidebar&locale=en'
```

- 返るのは house copy（"This placement is available on murakumo.cloud" → `/advertise`）。
  **house は「まだ広告主が居ない」の意であって、有料広告ではない。**
- **placement id は `go-sidebar` の 1 つだけ**（`ads/allowed-placements`）。
  `normalize-params` が未知の id を黙って `go-sidebar` に丸めるので、**新しい枠名を
  投げても 200 が返る** — 200 を「その枠が在る」と読まない。
- `/advertise` は intake only。`validate-apply` は `:payment "none"` を固定で付け、
  `apply-confirmation` が「掲載は自動ではない、operator が審査する」と明言する。
- 保存先は既存の `INFER_MEMORY_KV`（prefix `ads:`）。新しい vendor は入っていない。
- **`ads.cljc` の設計不変条件を壊さない**: placement 決定は placement id と locale
  しか見ない。`prompt` / `message` / `q` / `completion` 等（`user-content-keys`）は
  読まない・保存しない・エコーしない。`contains-user-content?` がその負テスト。
  live で両方向を確認できる（実測 2026-08-22、両方ともそのとおりだった）:

```bash
# 未知の枠名 → 黙って go-sidebar に丸められて 200。「その枠が在る」ではない
curl -sS 'https://murakumo.cloud/api/v1/ads/placement?placement=does-not-exist-9f2&locale=ja'
# 仕込んだ canary が応答に出ないこと（出たら user-content の遮断が壊れている）
curl -sS 'https://murakumo.cloud/api/v1/ads/placement?placement=go-sidebar&prompt=CANARY7X3&q=CANARY7X3' | grep -q CANARY7X3 && echo LEAK || echo clean
```

```bash
# 面 1: adserver — NXDOMAIN。live ではない
curl -sS -m 10 https://ads.x402.nexus/health
```

`orgs/cloud-itonami/adserver/wrangler.jsonc` は `routes` が**コメントアウト**され、
`CAMPAIGNS_JSON` は `"[]"`。つまり**配線されておらず、campaign も 0 件**。
README が書く `/serve` `/event` `/topup` は実装であって稼働ではない。

## 実行層は撤去されている（この skill が最も伝えたい 1 点）

ADR-2607302100（2026-07-30）は「実際に動いていた経路」を
`lg/lg_yukkuri/graphs/manage_ads.py`（396 行、Python + LangGraph + Playwright で
Google 広告 **web UI** を操作）と記録した。**その後に撤去された。**

```
329e14e  Replace lg/lg_yukkuri Python LangGraph pod with a clj/cljc runtime
```

後継の `clj/src/yukkuri/graphs/manage_ads.cljc`（280 行）は docstring で自ら
*"This namespace performs NO browser/HTTP/D1 IO"* と宣言する **pure planner** で、
移植されたのは plan builder・予算上限の算術・mode router・UI 操作列を**データとして**
持つ部分だけ。**Ads UI を実際に駆動する部分は移植されていない。**

したがって **2026-08-22 現在、外部媒体に campaign を作る/止める実行経路は
workspace 内に無い。** 面 3 は判定するだけ、面 4 は planner だけ。
「7310 があるから出稿できる」は誤り。

⚠ docstring は今も `lg/lg_yukkuri/browser_agent.py` を参照しているが、そのファイルは
repo に無い。**コメントの参照先が在ることを確かめずに追わない。**

## Google Ads API はトークンがあっても対象アカウントに届かない

ADR-2607302100 の実測（2026-07-30、値は表示していない）:

| | yukkuri MCC 459-659-5747 | GFTD Ads Manager MCC 557-192-1235 |
|---|---|---|
| developer token | あり | あり |
| アクセス権 | **テストアカウント** | **ベーシック** |
| 管理下アカウント | **0 件** | 640-633-3631 のみ |

**実際に出稿していた「ゆっくりサイバーch」(798-261-2098) はどの MCC 配下にも
入っていない。** だから `:auth-readiness` は
`:oauth-verified-developer-token-test-only`、smoke の失敗は
`:target-customer-not-accessible` になる。**問題は「トークンが無い」ではなく
「使えるトークンと対象アカウントが繋がっていない」。** これを直さずに API 経路の
実装を書き始めない。

なお provider catalog（`cloud-itonami/adnetwork_providers.cljc`）が指す
`kotoba-lang/com-googleapis-googleads` は **west に無い**。名前の近い
`etzhayyim/com-google-ads` は別物（outreach actor、`src/` 無し）。

## 金の面 — ここは恒久承認の外

CLAUDE.md の恒久承認は deploy と公開には及ぶが、**安全床②（資金の売買・送金・変換）は
不変**。広告費は payment method on file からの支出なので:

- **campaign の作成・再開・予算増額は、実支出を発生させる操作**である。実行経路が
  復活したとしても、**owner が名指しした scope の外の campaign に触らない**
  （ADR-2607302100 D2 の実例: 対象は shiropico/ghosthacker、`yk-cyber-*` は
  「status が有効のまま残っている 5 件」を事実として記録しただけで揃えなかった）。
- 停止（pause）は支出を減らす方向なので、owner の依頼があれば実行してよい。
- `manage_ads.cljc` の `budget-cap-jpy` は既定 10,000 円（env
  `YUKKURI_ADS_BUDGET_CAP_JPY`、operator directive 2026-06-14「予算内ならゲート
  なしで OK」）。**この承認は lg-yukkuri の A/B 出稿に限定されたもので、
  workspace 全体の広告費の白紙委任ではない。**
- 支払い手段の入力・アカウント作成は安全床①のとおり自分でやらない。
- **campaign 名は builder の出力と一致するとは限らない。** `campaign-name` は
  `yk-cyber-<lang>-pqc-<arm>-2606` 固定で、実在した
  `yk-shiropico-ep01-en-infeed-260719` は**この builder からは出てこない**。
  命名規約から「どの経路が作ったか」を推定しない。

## 収益の計算は既に済んでいる（作り直さない）

ADR-2608096000（accepted、2026-08-08）が RPM を仮定せずに逆算している:

- 自社 PV 合計 **31,825 / 7d ≈ 136,392 / 月**（9 zone、`90-docs/business/metrics/*.edn` の `:zone`）
- 月次 ¥300,000 に必要な RPM = **¥2,200 / 1000PV** → 日本語 display の実勢の約 1 桁上
- 結論: **現在の在庫では第三者 display 広告で意味のある収益は出ない。** 出るとすれば
  USDC 建ての direct deal か、在庫を 10 倍にする経路
- 唯一の第三者実績 ExoClick の **USD 0 / imp 0 は RPM の代理値ではない**（画像配信が
  522 で全滅した fill の失敗）
- leverage 順（`dynamics/rank-interventions`）: 2.1 adnet を自社 publisher へ配線 →
  2.0 USDC 入札の広告主 1 件 → 1.8 在庫を増やす。**third-party ad network への展開は
  0.4 で最下位。**

**順序は「配線 → 在庫 → 広告主」で、逆順にやると ExoClick の再演になる。**
最初の広告主が自社 product でよいが、**North Star の「外部の非 owner 実入金」には
数えない**（自己購入だから）。

⚠ 段 2 の在庫 pipeline として ADR が挙げる `cloud-itonami/media-gamers`
（`kouryaku/`）は **west に登録が無く checkout も無い**（`repo-search` 実測。
west に在るのは `etzhayyim/com-etzhayyim-media-gamers` という別 entry）。
手元から回せる前提で計画を書かない。

## robots を迂回しない

`kouryaku/sources.edn` の実測で、zelda.fandom / terraria.wiki.gg / minecraft.wiki は
`ClaudeBot` を名指し Disallow または Cloudflare challenge のため**使えない**と判定
済み。使っているのは pokeapi と wikidata だけ。**この判断を覆さない**（安全床④）。
攻略情報が最も濃い game wiki の大半は構造的に使えない、が実測の結論。

## 「広告は動いていない」と言う前に測る

状態を推測しない。3 つとも別の場所に答えがある:

```bash
# 面 1 が live か
curl -sS 'https://murakumo.cloud/api/v1/ads/placement?placement=go-sidebar&locale=en'
curl -sS -m 10 https://ads.x402.nexus/health || echo "adserver not routed"

# 面 1 の在庫が 0 でないか（house が返るなら listed は空）
grep -n 'CAMPAIGNS_JSON' orgs/cloud-itonami/adserver/wrangler.jsonc

# 面 4 の実行経路が復活していないか
git -C orgs/cloud-itonami/yukkuri ls-files | grep -i 'browser_agent\|manage_ads'
```

**Google 広告アカウント側の現在の status は、この workspace からは測れない**
（credential と browser が要る）。ADR-2607302100 の「一時停止した」は
**2026-07-30 時点の観測**であって、今日の状態ではない。今の status を報告するなら
実際にアカウントを見てから書く。見ていないなら「未測定」と書く。

## 読む順

1. `90-docs/adr/2607302100-…` — 4 面の境界と Google Ads 実測（**最初にこれ**）
2. `90-docs/adr/2608096000-…` — 収益計画と leverage 順、必要 RPM の逆算
3. `90-docs/adr/2607301200-…` — 7310 を判定層に留める決定、ChatGPT Ads preflight
4. `90-docs/adr/2607093500-…` — adnet first-party ad network の設計
5. `orgs/network-awai/cloud-murakumo/public/docs/ads-go.md` — live な面の運用文書
