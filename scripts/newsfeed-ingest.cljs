#!/usr/bin/env nbb
;; newsfeed-ingest.cljs — newsfeed の日次 ingest を回して台帳を B2 へ載せる
;; （ADR-2608031900）。launchd の com.gftd.newsfeed-ingest から呼ばれる。
;;
;; ## なぜ superproject 側にこれがあるか
;;
;; ingest 自体は `kotoba-lang/newsfeed` の `bin/ingest.cljs` が持つ。ここが持つのは
;; **その周りの運用** — B2 credential の解決、annex への save、B2 への push、
;; そして「何が起きたか」の報告。credential 解決は superproject の
;; `scripts/b2-creds.cljs` + `manifest/repos.edn` の `:b2 :credentials` に住んで
;; いるので、それを子 repo から参照させるより運用側をこちらに置く方が境界が素直。
;;
;; ## 不変条件
;;
;; 1. **exit 0 を成功の証拠にしない。** ingest の exit code だけでなく、台帳の
;;    行数が実際に増えたか（または「増えなかった」ことが正常か）を報告する。
;;    fleet-ci 全体と同じ規律。
;; 2. **credential をログに出さない。** 解決した値は環境変数として子プロセスに
;;    渡すだけで、print しない。
;; 3. **push が失敗したら save を巻き戻さない。** 台帳はローカルに残っており、
;;    次回の push で回収される。中途半端に戻す方が状態を壊す。
;; 4. **checkout が無ければ何もしない**（黙って clone しない）。west 管理下の
;;    path を勝手に作ると、他セッションの作業と衝突する。

(require '[scripts.nbb-compat :as io :refer [slurp]]
         '[clojure.string :as str])

(def cp (js/require "node:child_process"))
(def fs (js/require "node:fs"))

(def root "/Users/junkawasaki/github/com-junkawasaki")
(def ds (str root "/orgs/kotoba-lang/newsfeed"))
(def ledger (str ds "/state/articles.ledger.edn"))

(defn- run
  "コマンドを実行して {:exit :out :err} を返す。inherit しない（ログに集約する）。"
  [args {:keys [dir env]}]
  (let [r (.spawnSync cp (first args) (clj->js (rest args))
                      (clj->js (cond-> {:encoding "utf8" :maxBuffer (* 64 1024 1024)}
                                 dir (assoc :cwd dir)
                                 env (assoc :env env))))]
    {:exit (or (.-status r) 1)
     :out (or (.-stdout r) "")
     :err (or (.-stderr r) "")}))

(defn- line-count [p]
  (if (.existsSync fs p)
    (count (remove str/blank? (str/split-lines (slurp p))))
    0))

(defn- die [msg] (println (str "FAIL " msg)) (js/process.exit 1))

(println (str "newsfeed-ingest " (.toISOString (js/Date.))))

(when-not (.existsSync fs (str ds "/.git"))
  (die (str "checkout が無い: " ds " — west update してから再実行する（黙って clone しない）")))

;; ── B2 credential（値は出さない）──────────────────────────────────────────
(def creds
  (let [{:keys [exit out]} (run ["nbb" "--classpath"
                                 (str root "/orgs/kotoba-lang/secret-resolve/src:"
                                      root "/scripts/nbb_compat:" root)
                                 (str root "/scripts/b2-creds.cljs")]
                                {:dir root})]
    (when (pos? exit) (die "B2 credential を解決できない（scripts/b2-creds.cljs）"))
    (into {} (for [l (str/split-lines out)
                   :let [m (re-matches #"export ([A-Z0-9_]+)='(.*)'" l)]
                   :when m]
               [(nth m 1) (nth m 2)]))))

(when-not (get creds "B2_KEY_ID") (die "B2_KEY_ID が解決結果に無い"))
(println (str "  creds ok (bucket=" (get creds "B2_BUCKET") ")"))

;; process.env は nbb では素の Clojure map に落ちない（js->clj しても merge できない）。
;; JS オブジェクトのまま複製して足す。
(def env
  (let [e (js/Object.assign #js {} (.-env js/process))]
    (doseq [[k v] creds] (aset e k v))
    (aset e "AWS_ACCESS_KEY_ID" (get creds "B2_KEY_ID"))
    (aset e "AWS_SECRET_ACCESS_KEY" (get creds "B2_APP_KEY"))
    e))

;; ── 1) annex を使える状態にして、台帳の実体を手元に取る ───────────────────
;;
;; west の checkout は素の `git clone`/`git checkout` なので **annex は init されて
;; いない**。その状態では annex ファイルは中身の無いシンボリックリンクで、
;; ingest の追記が ENOENT で落ちる（実測 2026-08-03、この経路を最初に踏んだ）。
;; init は冪等なので毎回叩いてよい。
(run ["git" "annex" "init" "newsfeed-ingest"] {:dir ds :env env})

;; special remote の定義は **`git-annex` ブランチ**に載っている。west はそれを
;; fetch しないので、この clone では「No special remotes are currently known」に
;; なり get が必ず失敗する（実測 2026-08-03）。明示的に取ってくる。
;; あわせて west が付ける `annex-ignore` を外す — これが立っていると git-annex は
;; その remote を最初から候補にしない。
(def remote
  (let [{:keys [out]} (run ["git" "remote"] {:dir ds})]
    (or (first (remove str/blank? (str/split-lines out))) "origin")))
(run ["git" "config" (str "remote." remote ".annex-ignore") "false"] {:dir ds :env env})
(run ["git" "fetch" remote (str "+refs/heads/git-annex:refs/remotes/" remote "/git-annex")]
     {:dir ds :env env})
(run ["git" "annex" "merge"] {:dir ds :env env})

;; special remote は clone 直後は無効。有効化して初めて B2 から get できる。
(let [{:keys [exit err]} (run ["git" "annex" "enableremote" "b2"] {:dir ds :env env})]
  (when (pos? exit)
    (die (str "git annex enableremote b2 に失敗した（git-annex ブランチが取れていない可能性）: "
              (str/trim err)))))
(run ["git" "annex" "get" "state/articles.ledger.edn"] {:dir ds :env env})

;; annex 管理下のファイルは読み取り専用シンボリックリンクなので、そのままでは
;; 追記できない。unlock して実ファイルに戻す（datalad save が再び annex 化する）。
;;
;; 判定に `existsSync` を使わない — **content が未取得のとき symlink は壊れており、
;; existsSync はリンクを辿るので false を返す**。それでは「台帳が git にあるのに
;; unlock されない」状態になり、ingest が ENOENT で落ちる（実測 2026-08-03）。
;; リンク自体の有無は lstat で見る。初回 run では台帳がまだ無いので失敗してよい。
(defn- tracked? [p]
  (try (boolean (.lstatSync fs p)) (catch :default _ false)))
(when (tracked? ledger)
  (run ["datalad" "unlock" "state/articles.ledger.edn"] {:dir ds :env env}))
(.mkdirSync fs (str ds "/state") #js {:recursive true})

(def before (line-count ledger))

;; ── 2) ingest ─────────────────────────────────────────────────────────────
(let [{:keys [exit out err]} (run ["nbb" "--classpath" "src:resources" "bin/ingest.cljs"]
                                  {:dir ds :env env})]
  (println (str/join "\n" (map #(str "  " %) (take-last 6 (remove str/blank? (str/split-lines out))))))
  (when (pos? exit)
    (println (str "  ingest stderr: " (str/trim err)))
    (die "ingest が全 source で失敗した")))

(def after (line-count ledger))
(println (str "  台帳: " before " -> " after " 行 (+" (- after before) ")"))

;; 行数が増えないのは異常ではない（feed が更新されていない日はある）。
;; 減っていたら台帳が壊れているので止める。
(when (< after before)
  (die (str "台帳が縮んだ (" before " -> " after ") — append-only のはずなので中断する")))

;; ── 3) annex へ save ─────────────────────────────────────────────────────
(let [{:keys [exit out err]}
      (run ["datalad" "save" "-m" (str "ingest " (subs (.toISOString (js/Date.)) 0 10)
                                       " (+" (- after before) " article)")]
           {:dir ds :env env})]
  (if (pos? exit)
    (do (println (str "  save stderr: " (str/trim err))) (die "datalad save に失敗"))
    (println (str "  save ok" (when (str/includes? out "notneeded") " (変更なし)")))))

;; ── 4) B2 へ push ────────────────────────────────────────────────────────
(let [{:keys [exit err]} (run ["datalad" "push" "--to" "b2"] {:dir ds :env env})]
  (if (pos? exit)
    ;; 巻き戻さない（不変条件 3）。台帳は手元にあり、次回 push で回収される。
    (println (str "WARN push --to b2 が失敗した。台帳は手元に残っている（次回回収）。\n  "
                  (str/trim err)))
    (println "  push --to b2 ok")))

;; ── 5) 実際に B2 にあるかを whereis で確認する（exit 0 を信用しない）──────
(let [{:keys [out]} (run ["git" "annex" "whereis" "state/articles.ledger.edn"] {:dir ds :env env})]
  (if (str/includes? out "[b2]")
    (println "  whereis: b2 にコピーあり")
    (println "WARN whereis に b2 が出ない — B2 側に載っていない可能性がある")))

(println "newsfeed-ingest done")
