#!/usr/bin/env nbb
;; scripts/west-entry-add.cljs — register one repository in manifest/west.yml.
;;
;; The third of the trio: rename moves an entry, remove unregisters one, this
;; one adds. Same discipline -- the repository must exist, the commit must be
;; in it, and neither the name nor the checkout path may already be taken --
;; plus one this file learned the hard way.
;;
;; ⚠ WHERE the entry goes is not obvious, and getting it wrong does not
;; produce a west error. west.yml ends with a `self:` block that has its own
;; `path:`, so anchoring the insertion on the LAST `path:` line puts the entry
;; after `self:` and outside `projects:`. That is not a bad entry, it is
;; invalid YAML: `west update` then fails for EVERY project with a parser
;; error at a line number, and nothing points at the entry that caused it.
;; Measured 2026-09-06 -- main carried an unparseable west.yml for a few
;; minutes before it was repaired. So this anchors on the last project's
;; `groups:` line, which is inside the list and which `self:` does not have,
;; refuses if it cannot find one, and PARSES THE RESULT BEFORE WRITING IT.
;;
;;   ENTRY=<name> ORG=<org> SHA=<40-hex> GROUP=<west group> \
;;     nbb --classpath ".:scripts/nbb_compat" scripts/west-entry-add.cljs
(require '["fs" :as fs] '["child_process" :as cp] '[clojure.string :as str])
(def repo "com-junkawasaki/root") (def path "manifest/west.yml")
(defn sh [c] (try {:out (str (.execSync cp c #js {:encoding "utf8" :maxBuffer 64000000 :stdio #js ["pipe" "pipe" "pipe"]})) :exit 0}
                  (catch :default e {:out (str (or (.-stdout e) "") (or (.-stderr e) "")) :exit 1})))
(def name* (.-ENTRY js/process.env)) (def org (.-ORG js/process.env)) (def sha (.-SHA js/process.env)) (def grp (.-GROUP js/process.env))
(let [blob (str/trim (:out (sh (str "gh api repos/" repo "/contents/manifest --jq '.[] | select(.name==\"west.yml\") | .sha'"))))
      text (:out (sh (str "gh api repos/" repo "/contents/" path " -H 'Accept: application/vnd.github.raw'")))
      lines (vec (str/split-lines text))
      names (set (keep #(let [t (str/trim %)] (when (str/starts-with? t "- name: ") (subs t 8))) lines))
      paths (set (keep #(let [t (str/trim %)] (when (str/starts-with? t "path: ") (subs t 6))) lines))
      newpath (str "orgs/" org "/" name*)]
  (cond
    (contains? names name*) (println "REFUSING: an entry named" name* "already exists")
    (contains? paths newpath) (println "REFUSING:" newpath "is already some entry's checkout path")
    (not (re-matches #"[0-9a-f]{40}" sha)) (println "REFUSING: SHA is not a 40-hex commit")
    (not (zero? (:exit (sh (str "gh api repos/" org "/" name* "/commits/" sha " --jq .sha >/dev/null 2>&1")))))
    (println "REFUSING:" sha "is not a commit in" org "/" name*)
    :else
    ;; insert after the LAST project entry so the file stays one list; order is
    ;; not semantic in west.yml and re-sorting 4,300 lines to place one would
    ;; produce a diff nobody can review.
    ;; Anchor on the last PROJECT, not the last `path:`.
    ;;
    ;; west.yml ends with a `self:` block that also has a `path:`, so keying
    ;; off the final `path:` line inserts the entry AFTER `self:` and outside
    ;; `projects:` -- which is not a west error, it is invalid YAML, and it
    ;; broke main for a few minutes on 2026-09-06 before being repaired. The
    ;; final project's `groups:` line is inside the list and `self:` has none.
    (let [anchor (last (keep-indexed (fn [i l] (when (str/starts-with? l "      groups: [") i)) lines))
          _ (when (nil? anchor) (throw (js/Error. "no `      groups: [` line — refusing to guess where projects ends")))
          end (inc anchor)
          rendered [(str "    - name: " name*) (str "      remote: " org) (str "      revision: " sha)
                    (str "      path: " newpath) (str "      groups: [" grp "]")]
          out (str (str/join "\n" (concat (subvec lines 0 end) rendered (subvec lines end))) "\n")
          pf "/tmp/west-add.json"]
      (.writeFileSync fs pf (js/JSON.stringify (clj->js {:message (str "west: register " org "/" name*)
                                                         :content (.toString (.from js/Buffer out "utf8") "base64")
                                                         :sha blob :branch "main"})) "utf8")
      ;; Parse it BEFORE writing. A manifest that does not parse is worse
      ;; than one missing an entry, and the cost of finding out afterwards is
      ;; every `west update` in the window.
      (.writeFileSync fs "/tmp/west-add-check.yml" out "utf8")
      (let [chk (sh "python3 -c \"import yaml,sys; d=yaml.safe_load(open('/tmp/west-add-check.yml')); ps=d['manifest']['projects']; assert d['manifest'].get('self'); print(len(ps))\"")]
        (if-not (zero? (:exit chk))
          (println "REFUSING: the result does not parse as west.yml —" (str/trim (:out chk)))
          (let [r (sh (str "gh api -X PUT repos/" repo "/contents/" path " --input " pf " --jq .commit.sha"))]
            (println "  (parsed:" (str/trim (:out chk)) "projects)")
            (if (re-matches #"[0-9a-f]{40}" (str/trim (:out r)))
              (println "committed" (str/trim (:out r)))
              (println "PUT failed:" (str/trim (:out r))))))))))
