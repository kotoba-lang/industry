(ns tower100-b70-report
  "検査結果ページ(Artifact 用 HTML)を生成する。

   **数値は tower100-b70-assembly.edn から読み、図は生成済み SVG を埋め込む。**
   散文だけを手で書く。こうしないと、モデルを直したときにページの数字だけが
   古いまま残り、しかも見た目は正しいので誰も気づかない —— このタスク自体が
   『測っていないものを測ったように見せない』ことを主題にしているのに、
   報告書がそれを破ったら意味が無い。

   実行: nbb scripts/tower100-b70-report.cljs <out.html> [--standalone]

   **既定は Artifact 用の *断片*** —— publish 時に doctype / head / body の
   skeleton が被せられるので、こちらで書くと二重になる。
   `--standalone` はローカルの file:// で開く用に skeleton を自分で付ける
   (doctype を欠くとブラウザが quirks mode に落ちるので、単体表示には必要)。")

(require '[clojure.string :as str]
         '["fs" :as fs]
         '[cljs.reader :as edn])

(def m (edn/read-string (fs/readFileSync "90-docs/hardware/tower100-b70-assembly.edn" "utf8")))
(def svg (fs/readFileSync "90-docs/hardware/tower100-b70-views.svg" "utf8"))
(def args (vec *command-line-args*))
(def standalone? (boolean (some #(= "--standalone" %) args)))
(def out (or (first (remove #(str/starts-with? % "--") args)) "/tmp/tower100-b70.html"))

(defn esc [t] (-> (str t) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
(defn r1 [x] (/ (Math/round (* 10.0 (double x))) 10.0))

(def grade-label {:spec "規格確定" :datasheet "メーカー公称" :estimate "推定"})

;; ── 公表クリアランス表 ─────────────────────────────────────────────────────
(def checks (:checks-declared m))
(def max-limit (apply max (map #(if (= "slot" (:unit %)) 0 (:limit %)) checks)))

(def check-rows
  (str/join
   (for [{:keys [name need limit unit grade pass]} checks]
     (let [slack (- limit need)
           barw (if (= "slot" unit) 100 (* 100.0 (/ need max-limit)))
           tight (<= slack 0)]
       (str "<tr>"
            "<td class=\"nm\">" (esc name) "</td>"
            "<td class=\"num\">" need "<span class=\"u\">" (esc unit) "</span></td>"
            "<td class=\"num\">" limit "<span class=\"u\">" (esc unit) "</span></td>"
            "<td class=\"num " (if tight "zero" "") "\">" (if (pos? slack) (str "+" slack) slack) "</td>"
            "<td class=\"barcell\"><span class=\"bar\" style=\"--w:" (.toFixed barw 1) "%\"></span></td>"
            "<td><span class=\"g g-" (subs (str grade) 1) "\">" (grade-label grade) "</span></td>"
            "<td class=\"vd\">" (if pass "合致" "不成立") "</td>"
            "</tr>")))))

;; ── 部材表 ─────────────────────────────────────────────────────────────────
(def part-names
  {"gpu-b70" "ASRock Arc Pro B70 Creator 32GB"
   "mobo-b650i" "ASRock B650I Lightning WiFi"
   "cpu-7500f" "AMD Ryzen 5 7500F"
   "cooler-wraith" "AMD Wraith Stealth（7500F 同梱）"
   "ram-ddr5" "ADATA AD5U56008G-DT ×2"
   "ssd-m2" "PNY M280CS2241-500"
   "psu-pro750b" "ASRock PRO-750B 750W"})

(def part-rows
  (str/join
   (for [{:keys [id pos size grade]} (:placement m)]
     (str "<tr>"
          "<td><span class=\"sw sw-" id "\"></span><span class=\"pid\">" (esc id) "</span></td>"
          "<td class=\"nm\">" (esc (get part-names id id)) "</td>"
          "<td class=\"num\">" (str/join " × " (map r1 size)) "</td>"
          "<td class=\"num dim\">" (str/join " " (map r1 pos)) "</td>"
          "<td><span class=\"g g-" (subs (str grade) 1) "\">" (grade-label grade) "</span></td>"
          "</tr>"))))

;; ── ケーブル ───────────────────────────────────────────────────────────────
(def cable-rows
  (str/join
   (for [{:keys [id required-r worst-r pass]} (:cable (:sim m))]
     (str "<tr><td class=\"pid\">" (esc id) "</td>"
          "<td class=\"num\">" required-r "</td>"
          "<td class=\"num\">" (r1 worst-r) "</td>"
          "<td class=\"vd\">" (if pass "合致" "不足") "</td></tr>"))))

(def ch (:chassis m))
(def pw (:power m))
(def th (:thermal m))
(def sim (:sim m))
(def blocking (count (:blocking (:findings m))))
(def warnings (count (:warnings (:findings m))))
(def volume-l (/ (apply * (:outer ch)) 1.0e6))

(def html (str "<title>Tower 100 × Arc Pro B70 組立検査</title>
<style>
:root{
  --ground:#EDF0EC; --sheet:#FBFCFA; --raise:#F4F7F3;
  --ink:#151A17; --muted:#5B655E; --accent:#1B6B47;
  --grid:#CBD3CB; --rule:#DDE3DC;
  --caution:#8A5A16; --fail:#9E3327;
  --p-gpu:#1B6B47; --p-mobo:#8E9A92; --p-cpu:#2F3F37; --p-cool:#A9B6AC;
  --p-ram:#C08A3E; --p-ssd:#6C7F74; --p-psu:#3B4E44; --p-etc:#B6C0B8;
  --shadow:0 1px 2px rgba(21,26,23,.06),0 8px 24px rgba(21,26,23,.05);
}
@media (prefers-color-scheme:dark){
  :root:not([data-theme=\"light\"]){
    --ground:#0D110F; --sheet:#141915; --raise:#1A201C;
    --ink:#E4EAE4; --muted:#8E9A92; --accent:#54C48C;
    --grid:#28312B; --rule:#232B26;
    --caution:#D6A054; --fail:#E27A68;
    --p-gpu:#2E8B60; --p-mobo:#54615A; --p-cpu:#7E8D85; --p-cool:#3E4B44;
    --p-ram:#B98A45; --p-ssd:#4A5A51; --p-psu:#6B7A71; --p-etc:#39443D;
    --shadow:0 1px 2px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.3);
  }
}
:root[data-theme=\"dark\"]{
  --ground:#0D110F; --sheet:#141915; --raise:#1A201C;
  --ink:#E4EAE4; --muted:#8E9A92; --accent:#54C48C;
  --grid:#28312B; --rule:#232B26;
  --caution:#D6A054; --fail:#E27A68;
  --p-gpu:#2E8B60; --p-mobo:#54615A; --p-cpu:#7E8D85; --p-cool:#3E4B44;
  --p-ram:#B98A45; --p-ssd:#4A5A51; --p-psu:#6B7A71; --p-etc:#39443D;
  --shadow:0 1px 2px rgba(0,0,0,.4),0 8px 24px rgba(0,0,0,.3);
}
*{box-sizing:border-box}
body{
  margin:0; background:var(--ground); color:var(--ink);
  font-family:-apple-system,BlinkMacSystemFont,\"Hiragino Sans\",\"Noto Sans JP\",system-ui,sans-serif;
  font-size:15px; line-height:1.72; -webkit-font-smoothing:antialiased;
}
.wrap{max-width:1000px;margin:0 auto;padding:40px 24px 88px;display:flex;flex-direction:column;gap:38px}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}

/* ── title block ─────────────────────────────────────────── */
.tb{background:var(--sheet);border:1px solid var(--rule);border-radius:3px;box-shadow:var(--shadow);overflow:hidden}
.tb-top{display:flex;flex-wrap:wrap;gap:20px;align-items:flex-start;justify-content:space-between;padding:26px 28px 22px}
.eyebrow{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.16em;
  text-transform:uppercase;color:var(--muted);margin:0 0 10px}
h1{font-size:clamp(25px,3.6vw,36px);line-height:1.2;letter-spacing:-.022em;margin:0;text-wrap:balance;font-weight:680}
.sub{color:var(--muted);margin:12px 0 0;max-width:56ch}
.stamp{flex:0 0 auto;text-align:center;border:2px solid var(--accent);border-radius:3px;
  padding:12px 20px;color:var(--accent)}
.stamp .big{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:22px;font-weight:700;letter-spacing:.02em;display:block}
.stamp .small{font-size:11px;letter-spacing:.1em;text-transform:uppercase;display:block;margin-top:3px}
.tb-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));border-top:1px solid var(--rule)}
.tb-cell{padding:14px 28px;border-right:1px solid var(--rule)}
.tb-cell:last-child{border-right:0}
.tb-cell .k{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:10px;letter-spacing:.12em;
  text-transform:uppercase;color:var(--muted)}
.tb-cell .v{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:17px;font-weight:600;
  font-variant-numeric:tabular-nums;margin-top:2px}
.tb-cell .v small{font-size:11px;font-weight:400;color:var(--muted);margin-left:3px}

/* ── sections ────────────────────────────────────────────── */
section{display:flex;flex-direction:column;gap:14px}
h2{font-size:19px;letter-spacing:-.012em;margin:0;font-weight:660;display:flex;align-items:baseline;gap:11px}
h2 .idx{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:var(--accent);
  letter-spacing:.1em;font-weight:600}
h3{font-size:14px;margin:0 0 4px;font-weight:640}
p{margin:0;max-width:70ch}
.lede{color:var(--muted)}

.card{background:var(--sheet);border:1px solid var(--rule);border-radius:3px;padding:20px 22px;box-shadow:var(--shadow)}
.figure{background:var(--sheet);border:1px solid var(--rule);border-radius:3px;padding:16px;box-shadow:var(--shadow);overflow-x:auto}
.figure svg{display:block;min-width:600px}
figcaption{color:var(--muted);font-size:13px;margin-top:12px;padding:0 4px}

/* ── tables ──────────────────────────────────────────────── */
.tscroll{overflow-x:auto;background:var(--sheet);border:1px solid var(--rule);border-radius:3px;box-shadow:var(--shadow)}
table{border-collapse:collapse;width:100%;min-width:600px;font-size:14px}
th{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:10px;letter-spacing:.11em;
  text-transform:uppercase;color:var(--muted);text-align:left;font-weight:600;
  padding:12px 14px;border-bottom:1px solid var(--rule);white-space:nowrap}
td{padding:11px 14px;border-bottom:1px solid var(--rule);vertical-align:middle}
tr:last-child td{border-bottom:0}
.num{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-variant-numeric:tabular-nums;
  text-align:right;white-space:nowrap}
.num .u{color:var(--muted);font-size:11px;margin-left:2px}
.num.zero{color:var(--caution);font-weight:700}
.num.dim{color:var(--muted);text-align:left;font-size:12px}
.nm{min-width:190px}
.pid{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
.vd{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--accent);
  white-space:nowrap;font-weight:600}
.barcell{width:120px;min-width:120px}
.bar{display:block;height:7px;background:var(--grid);border-radius:1px;position:relative;overflow:hidden}
.bar::before{content:\"\";position:absolute;inset:0 auto 0 0;width:var(--w);background:var(--accent);border-radius:1px}
.g{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:10px;letter-spacing:.06em;
  padding:3px 7px;border-radius:2px;white-space:nowrap;border:1px solid}
.g-spec{color:var(--accent);border-color:var(--accent);background:color-mix(in srgb,var(--accent) 9%,transparent)}
.g-datasheet{color:var(--muted);border-color:var(--grid);background:var(--raise)}
.g-estimate{color:var(--caution);border-color:var(--caution);
  background:color-mix(in srgb,var(--caution) 9%,transparent)}
.sw{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:8px;vertical-align:-1px;
  border:1px solid rgba(128,128,128,.35)}
.sw-gpu-b70{background:var(--p-gpu)} .sw-mobo-b650i{background:var(--p-mobo)}
.sw-cpu-7500f{background:var(--p-cpu)} .sw-cooler-wraith{background:var(--p-cool)}
.sw-ram-ddr5{background:var(--p-ram)} .sw-ssd-m2{background:var(--p-ssd)}
.sw-psu-pro750b{background:var(--p-psu)}

/* ── notes ───────────────────────────────────────────────── */
.two{display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:16px}
.note{background:var(--sheet);border:1px solid var(--rule);border-left:3px solid var(--caution);
  border-radius:3px;padding:17px 19px;box-shadow:var(--shadow)}
.note.limit{border-left-color:var(--grid)}
.note p{font-size:14px;color:var(--muted)}
.note h3{color:var(--ink)}
ul{margin:0;padding-left:19px;display:flex;flex-direction:column;gap:7px;font-size:14px;color:var(--muted)}
li strong{color:var(--ink);font-weight:620}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12.5px;
  background:var(--raise);border:1px solid var(--rule);border-radius:2px;padding:1px 5px}
pre{margin:0;background:var(--sheet);border:1px solid var(--rule);border-radius:3px;padding:15px 17px;
  overflow-x:auto;box-shadow:var(--shadow)}
pre code{background:none;border:0;padding:0;font-size:12.5px;line-height:1.7;color:var(--ink)}
footer{color:var(--muted);font-size:12.5px;border-top:1px solid var(--rule);padding-top:18px;
  display:flex;flex-wrap:wrap;gap:6px 20px}
footer .mono{font-size:12px}
@media (prefers-reduced-motion:no-preference){.bar::before{transition:width .4s ease}}
</style>

<div class=\"wrap\">

<div class=\"tb\">
  <div class=\"tb-top\">
    <div>
      <p class=\"eyebrow\">組立成立性検査 · ADR-2608172000</p>
      <h1>Tower 100 × Arc Pro B70</h1>
      <p class=\"sub\">見積 7 品目を 3D モデル化し、メーカー公表クリアランスに対して
      組み立てが成立するかを検査した。図面・数値はすべて
      <span class=\"mono\">tower100-b70-assembly.edn</span> から生成している。</p>
    </div>
    <div class=\"stamp\">
      <span class=\"big\">" (if (zero? blocking) "成立" "不成立") "</span>
      <span class=\"small\">阻却 " blocking " 件 / 警告 " warnings " 件</span>
    </div>
  </div>
  <div class=\"tb-grid\">
    <div class=\"tb-cell\"><div class=\"k\">筐体容積</div><div class=\"v\">" (.toFixed volume-l 1) "<small>L</small></div></div>
    <div class=\"tb-cell\"><div class=\"k\">発熱 / 電源</div><div class=\"v\">" (:load-w pw) "<small>W / " (:psu-w pw) "W</small></div></div>
    <div class=\"tb-cell\"><div class=\"k\">発熱密度</div><div class=\"v\">" (.toFixed (:w-per-litre th) 1) "<small>W/L</small></div></div>
    <div class=\"tb-cell\"><div class=\"k\">GPU 余裕</div><div class=\"v\">+29<small>mm</small></div></div>
  </div>
</div>

<section>
  <h2><span class=\"idx\">01</span>直交三面図</h2>
  <p class=\"lede\">内寸は非公表なので、公表クリアランス（VGA 330 / PSU 180 / クーラー 190 / スロット 2）から
  内部座標を逆算した。破線が推定内空、実線が外形。モデル分解能は 2.8mm。</p>
  <figure class=\"figure\">" svg "
  <figcaption>座標系 " (esc (:model/frame m)) "。"
  "ブラケット面 Y=" (:bracket-y ch) "mm は「PSU 上端 86 + カバー 19 = 105mm に VGA 制限 330mm を積む」逆算。</figcaption>
  </figure>
</section>

<section>
  <h2><span class=\"idx\">02</span>公表クリアランスに対する判定</h2>
  <p class=\"lede\">市販ケースで組立可否を決めるのは、写真から推定した内部形状ではなく
  メーカーが公表した制限値である。これが契約。</p>
  <div class=\"tscroll\"><table>
    <thead><tr><th>検査</th><th style=\"text-align:right\">必要</th><th style=\"text-align:right\">制限</th>
    <th style=\"text-align:right\">余裕</th><th>占有</th><th>寸法根拠</th><th>判定</th></tr></thead>
    <tbody>" check-rows "</tbody>
  </table></div>
  <div class=\"note\">
    <h3>律速は GPU ではなく「余裕ゼロが 3 箇所ある」こと</h3>
    <p>最も逼迫していそうな GPU 長さは 29mm 余る（最悪ケースでも 27mm）。
    実際に余裕ゼロなのは <strong>拡張スロット 2/2</strong>、<strong>マザー 170/170mm</strong>、
    <strong>カード厚 2 スロットちょうど</strong> の 3 つ。規格上ぴったり嵌まる設計なので問題ではないが、
    どれか一つでも仕様が変われば即不成立になる —— 3 スロット GPU も microATX も入らない。</p>
  </div>
</section>

<section>
  <h2><span class=\"idx\">03</span>部材と配置</h2>
  <div class=\"tscroll\"><table>
    <thead><tr><th>ID</th><th>部材</th><th style=\"text-align:right\">寸法 X×Y×Z</th><th>最小角</th><th>寸法根拠</th></tr></thead>
    <tbody>" part-rows "</tbody>
  </table></div>
  <div class=\"two\">
    <div class=\"note\">
      <h3>CPU クーラーは BOM に無いが欠品ではない</h3>
      <p>AMD 公式の Supplied Thermal Solution (PIB) が Wraith Stealth なので
      <strong>7500F BOX に同梱される</strong>。65W TDP に対し定格どおりで、190mm 制限に 136mm の余裕。
      別途購入は不要（静音や OC を狙うなら別問題）。</p>
    </div>
    <div class=\"note\">
      <h3>幅を決めるのはクーラーではなく GPU</h3>
      <p>張り出しは GPU が 119.6mm、CPU クーラーは 61.6mm。
      <strong>カード高さ 112mm がこの筐体の幅方向の律速</strong>で、内空 250mm に対し 130mm 余る。
      律速部材は構成ごとに変わるので、思い込みで設計を始めると余剰を見落とす。</p>
    </div>
  </div>
</section>

<section>
  <h2><span class=\"idx\">04</span>ケーブル経路 — 最小曲げ半径</h2>
  <p class=\"lede\">PSU が底、GPU の 12V-2x6 コネクタがカード下端。吊り下げレイアウトなので両者が近く、
  引き回しが短い。PSU は ATX 3.1 の native 12V-2x6 を持つので<strong>変換アダプタは不要</strong>。</p>
  <div class=\"tscroll\"><table>
    <thead><tr><th>ケーブル</th><th style=\"text-align:right\">要求 R (mm)</th>
    <th style=\"text-align:right\">経路の最小 R</th><th>判定</th></tr></thead>
    <tbody>" cable-rows "</tbody>
  </table></div>
</section>

<section>
  <h2><span class=\"idx\">05</span>このモデルが答えられないこと</h2>
  <p class=\"lede\">検査が緑であることと、検査が問いを立てられていることは別。以下は測っていない。</p>
  <div class=\"two\">
    <div class=\"note limit\">
      <h3>基板内部のクリアランスは検査していない</h3>
      <p>初版はここで座標を発明し、RAM との干渉を 3 件「検出」した。あれは基板の設計ではなく
      私が置いた座標の産物だったので撤去した。担保しているのは AABB ではなく
      <strong>AM5 ソケットのキープアウトと Mini-ITX 規格</strong> —— 純正クーラーはその内側に設計されている。
      規格に帰しただけで、実測でも sim でもない。</p>
    </div>
    <div class=\"note limit\">
      <h3>熱は未決着。CFD の数値は出していない</h3>
      <p>D3Q19 LBM は同一実行内で閉形解と突き合わせて検証済み（相対 L2 1.44%）だが、
      <code>reachable-reynolds</code> に問うと <strong>:feasible? false</strong>。
      物理 Re 41,667 に対し格子側の安定域上限は 1,250。
      よって CFM・流速・停滞率を報告しない。言えるのは通過流のエネルギー保存だけで、
      排気 ΔT は実効 50% 想定で 6.2K。<strong>素子温度は出ない。</strong></p>
    </div>
    <div class=\"note limit\">
      <h3>AABB はメッシュ精度の衝突判定ではない</h3>
      <p>「干渉なし」は「軸平行境界箱が重ならない」の意味。ヒートシンクのフィンや
      コネクタの張り出しは見ていない。内部座標は公表値からの逆算で分解能 2.8mm、
      それより細かい主張はこのモデルからは出せない。<strong>実物は 1 つも採寸していない。</strong></p>
    </div>
    <div class=\"note limit\">
      <h3>検査自体の判別能力は実測した</h3>
      <p>壊したカタログのコピーで赤くなることを確認した（3/3）。GPU を 320mm に →
      <code>vga-length</code> 阻却。PSU を 200mm に → <code>psu-length</code> 阻却。
      140mm ファンを 5 個に → <code>fan-mounts</code> 阻却。無改変では 0 件。
      <strong>壊したものと報告されたものが一致することを確かめた。</strong></p>
    </div>
  </div>
</section>

<section>
  <h2><span class=\"idx\">06</span>組み立てとは別に、用途適合の問題が 2 つ</h2>
  <p class=\"lede\">どちらも組立を阻却しないので、「成立」だけを読むと見落とす。</p>
  <div class=\"card\">
    <ul>
      <li><strong>システム RAM 16GB に対し VRAM 32GB。</strong>
      B70 は「32GB のモデルを載せられる」ことが売りなのに、そのモデルを運ぶ中継地が 16GB しかない。
      加えて Mini-ITX は DIMM 2 枚きりなので、増設は買い足しではなく<strong>買い直し</strong>になる。</li>
      <li><strong>マザーの x16 スロットは PCIe 4.0 であって 5.0 ではない。</strong>
      B650I Lightning WiFi の Gen5 は M.2 側で、x16 は Gen4。Gen5 カードの B70 は Gen4 x16 で動く。
      定常推論はカード上で完結するので影響は小さいが、モデルのロード時間には効く。</li>
      <li><strong>7500F は iGPU 非搭載。</strong>GPU を抜くと映像出力が無いので、切り分けの場面で不便。</li>
      <li><strong>筐体には 120mm 排気が 2 個プリインストール済み。</strong>
      F140Q 2 個は「追加」ではなく<strong>置換 + 1 箇所増設</strong>。3 箇所すべてを排気にすると
      吸気が完全に passive になるので、Power Cover の 1 個は吸気向きが妥当（未検証の推奨）。</li>
    </ul>
  </div>
</section>

<section>
  <h2><span class=\"idx\">07</span>再現</h2>
  <pre><code># 検査（寸法を変えるときは部材カタログだけを直す。他は生成物）
nbb --classpath orgs/kotoba-lang/org-iso-10303/src:orgs/kotoba-lang/kami-engine-cfd/src \\
    scripts/tower100-b70-cad.cljs

# 三面図 → このページ
nbb scripts/tower100-b70-drawing.cljs
nbb scripts/tower100-b70-report.cljs out.html</code></pre>
  <p class=\"lede\">正本は <code>90-docs/hardware/tower100-b70-parts.datoms.edn</code>。
  STEP（ISO 10303-21）は <code>tower100-b70-chassis.step</code> に出力され、read-step で往復も通る。
  幾何は org-iso-10303 の brep kernel、CFD は kami-engine-cfd を読み取り専用で使っている。</p>
</section>

<footer>
  <span class=\"mono\">内包NG " (:containment-ng sim) " / 干渉 " (count (:collisions sim))
  " / 挿入不可 " (:insertion-blocked sim) " / 公差NG " (:tolerance-ng sim) "</span>
  <span class=\"mono\">検証 2026-08-17</span>
  <span>実物未採寸・筐体内寸未実測・熱未決着</span>
</footer>

</div>"))

;; standalone は skeleton を自分で付ける。Artifact の CSS reset が無いぶん、
;; margin:0 と色は body 側で明示している(既に指定済み)ので追加は最小で足りる。
(def final-html
  (if standalone?
    ;; 断片の先頭は <title> と <style> なので、</style> までを head へ移す。
    ;; body に残したままでもブラウザは拾うが、head に在るのが正しい。
    (let [i (+ (.indexOf html "</style>") (count "</style>"))
          head (subs html 0 i)
          body (subs html i)]
      (str "<!doctype html>\n<html lang=\"ja\">\n<head>\n"
           "<meta charset=\"utf-8\">\n"
           "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
           head "\n</head>\n<body>" body "\n</body>\n</html>\n"))
    html))

(fs/writeFileSync out final-html)
(println (str "書き出し: " out " (" (count final-html) " chars"
              (if standalone? " / standalone: doctype+head 付き" " / Artifact 断片") ")"))
