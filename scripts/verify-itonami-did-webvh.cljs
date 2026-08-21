#!/usr/bin/env nbb
;; itonami.cloud の did:webvh log —— repo が持っているものと、live が配っているもの。
;;
;;   nbb scripts/verify-itonami-did-webvh.cljs [--root <orgs を持つ checkout>] [--findings]
;;
;; ## 2 つ別のことを測る。混ぜない
;;
;; 1. **repo の log が暗号的に verify するか** —— cloud-itonami の
;;    `bin/didwebvh.cljs verify` を**その checkout で実行して**訊く。ここで
;;    再実装しない（entry hash と proof は別の document に対して取られる、
;;    `nextKeyHashes` は multikey 文字列を hash する、署名してよいのはその
;;    entry の updateKeys とは限らない —— 3 つとも「もっともらしく安定して
;;    間違った」値が出る種類の規則で、2 実装目を持てば片方だけが直る）。
;; 2. **live が repo と同じ bytes を配っているか** —— Pages は git 連携が無く、
;;    deploy は最後に実行した人が勝つ。main に在ることと配られていることは
;;    別の主張である（ADR-2607285000 の kotobase.net funnel 巻き戻しと同型）。
;;
;; ## 答えられなかったときは 0 でも 1 でもない
;;
;; checkout が無い / nbb が無い / **ネットワークが死んでいる** は exit 3。
;; とくに 3 つ目 —— 到達できないことを「live が配っていない」と記録すると、
;; 回線が落ちた日に deploy 差分が捏造される。
(ns verify-itonami-did-webvh
  (:require [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def argv (vec *command-line-args*))
(defn opt [f d] (let [i (.indexOf argv f)] (if (neg? i) d (nth argv (inc i) d))))
(def findings? (some #{"--findings"} argv))
(def root (opt "--root" (.cwd js/process)))
(def repo (path/join root "orgs" "network-awai" "cloud-itonami"))
(def log-rel (path/join "public" ".well-known" "did.jsonl"))
(def live-url (opt "--url" "https://itonami.cloud/.well-known/did.jsonl"))

(def kl (path/join root "orgs" "kotoba-lang"))
(def classpath
  (str/join ":" ["src" "test"
                 (path/join kl "foundation-identity-didwebvh" "src")
                 (path/join kl "org-ietf-jcs" "src")
                 (path/join kl "io-multiformats" "src")
                 (path/join kl "org-ietf-ed25519" "src")
                 (path/join kl "org-w3-vc-data-integrity" "src")
                 (path/join kl "org-w3-did" "src")]))

(defn refuse! [why]
  (println (str "REFUSING to report on the did:webvh log: " why))
  (println "exit 3 — could not answer. This is not a pass and not a gap.")
  (.exit js/process 3))

(def found (atom 0))

(defn finding! [sev k detail]
  (swap! found inc)
  ;; **`--findings` が無いときも数える。** 表示の有無で exit code が変われば、
  ;; 人が手で回したときだけ緑になる検査になる。
  (when findings? (println (str "FINDING\t" sev "\t" k "\t" detail))))

(defn -main []
  (when-not (fs/existsSync repo)
    (refuse! (str "cloud-itonami の checkout が無い: " repo)))
  (let [log-path (path/join repo log-rel)]
    (when-not (fs/existsSync log-path)
      ;; genesis を打っていないのは gap だが、**この検出器が答えるべき問いでは
      ;; ない** —— 打つかどうかは決定であって欠陥ではない。
      (refuse! (str "repo に did.jsonl が無い（genesis 未実行）: " log-path)))
    (let [repo-bytes (fs/readFileSync log-path "utf8")
          verify (try
                   (cp/execFileSync "nbb" #js ["--classpath" classpath
                                               "bin/didwebvh.cljs" "verify"]
                                    #js {:cwd repo :encoding "utf8"
                                         :stdio #js ["ignore" "pipe" "pipe"]})
                   (catch :default e
                     ;; exit 1 は「log が verify しない」で、これは finding。
                     ;; それ以外（nbb が無い / classpath が壊れている）は
                     ;; 答えられなかったのであって、欠陥ではない。
                     (let [st (.-status e)
                           out (str (some-> (.-stdout e) .toString)
                                    (some-> (.-stderr e) .toString))
                           head (str/join " " (take 3 (str/split-lines out)))]
                       (when-not (= 1 st)
                         (refuse! (str "verify を実行できなかった (status " st "): "
                                       (str/trim head))))
                       out)))
          verify-txt (str verify)
          verified? (str/includes? verify-txt "log verifies")
          did (second (re-find #"DID\t(\S+)" verify-txt))
          entries (second (re-find #"ENTRIES\t(\d+)" verify-txt))]

      (println (str "SCANNED\t" (or entries "?") " log entr" (if (= "1" entries) "y" "ies")
                    " in " (path/join "orgs/network-awai/cloud-itonami" log-rel)))
      (println (str "DID\t" (or did "(none — the log did not verify)")))

      (when-not verified?
        (finding! "high" "did-webvh:repo-log-does-not-verify"
                  (str "repo の did.jsonl が verify を通らない: "
                       (str/trim (or (second (re-find #"(log が検証を通らない.*)" verify-txt)) "")))))

      ;; live の取得。落ちたら **finding ではなく refuse**。
      (let [live (try
                   (.toString (cp/execFileSync
                               "curl" #js ["-sS" "--max-time" "25" "-w" "\n%{http_code}" live-url]
                               #js {:encoding "utf8"}))
                   (catch :default e
                     (refuse! (str "live に到達できなかった。**「配られていない」と"
                                   "記録しない** —— 回線が落ちた日に deploy 差分が"
                                   "捏造される: " (ex-message e)))))
            lines (str/split-lines live)
            code (last lines)
            body (str/join "\n" (butlast lines))]
        (println (str "LIVE\t" live-url " -> HTTP " code))
        (cond
          (= "404" code)
          (finding! "medium" "did-webvh:not-deployed"
                    (str "main は did.jsonl を持っているが live は 404。Pages は git 連携が"
                         " 無く、deploy は手で走らせる。deploy されるまで、この DID は"
                         " 誰からも resolve できない"))

          (not= "200" code)
          (finding! "medium" "did-webvh:live-not-200"
                    (str "live が HTTP " code " を返す"))

          (not= (str/trim body) (str/trim repo-bytes))
          (finding! "high" "did-webvh:live-differs-from-repo"
                    (str "live が配っている bytes が repo と違う。deploy は push と"
                         " 違って fast-forward 検査を持たず、最後に実行した人が勝つ"))

          :else
          (println "\nThe live host serves exactly what the repository holds."))))
    (when (pos? @found)
      (println (str "\n" @found " finding(s)."))
      (.exit js/process 1))))

(-main)
