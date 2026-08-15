#!/usr/bin/env nbb
;; Which application manifests identify their document by content, and which
;; only by location?
;;
;;   nbb --classpath ".:orgs/kotoba-lang/content-address/src" \
;;       scripts/verify-app-content-address.cljs [<root>] [--list]
;;
;; ADR-2608157000. The judge is `content-address.core/addressed?` — this
;; script does not carry a second copy of that decision, because a mirror
;; that only one side fixes is how the workspace has lost time before.
;;
;; Exit codes are three-valued: 0 = every manifest carries a content address,
;; 1 = some identify by location only, 2 = the question could not be answered
;; (nothing scanned, the library is not checked out, or an EDN would not
;; read). A check that could not run must never look like a pass
;; (ADR-2608136000).
;;
;; ⚠ This is deliberately NOT a fleet gate. Its input lives under `orgs/`,
;; and fleet ships a single repo's tree — a gate here would be permanently
;; red for want of input, exactly like `root-permit-index`.

(ns verify-app-content-address
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [content-address.core :as ca]))

(def ^:private manifest-name "kotoba.app.edn")

(defn- script-args []
  (if (seq *command-line-args*)
    (vec *command-line-args*)
    (let [argv (vec (js->clj (.-argv js/process)))
          i (first (keep-indexed
                    (fn [i v] (when (str/ends-with? v "verify-app-content-address.cljs") i))
                    argv))]
      (if (some? i) (subvec argv (inc i)) []))))

(defn- find-manifests [root]
  (let [out (try
              (.toString (cp/execSync (str "rg --files --hidden --no-messages -g '"
                                           manifest-name "' " root)
                                      #js {:maxBuffer (* 64 1024 1024)}))
              (catch :default _ ""))]
    (->> (str/split-lines out)
         (remove str/blank?)
         ;; A worktree copy is the same manifest twice; counting it twice
         ;; would move the coverage number without moving the coverage.
         (remove #(re-find #"/(node_modules|\.git)/" %))
         (remove #(re-find #"/orgs/[^/]+/[._]" %))
         sort
         vec)))

(defn- classify [p]
  (let [m (try (edn/read-string (.readFileSync fs p "utf8"))
               (catch :default e {::unreadable (str e)}))]
    (cond
      (::unreadable m)
      {:path p :state :unreadable :detail (::unreadable m)}

      (empty? (ca/entities m))
      {:path p :state :unreadable :detail "no application entity in this EDN"}

      (ca/file-addressed? m)
      {:path p :state :addressed
       :address (ca/file-address-of m)
       :problems (mapcat ca/problems (filter ca/addressed? (ca/entities m)))}

      :else
      {:path p :state :located
       :problems (ca/problems (first (ca/entities m)))})))

(defn -main [& args]
  (let [root (or (first (remove #(str/starts-with? % "--") args)) "orgs")
        list? (some #{"--list"} args)
        lib "orgs/kotoba-lang/content-address/src"]
    (when-not (.existsSync fs lib)
      (println "UNANSWERED — kotoba-lang/content-address is not checked out at" lib)
      (println "  west update --fetch smart content-address")
      (set! (.-exitCode js/process) 2)
      (throw (ex-info "library missing" {})))
    (let [rows (mapv classify (find-manifests root))
          by-state (group-by :state rows)
          scanned (count rows)
          addressed (count (:addressed by-state))
          located (count (:located by-state))
          unreadable (count (:unreadable by-state))]
      (when list?
        (doseq [r rows]
          (println (case (:state r)
                     :addressed "ADDRESSED "
                     :located "LOCATED   "
                     "UNREADABLE")
                   (:path r)
                   (if (seq (:problems r))
                     (str/join "," (map (comp name :problem) (:problems r)))
                     ""))))
      (doseq [r (:addressed by-state)]
        (println "ADDRESSED " (:path r)
                 (get-in r [:address :bundle-cid] (get-in r [:address :graph-cid]))))
      (doseq [r (:unreadable by-state)]
        (println "UNREADABLE" (:path r) (:detail r)))
      (println (str "ROOT\t" (path/resolve root)))
      (println (str "SCANNED\t" scanned))
      (println (str "ADDRESSED\t" addressed))
      (println (str "LOCATED\t" located))
      (println (str "UNREADABLE\t" unreadable))
      (cond
        (zero? scanned)
        (do (println "UNANSWERED — no" manifest-name "was found under" root)
            (set! (.-exitCode js/process) 2))

        (pos? unreadable)
        (do (println "UNANSWERED — an application manifest would not read")
            (set! (.-exitCode js/process) 2))

        (pos? located)
        (set! (.-exitCode js/process) 1)))))

(apply -main (script-args))
