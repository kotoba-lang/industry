;; 生成済み product face を各 repo の main へ **サーバ側 single-file commit** で
;; 反映する(GitHub Contents API)。387 repo を clone/branch/merge せずに済み、
;; blob SHA の楽観ロックで競合が構造的に起きない — CLAUDE.md が west.yml の
;; pin 前進に使えと言っている経路と同じ考え方。
;;
;; usage:
;;   GH_TOKEN=... nbb land_product_faces.cljs <generated-root> <list-file> [--execute]
;; --execute が無ければ dry-run(何件変わるかだけ数える)。
(require '[clojure.string :as str]
         '["fs" :as fs]
         '["path" :as path]
         '["child_process" :as cp])

(def ^:private api-base "https://api.github.com/repos/cloud-itonami/")
(def ^:private file-path "docs/index.html")

(defn- gh
  "gh api を同期で叩いて JSON を返す。失敗時は {:error status}。"
  [method url & [body]]
  (try
    (let [args (cond-> ["api" "-X" method url]
                 body (concat ["--input" "-"]))
          out (cp/execSync (str "gh " (str/join " " (map #(str "'" % "'") args)))
                           (cond-> #js {:encoding "utf8" :stdio #js ["pipe" "pipe" "pipe"]}
                             body (doto (aset "input" (js/JSON.stringify (clj->js body))))))]
      (js->clj (js/JSON.parse out) :keywordize-keys true))
    (catch :default e
      {:error (or (some-> (.-stderr e) str) (str e))})))

(defn- remote-file [repo]
  (gh "GET" (str api-base repo "/contents/" file-path)))

(defn- put-file! [repo sha content-b64 message]
  (gh "PUT" (str api-base repo "/contents/" file-path)
      {:message message :content content-b64 :sha sha :branch "main"}))

(def message
  (str "ui: product face を デジタル庁デザインシステム(DADS)へ移行\n\n"
       "共有テンプレート由来の docs/index.html を kotoba-lang/jp-go-digital-design-system\n"
       "で再描画したもの(superproject ADR-2607261600 / follow-up 1)。\n\n"
       "**本文は既存ページから抽出して持ち越しており、再導出していない** — repo 種別で\n"
       "badge の書式が違い blueprint.edn を持たない repo もあるため、presentation 層\n"
       "だけを差し替えている。fleet 全 387 repo で本文一致を検証済み\n"
       "(358 完全一致 / 29 は README 由来の blockquote マーカー \"> \" の除去のみ。\n"
       "矢印 -> や ⊣ や実体参照の欠落は 0 件)。\n\n"
       "生成器: com-junkawasaki/root の scripts/gen-product-face.cljs\n"
       "(CSS は EDN、markup は hiccup。生 CSS / 生 HTML は書かない)\n\n"
       "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"))

(let [[root list-file & flags] *command-line-args*
      execute? (some #{"--execute"} flags)
      repos (->> (fs/readFileSync list-file "utf8") str/split-lines
                 (map str/trim) (remove str/blank?) vec)
      tally (atom {:same 0 :changed 0 :updated 0 :error 0 :missing 0})]
  (println (str (if execute? "EXECUTE" "DRY-RUN") " " (count repos) " repos"))
  (doseq [[i repo] (map-indexed vector repos)]
    (let [local (path/join root repo "docs" "index.html")]
      (if-not (fs/existsSync local)
        (swap! tally update :missing inc)
        (let [new-content (fs/readFileSync local "utf8")
              remote (remote-file repo)]
          (cond
            (:error remote)
            (do (swap! tally update :error inc)
                (println (str "  ERROR get " repo " " (subs (str (:error remote)) 0 120))))

            :else
            (let [cur (-> (:content remote) (str/replace #"\s" "")
                          (#(js/Buffer.from % "base64")) (.toString "utf8"))]
              (if (= cur new-content)
                (swap! tally update :same inc)
                (do
                  (swap! tally update :changed inc)
                  (when execute?
                    (let [b64 (.toString (js/Buffer.from new-content "utf8") "base64")
                          r (put-file! repo (:sha remote) b64 message)]
                      (if (:error r)
                        (do (swap! tally update :error inc)
                            (println (str "  ERROR put " repo " " (subs (str (:error r)) 0 120))))
                        (swap! tally update :updated inc))))))))))
      (when (zero? (mod (inc i) 50))
        (println (str "  ... " (inc i) "/" (count repos) " " (pr-str @tally))))))
  (println (str "done " (pr-str @tally))))
