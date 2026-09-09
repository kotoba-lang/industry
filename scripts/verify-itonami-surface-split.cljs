#!/usr/bin/env nbb
;; verify-itonami-surface-split.cljs — cloud-itonami-app が cli / apex に渡した
;; 面が、渡したあとも一致しているか。
;;
;;   nbb scripts/verify-itonami-surface-split.cljs [--root <path>] [--findings]
;;
;; ## なぜこれが fleet gate ではないのか
;;
;; **答えを出すのに 3 つの checkout が同時に要るから。** fleet gate は対象 repo の
;; tree 1 本しか配らない（manifest/orgs-detectors.edn の冒頭、ADR-2608124800）ので、
;; ここに置いた検査は node の上で「相手が見つからない」としか言えない。
;; cloud-itonami-cli は自分の側に `scripts/verify-pinned-surface.cljs` を持っていて、
;; それは正しく exit 2 (REFUSED) を返す —— 正しく返し続ける。**恒久的に答えられない
;; 検査は、恒久的に緑な検査と同じだけ無内容である。**
;;
;; だから superproject に置く。orgs/ を持っている checkout はここだけで、
;; app・cli・apex の 3 本が同時に読めるのもここだけである。
;;
;; ## 何を測るか —— 3 家族
;;
;;   A  cli-pin        cloud-itonami-cli が pin した 4 つの生成物が、それを生成する
;;                     app の resources/ と一致するか
;;   B  worker-copy    apex が持つ Worker の *deploy 可能なコピー* が app に残って
;;                     いないか。残っていれば、その 2 つが一致するかも見る
;;   C  ingress-pin    app が pin した agent-edge の write table が、apex の実 Worker
;;                     の POST 表と一致するか
;;
;; B が「未使用ファイル」ではない理由は 2026-09-09 に実測した: app に残っていた
;; 4 コピーは **apex の原本と同じ Worker 名・同じ custom_domain** を持っており、
;; そのうち `itonami-fleet-dispatch` は既にずれていた（app 側に `capital` が無い）。
;; release に fast-forward 検査は無いので、**どちらの tree から出したかを誰も
;; 測っていない状態で、後から出した方が勝つ。**
;;
;; ## 出力
;;
;;   0  clean（比較が 1 件以上成立し、finding が無い）
;;   1  findings
;;   2  REFUSED —— 必要な checkout が無い / 比較が 1 件も成立しなかった。
;;      **0 と混ぜない。** 「相手が居なかったので何も比較しなかった」を pass として
;;      返すのが、この workspace が 14 箇所で踏んだ形そのものである。

(ns verify-itonami-surface-split
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]))

(def argv (js->clj (.slice js/process.argv 2)))

(defn- flag-value [name]
  (second (drop-while #(not= % name) argv)))

(def root (or (flag-value "--root") (js/process.cwd)))
(def findings-mode? (some #{"--findings"} argv))

(def repos
  {:app  (path/join root "orgs" "cloud-itonami" "cloud-itonami-app")
   :cli  (path/join root "orgs" "cloud-itonami" "cloud-itonami-cli")
   :apex (path/join root "orgs" "cloud-itonami" "cloud-itonami-apex")})

(defn- dir? [p] (and (fs/existsSync p) (.isDirectory (fs/statSync p))))
(defn- file? [p] (and (fs/existsSync p) (.isFile (fs/statSync p))))

(defn- digest
  "sha256 prefix of a file, or nil when it is not there. nil is NOT a digest --
   every caller below has to decide what an absent side means, because
   `(= nil nil)` would otherwise report two missing files as agreement."
  [p]
  (when (file? p)
    (-> (crypto/createHash "sha256") (.update (fs/readFileSync p)) (.digest "hex") (subs 0 16))))

(defn- read-dir [p] (if (dir? p) (vec (sort (js->clj (fs/readdirSync p)))) []))

(defn- slurp* [p] (when (file? p) (fs/readFileSync p "utf8")))

(defn- tree-files
  "Every file under a directory, relative, sorted. Used to compare two copies of
   a Worker without shelling out to diff."
  [base]
  (letfn [(walk [rel]
            (let [abs (path/join base rel)]
              (if (dir? abs)
                (mapcat #(walk (path/join rel %)) (read-dir abs))
                [rel])))]
    (vec (sort (mapcat walk (read-dir base))))))

;; ---------------------------------------------------------------------------
;; findings
;; ---------------------------------------------------------------------------

(def findings (atom []))
(def comparisons (atom 0))

(defn- finding! [severity key detail]
  (swap! findings conj {:severity severity :key key :detail detail}))

(defn- compared! [] (swap! comparisons inc))

;; ---------------------------------------------------------------------------
;; A — the tables cloud-itonami-cli pins from the app that generates them
;; ---------------------------------------------------------------------------

(def cli-pinned
  ["cloud-itonami-app.commands.edn"
   "cloud-itonami-app.cli-aliases.edn"
   "cloud-itonami-app.defaults.edn"
   ;; The fourth is not named for a table it carries, which is how the split
   ;; missed it in the first place: `bin/itonami` read it, found nothing, and
   ;; rendered "vunknown" -- a correct-looking screen rather than an error.
   "cloud-itonami-version.edn"])

(defn check-cli-pins! []
  (let [app (:app repos) cli (:cli repos)]
    (doseq [n cli-pinned]
      (let [mine   (digest (path/join cli "resources" n))
            theirs (digest (path/join app "resources" n))]
        (cond
          (nil? mine)
          (finding! "fail" (str "cli-pin-missing:" n)
                    (str "cloud-itonami-cli/resources/" n " is absent; the CLI pins a table it does not have"))

          (nil? theirs)
          (finding! "fail" (str "cli-pin-orphan:" n)
                    (str "cloud-itonami-app/resources/" n " is absent; the CLI pins a table nothing generates any more"))

          :else
          (do (compared!)
              (when-not (= mine theirs)
                (finding! "fail" (str "cli-pin-drift:" n)
                          (str "cli=" mine " app=" theirs
                               " -- refresh from the app checkout, or say in the commit why the pin stays behind")))))))))

;; ---------------------------------------------------------------------------
;; B — apex owns the Workers that own hostnames; the app must not carry a
;;     deployable copy of one
;; ---------------------------------------------------------------------------

(defn- worker-name
  "The Worker's own name out of its wrangler config, and whether it claims a
   route. Read as text on purpose: wrangler.toml and wrangler.jsonc are two
   syntaxes and this needs one fact from each, not a parser for both."
  [svc-dir]
  (let [txt (str/join "\n" (keep #(slurp* (path/join svc-dir %))
                                 ["wrangler.toml" "wrangler.jsonc" "wrangler.json"]))
        nm  (some-> (or (second (re-find #"(?m)^\s*name\s*=\s*\"([^\"]+)\"" txt))
                        (second (re-find #"(?m)^\s*\"name\"\s*:\s*\"([^\"]+)\"" txt)))
                    str/trim)
        route (second (re-find #"pattern\"?\s*[:=]\s*\"([^\"]+)\"" txt))]
    {:name nm :route route :has-config? (seq txt)}))

(defn check-worker-copies! []
  (let [app (:app repos) apex (:apex repos)]
    (doseq [svc (read-dir (path/join apex "services"))
            :let [apex-dir (path/join apex "services" svc)
                  app-dir  (path/join app "services" svc)]
            :when (dir? apex-dir)]
      (compared!)
      (when (dir? app-dir)
        (let [{:keys [name route has-config?]} (worker-name app-dir)
              apex-files (tree-files apex-dir)
              app-files  (tree-files app-dir)
              differing  (->> (distinct (concat apex-files app-files))
                              (remove #(= (digest (path/join apex-dir %))
                                          (digest (path/join app-dir %))))
                              sort vec)]
          (finding! "fail" (str "worker-copy:" svc)
                    (str "cloud-itonami-app/services/" svc " is a copy of an apex-owned Worker"
                         (when has-config?
                           (str "; it carries name=" (pr-str name) " route=" (pr-str route)
                                " -- the same identity apex releases, so whichever tree ships last wins"))
                         (if (seq differing)
                           (str "; the copies ALREADY DIFFER in " (count differing) " file(s): "
                                (str/join ", " (take 4 differing)))
                           "; the copies are byte-identical today")))
          (when (seq differing)
            (finding! "fail" (str "worker-copy-drift:" svc)
                      (str (count differing) " file(s) differ between the two copies of " svc
                           ": " (str/join ", " differing)))))))))

;; ---------------------------------------------------------------------------
;; C — the ingress write table the app pins, against the Worker apex ships
;; ---------------------------------------------------------------------------

(def ingress-pin-name "cloud-itonami-apex.agent-edge-writes.edn")

(defn check-ingress-pin! []
  (let [pin-path (path/join (:app repos) "resources" ingress-pin-name)
        pin-txt  (slurp* pin-path)]
    (if-not pin-txt
      ;; Not a finding. The pin is introduced by the same change that retires the
      ;; copies; before that lands there is nothing here to check, and inventing
      ;; a finding for its absence would report a defect against every older tree.
      nil
      (let [pin (try (edn/read-string pin-txt) (catch :default e {:read-error (str e)}))]
        (cond
          (:read-error pin)
          (finding! "fail" "ingress-pin-unreadable"
                    (str ingress-pin-name " does not read as EDN: " (:read-error pin)))

          (not (vector? (:writes pin)))
          (finding! "fail" "ingress-pin-shape"
                    (str ingress-pin-name " :writes is " (pr-str (type (:writes pin)))
                         ", not a vector -- edn/read-string returns a list for (str \"a\" \"b\") without throwing"))

          (not (every? string? (:writes pin)))
          (finding! "fail" "ingress-pin-shape"
                    (str ingress-pin-name " :writes contains a non-string entry: "
                         (pr-str (first (remove string? (:writes pin))))))

          :else
          (let [src-rel (or (:source/path pin) "services/agent-edge/src/index.js")
                src     (slurp* (path/join (:apex repos) src-rel))]
            (if-not src
              (finding! "fail" "ingress-pin-source-missing"
                        (str "the pin names " src-rel " in cloud-itonami-apex, and it is not there"))
              (let [live (set (map second (re-seq #"\[\"POST\", \"([^\"]+)\"\]" src)))
                    pinned (set (:writes pin))]
                (compared!)
                (cond
                  (empty? live)
                  (finding! "fail" "ingress-pin-source-shape"
                            (str src-rel " yielded no [\"POST\", \"...\"] rows -- the table's shape changed, so"
                                 " this comparison stopped measuring rather than started passing"))

                  (not= live pinned)
                  (finding! "fail" "ingress-pin-drift"
                            (str "app pins " (pr-str (sort pinned))
                                 " but " src-rel " carries " (pr-str (sort live))
                                 " -- refresh the pin and move :source/revision with it")))))))))))

;; ---------------------------------------------------------------------------

(let [missing (->> repos (remove (comp dir? val)) (map key) sort vec)]
  (when (seq missing)
    (println (str "REFUSED: checkout(s) not present under " root "/orgs/cloud-itonami: "
                  (str/join ", " (map name missing))))
    (println "  Nothing was compared, so this is not a pass.")
    (js/process.exit 2))

  (check-cli-pins!)
  (check-worker-copies!)
  (check-ingress-pin!)

  (when (zero? @comparisons)
    (println "REFUSED: every checkout was present but no comparison was made.")
    (js/process.exit 2))

  (println (str "SCANNED\t" @comparisons "\tcomparisons (cli pins, apex workers, ingress pin)"))
  (doseq [{:keys [severity key detail]} @findings]
    (if findings-mode?
      (println (str "FINDING\t" severity "\t" key "\t" detail))
      (println (str "  " severity "  " key "  " detail))))
  (if (seq @findings)
    (do (when-not findings-mode?
          (println (str (count @findings) " finding(s).")))
        (js/process.exit 1))
    (do (println "OK: the app, cli and apex surfaces agree.")
        (js/process.exit 0))))
