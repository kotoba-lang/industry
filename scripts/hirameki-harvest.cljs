#!/usr/bin/env nbb
;; hirameki-harvest.cljs — 特許 corpus の収集を常駐で回す運用側。ADR-2608100200。
;;
;; ## なぜ observatory-run と別なのか
;;
;; `com.gftd.observatory-run.plist` は自分でこう書いている:
;;
;;   ⚠ live ingest はここでは走らない。外向きの fetch/publish を無人の毎時 run に
;;     黙って混ぜない（混ぜるなら別 plist にして、その旨を書く）。
;;
;; hirameki の **autorun は観測だけ**（ネットワークに触らない）なので既存の
;; observatory-run に乗る。**harvest は 1 tick 1 外部リクエスト**なので、その規約に
;; 従ってここに分けた。同じプロセスに入れると「観測を毎時回す」判断が
;; 「Google Patents を毎時叩く」判断を暗黙に含んでしまう。
;;
;; ## なぜ fleet ノードに配らないのか（配れるのに）
;;
;; 10 台の mac-mini は外向き HTTPS を持っている（CLAUDE.md、2026-08-05 に全ノード実測）。
;; だが配らない理由が 2 つある:
;;
;; 1. **並列化はこの仕事では劣化である。** この source の規約は「識別可能な
;;    User-Agent・逐次・低レート」。10 ノードから並列に叩けば速くなるが、それは
;;    守ると決めた礼儀を破ることでしか得られない速さで、要件ではない。
;; 2. **書き込みに credential が要る。** fleet-ci の不変条件は「ノードにはテストだけを
;;    配り、秘密情報を配らない」。取得は鍵不要でもcorpus への push は鍵が要るので、
;;    ノードに置くなら「取得だけノード / commit は operator」に割ることになり、
;;    1 の理由で得るものが無い。
;;
;; したがって operator 常駐（newsfeed-ingest / observatory-run と同じ launchd 家族）。
;;
;; ## 不変条件（observatory-run から継承する）
;;
;; 1. **exit 0 を成功の証拠にしない。** journal が実際に伸びたかを測る。
;; 2. **FF できなければ何もしない。** 遅れた checkout に commit を積むと、次の誰かが
;;    解けない乖離を引き継ぐ。fetch すらせず :behind として報告して抜ける。
;; 3. **自分のファイルだけ commit する。** journal / seeds / state 以外は触らない。
;;    共有 checkout に他セッションの WIP があっても巻き込まない。
;; 4. **落ちたら黙らない。** 台帳に理由付きで残す。
;;
;; ## 使い方
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/hirameki-harvest.cljs
;;   nbb --classpath ".:scripts/nbb_compat" scripts/hirameki-harvest.cljs --ticks 40
;;   nbb --classpath ".:scripts/nbb_compat" scripts/hirameki-harvest.cljs --dry-run
;;   nbb --classpath ".:scripts/nbb_compat" scripts/hirameki-harvest.cljs --no-push

(require '[scripts.nbb-compat :as compat :refer [slurp spit sh]]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def fs (js/require "node:fs"))
(def path-mod (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def root (str/trim (:out (sh "git" "rev-parse" "--show-toplevel"))))
(defn- abs [& parts] (apply (.-join path-mod) (clj->js (cons root parts))))
(defn- exists? [p] (.existsSync fs p))

(def argv (vec *command-line-args*))
(defn- flag? [f] (some #{f} argv))
(defn- opt [f] (let [i (.indexOf (clj->js argv) f)] (when (>= i 0) (nth argv (inc i) nil))))

(def dry-run? (flag? "--dry-run"))
(def push? (not (flag? "--no-push")))
(def ticks (js/parseInt (or (opt "--ticks") "20") 10))

(def actor-dir (abs "orgs" "cloud-itonami" "hirameki"))
(def corpus-dir (abs "orgs" "cloud-itonami" "hirameki-patents"))
(def journal (str ((.-join path-mod) corpus-dir "80-data" "public" "google-patents.journal.edn")))
(def ledger-path (abs "90-docs" "observatory" "hirameki-harvest.ledger.edn"))

(defn- git [dir & args]
  (let [r (.spawnSync cp "git" (clj->js args)
                      (clj->js {:cwd dir :encoding "utf8" :maxBuffer (* 16 1024 1024)}))]
    {:exit (if (nil? (.-status r)) 1 (.-status r))
     :out (str/trim (or (.-stdout r) ""))
     :err (str/trim (or (.-stderr r) ""))}))

(defn- run-in [dir cmd args timeout-ms]
  (let [r (.spawnSync cp cmd (clj->js args)
                      (clj->js {:cwd dir :encoding "utf8" :timeout timeout-ms
                                :maxBuffer (* 64 1024 1024)
                                :env (merge (compat/getenv-all) {"JAVA_TOOL_OPTIONS" ""})}))]
    {:exit (if (nil? (.-status r)) 124 (.-status r))
     :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- size [p] (if (exists? p) (.-size (.statSync fs p)) 0))

(defn- patents-in-journal []
  (if-not (exists? journal)
    0
    (count (into #{} (map first) (edn/read-string (slurp journal))))))

(defn- append-ledger! [m]
  (let [line (str (pr-str (assoc m :run/at (.toISOString (js/Date.)))) "\n")]
    (when-not (exists? ledger-path)
      (spit ledger-path
            (str ";; hirameki-harvest.ledger.edn — append-only。1 行 1 run。\n"
                 ";; **文書ではなく測定列**なので上書きしない（CLAUDE.md の例外側）。\n"
                 ";; ここが伸びない = 収集が止まっている、を一目で見るための台帳。\n")))
    (.appendFileSync fs ledger-path line)))

(defn- die! [m]
  (println (str "  ✗ " (:run/reason m)))
  (when-not dry-run? (append-ledger! (assoc m :run/ok false)))
  (js/process.exit 1))

;; ── 前提を確かめる ──────────────────────────────────────────────────────────
(println (str "hirameki-harvest " (.toISOString (js/Date.))
              (when dry-run? "  [--dry-run]")))

(when-not (exists? ((.-join path-mod) actor-dir ".git"))
  (die! {:run/reason (str "actor checkout が無い: " actor-dir " — west update が要る")
         :run/observatory "hirameki" :run/stage :precondition}))
(when-not (exists? ((.-join path-mod) corpus-dir ".git"))
  (die! {:run/reason (str "corpus checkout が無い: " corpus-dir " — west update が要る")
         :run/observatory "hirameki" :run/stage :precondition}))

;; 不変条件 2 — FF できないなら何もしない。
(doseq [[label dir] [["corpus" corpus-dir] ["actor" actor-dir]]]
  (git dir "fetch" "origin" "--quiet")
  (let [{:keys [out]} (git dir "rev-list" "--left-right" "--count" "origin/main...HEAD")
        [behind ahead] (map #(js/parseInt % 10) (str/split out #"\s+"))]
    (when (and (pos? behind) (pos? ahead))
      (die! {:run/reason (str label " が origin/main と乖離している（behind " behind
                              " / ahead " ahead "）— rebase も force もしないので手で解く")
             :run/observatory "hirameki" :run/stage :sync}))
    (when (pos? behind)
      (let [{:keys [exit err]} (git dir "merge" "--ff-only" "origin/main")]
        (when-not (zero? exit)
          (die! {:run/reason (str label " の fast-forward に失敗: " err)
                 :run/observatory "hirameki" :run/stage :sync}))
        (println (str "  · " label " を origin/main へ " behind " commit 前進"))))))

;; ── 収集 ────────────────────────────────────────────────────────────────────
(def before-bytes (size journal))
(def before-patents (patents-in-journal))
(println (str "  · 開始時: " before-patents " 特許 / " before-bytes " bytes"))

(when dry-run?
  (println (str "  · --dry-run: " ticks " tick を走らせるところ。ここで終了。"))
  (js/process.exit 0))

(def harvest
  (run-in actor-dir "clojure"
          ["-M" "-m" "hirameki.methods.harvest" "--ticks" (str ticks) "--dataset" corpus-dir]
          (* 30 60 1000)))
(print (:out harvest))
(when-not (zero? (:exit harvest))
  (println (:err harvest)))

(def after-bytes (size journal))
(def after-patents (patents-in-journal))
(def gained (- after-patents before-patents))

;; 不変条件 1 — exit code ではなく journal の増分で判定する。
;; ただし **増分 0 は失敗とは限らない**: フロンティアが全部 404 だった tick も、
;; 既知の特許ばかり引いた tick もある。区別できるのは exit だけなので両方見る。
(when-not (zero? (:exit harvest))
  (die! {:run/reason (str "harvest が exit " (:exit harvest))
         :run/observatory "hirameki" :run/stage :harvest
         :run/ticks ticks :run/gained gained
         :run/patents after-patents}))

(println (str "  · 収集後: " after-patents " 特許（+" gained "） / " after-bytes " bytes"))

;; ── 成果物を作り直す（shard + CID + manifest）──────────────────────────────
(def published
  (when (pos? gained)
    (let [r (run-in actor-dir "clojure"
                    ["-M" "-m" "hirameki.methods.dataset" "--dataset" corpus-dir
                     "--as-of" (subs (.toISOString (js/Date.)) 0 10)]
                    (* 20 60 1000))]
      (print (:out r))
      (when-not (zero? (:exit r))
        (println (:err r))
        (die! {:run/reason (str "dataset publish が exit " (:exit r)
                                " — journal は伸びたが成果物が作り直せていない")
               :run/observatory "hirameki" :run/stage :publish
               :run/gained gained :run/patents after-patents}))
      ;; publish 自身が単一ブロック超過を拒否するので、ここで CID を再検証する必要は
      ;; ないが、**検証器が通ることは確かめる** —— 拒否と検証は別のコードなので。
      (let [v (run-in corpus-dir "clojure" ["-M" "verify.clj"] (* 10 60 1000))]
        (when-not (zero? (:exit v))
          (println (:out v)) (println (:err v))
          (die! {:run/reason "verify.clj が失敗 — 成果物が manifest と一致しない"
                 :run/observatory "hirameki" :run/stage :verify
                 :run/gained gained :run/patents after-patents}))
        (println "  · verify.clj OK（bytes / CID / ブロック上限 / 件数合計）"))
      true)))

;; ── 着地（自分のファイルだけ）──────────────────────────────────────────────
;; 不変条件 3 — 他の path は stage しない。共有 checkout に他セッションの WIP が
;; あっても巻き込まない。
(defn- commit-and-push! [dir paths msg label]
  (apply git dir "add" "--" paths)
  (let [staged (git dir "diff" "--cached" "--quiet")]
    (if (zero? (:exit staged))
      (do (println (str "  · " label ": 変更なし")) :no-change)
      (let [c (git dir "-c" "user.name=Jun Kawasaki" "-c" "user.email=jun@gftd.group"
                   "commit" "-m" msg)]
        (if-not (zero? (:exit c))
          (do (println (str "  ✗ " label " commit 失敗: " (:err c))) :failed)
          (if-not push?
            (do (println (str "  · " label ": commit 済み（--no-push）")) :committed)
            (let [p (git dir "push" "origin" "HEAD:main")]
              (if (zero? (:exit p))
                (do (println (str "  · " label ": push 済み")) :pushed)
                (do (println (str "  ✗ " label " push 失敗: " (:err p))) :push-failed)))))))))

(def corpus-landed
  (commit-and-push! corpus-dir
                    ["80-data/public/google-patents.journal.edn"
                     "corpus" "datoms" "publish-manifest.edn"]
                    (str "harvest: +" gained " patents (" after-patents " total)")
                    "corpus"))

(def actor-landed
  (commit-and-push! actor-dir ["seeds.edn" "state.edn"]
                    (str "harvest state: " ticks " ticks, +" gained " patents")
                    "actor state"))

(append-ledger!
 {:run/observatory "hirameki" :run/ok true :run/stage :done
  :run/ticks ticks :run/gained gained
  :run/patents-before before-patents :run/patents after-patents
  :run/bytes-before before-bytes :run/bytes after-bytes
  :run/published (boolean published)
  :run/corpus corpus-landed :run/actor actor-landed})

(println (str "\n  hirameki-harvest: " ticks " tick で +" gained " 特許 → 合計 "
              after-patents "。corpus=" (name corpus-landed)
              " state=" (name actor-landed)))

;; 落ちてはいないが着地していない状態を成功と呼ばない。
(when (some #{:failed :push-failed} [corpus-landed actor-landed])
  (js/process.exit 1))
