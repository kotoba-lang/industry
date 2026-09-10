#!/usr/bin/env nbb
;; DataLad/git-annex content synchronizer for west projects.
;; Replaces the former Python West extension. Run directly:
;;   nbb manifest/west_annex.cljs annex-get [project ...]
;;   nbb manifest/west_annex.cljs annex-drop [project ...]
(require '[scripts.nbb-compat :refer [slurp sh exit getenv getenv-all]])

(def root (clojure.string/trim (:out (sh "git" "rev-parse" "--show-toplevel"))))
(def fs (js/require "node:fs"))

(defn fail [message]
  (binding [*out* *err*] (println message))
  (exit 1))

(defn projects []
  ;; west.yml is generated and uses a stable block layout. Only retain projects
  ;; explicitly marked `userdata: datalad: true`; this avoids a YAML dependency.
  ;; lookahead terminator: JS regex has no \z ("end of input") anchor — it silently
  ;; matches a literal 'z', truncating any block whose text contains one (verified:
  ;; 185/1624 blocks lose their `path:` line this way, e.g. any project path containing
  ;; "z"). (?![\s\S]) is the portable "true end of string" idiom across regex engines.
  (->> (re-seq #"(?ms)^\s+- name:\s*([^\n]+)(.*?)(?=^\s+- name:|(?![\s\S]))" (slurp "manifest/west.yml"))
       (keep (fn [[_ name block]]
               (when (re-find #"(?m)^\s+datalad:\s*true\s*$" block)
                 (when-let [[_ path] (re-find #"(?m)^\s+path:\s*([^\s]+)\s*$" block)]
                   {:name (clojure.string/trim name)
                    :path path
                    :remote (or (some-> (re-find #"(?m)^\s+annex-remote:\s*([^\s]+)\s*$" block) second) "b2")}))))))

(defn resolve-b2 []
  ;; b2-creds.cljs now delegates its env/1Password/Keychain resolution to
  ;; kotoba-lang/secret-resolve (ADR-2607161000) and no longer carries its
  ;; own copy of that logic — it needs secret-resolve's src (and
  ;; scripts/nbb_compat for the cheshire shim) on the classpath, which this
  ;; script previously omitted entirely (confirmed broken: `nbb
  ;; scripts/b2-creds.cljs --json` failed with "Could not find namespace:
  ;; clojure.java.shell" before this fix, independent of the secret-resolve
  ;; refactor).
  (let [script (str root "/scripts/b2-creds.cljs")
        classpath (str root ":" root "/scripts/nbb_compat:"
                       root "/orgs/kotoba-lang/secret-resolve/src:"
                       ;; secret-resolve moved off clojure.string onto
                       ;; kotoba.lang.text; without this entry b2-creds dies
                       ;; with "Could not find namespace: kotoba.lang.text" and
                       ;; annex-get/annex-drop cannot run at all. Measured
                       ;; 2026-09-10: exit 1 with that message before, creds
                       ;; JSON after. A reclaim path nobody can run is how 137
                       ;; GB of annexed content stayed resident on a disk at
                       ;; 99% full.
                       root "/orgs/kotoba-lang/text/src")]
    (cond
      (not (.existsSync fs script)) (fail "scripts/b2-creds.cljs がありません。")
      (not (zero? (:exit (sh "which" "nbb")))) (fail "nbb が見つかりません。")
      :else (let [{:keys [exit out err]} (sh "nbb" "--classpath" classpath script "--json")]
              (if (zero? exit)
                (try (js->clj (.parse js/JSON out))
                     (catch :default _ (fail "b2-creds の JSON 出力を解釈できません。")))
                (fail (str "B2 creds 解決に失敗: " err)))))))

(defn run! [dir env & command]
  (println (str "  $ " (clojure.string/join " " command) "   (in " dir ")"))
  (let [{:keys [exit out err]} (apply sh (concat command [{:cwd dir :env env}]))]
    (when (seq out) (print out))
    (when (seq err) (binding [*out* *err*] (print err)))
    exit))

(defn enable-b2! [dir remote env]
  ;; deleted west_annex.py's _enable_b2 checked AWS_ACCESS_KEY_ID before ever
  ;; calling enableremote, as a defense-in-depth guard against resolve-b2
  ;; soft-failing into an incomplete creds map; this port dropped that
  ;; local check, relying entirely on resolve-b2's own hard-exit on failure.
  (if-not (get env "AWS_ACCESS_KEY_ID")
    (do (binding [*out* *err*]
          (println "B2 creds 未解決(AWS_ACCESS_KEY_ID 無し)。manifest/repos.edn の :b2 :credentials を確認。"))
        false)
    (do (run! dir env "git" "annex" "init")
        (if (zero? (run! dir env "git" "annex" "enableremote" remote))
          true
          (do (binding [*out* *err*]
                (println (str "enableremote " remote " に失敗。初回は scripts/datalad-b2-init.cljs で initremote 済みか確認。")))
              false)))))

(defn datalad? [] (zero? (:exit (sh "which" "datalad"))))

(let [[action & wanted] *command-line-args*]
  (when-not (#{"annex-get" "annex-drop"} action) (fail "usage: nbb manifest/west_annex.cljs annex-get|annex-drop [project ...]"))
  (let [targets (cond->> (projects) (seq wanted) (filter #(contains? (set wanted) (:name %))))]
    (when (empty? targets) (fail "対象となる DataLad project がありません。"))
    (let [env (merge (getenv-all) (resolve-b2))
          ;; Worst child status wins, and it is this script's status. Measured
          ;; 2026-09-10: `datalad drop .` on m365-archive reported
          ;; "impossible: 10242 (cannot drop modified content, save first)",
          ;; dropped nothing, left 137 GB resident -- and this script exited 0,
          ;; which is what a reclaim that actually ran also does. A caller
          ;; watching the status could not tell the two apart.
          worst (atom 0)
          note! (fn [code] (swap! worst max (or code 0)) code)]
      (doseq [{:keys [name path remote]} targets]
        (let [dir (str root "/" path)]
          (println (str "== " action ": " name " =="))
          (if-not (.existsSync fs dir)
            (do (binding [*out* *err*]
                  (println (str name " は未取得。先に west update --group-filter +datalad " name)))
                (note! 1))
            (case action
              "annex-get" (when (enable-b2! dir remote env)
                            (note! (if (datalad?) (run! dir env "datalad" "get" ".")
                                       (run! dir env "git" "annex" "get" "--from" remote))))
              "annex-drop" (note! (if (datalad?) (run! dir env "datalad" "drop" ".")
                                      (run! dir env "git" "annex" "drop")))))))
      (when-not (zero? @worst)
        (binding [*out* *err*]
          (println (str action " は完了しませんでした (worst exit " @worst ")。"
                        "上の出力を読むこと — 何も転送されていない可能性があります。")))
        (exit @worst)))))
