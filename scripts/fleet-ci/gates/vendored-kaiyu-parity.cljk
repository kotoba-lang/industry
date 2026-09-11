#!/usr/bin/env nbb
;; vendored-kaiyu-parity — a vendored copy must still BE the copy.
;;
;; club-shinshi-app cannot depend on kotoba-lang/kaiyu: its CI and production
;; deploy both check out that repo alone, so a west-sibling source path or a
;; :git/sha dep resolves on a developer machine and breaks everywhere else
;; (ADR-2607078100 records the same reasoning for `treasury.core`). So it
;; vendors `src/kaiyu/*.cljc` with the upstream sha in a header comment.
;;
;; A vendored copy that nobody checks is just a fork with a misleading name.
;; The failure it produces is quiet in the worst way: the library changes a
;; dwell boundary, babiniku.net and kotobase.net follow, shinshi.club keeps the
;; old one, and the three sites' distributions stop being comparable while
;; every suite stays green — which is precisely the thing the shared vocabulary
;; exists to prevent.
;;
;; This gate runs against the repo tree fleet-ci shipped to the node. It reads
;; the pinned sha out of the vendored header and fetches that file from GitHub,
;; because the point is to compare against WHAT THE HEADER CLAIMS, not against
;; whatever happens to be checked out next to it.
;;
;;   nbb vendored-kaiyu-parity.cljs [--dir <repo-root>] [--prefix <vendor dir>]

(ns vendored-kaiyu-parity
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn- arg [flag default]
  (let [i (.indexOf argv flag)]
    (if (neg? i) default (nth argv (inc i) default))))

(def repo-root (arg "--dir" "."))
(def vendor-prefix (arg "--prefix" "appview/ai-gftd-wasm-shinshi-sh1n5h1x/cljs/src/kaiyu"))
(def upstream-repo "kotoba-lang/kaiyu")

(def header-re #"(?m)^;; VENDORED from kotoba-lang/kaiyu @ ([0-9a-f]{40}) ")

(defn- fail! [& msgs]
  (println (str/join "\n" (cons "vendored-kaiyu-parity: FAIL" msgs)))
  (js/process.exit 1))

(defn- strip-header
  "The vendored file is the upstream file with a provenance header prepended.
  Compare the body, so the header itself is allowed to differ."
  [s]
  (let [marker "(ns kaiyu."]
    (if-let [i (str/index-of s marker)]
      (subs s i)
      s)))

(defn- fetch-upstream [sha file]
  (-> (js/fetch (str "https://raw.githubusercontent.com/" upstream-repo "/" sha "/src/kaiyu/" file))
      (.then (fn [^js res]
               (if (.-ok res)
                 (.text res)
                 (js/Promise.reject (js/Error. (str "GET " file " @" sha " → " (.-status res)))))))))

(defn -main []
  (let [dir (path/join repo-root vendor-prefix)]
    (when-not (fs/existsSync dir)
      (fail! (str "vendor directory not found: " dir)
             "If the vendored copy was removed on purpose, remove this gate in the same commit."))
    (let [files (->> (fs/readdirSync dir) (js->clj) (filter #(str/ends-with? % ".cljc")) sort vec)]
      (when (empty? files)
        (fail! (str "no .cljc files under " dir)
               "An empty vendor directory passes every content check trivially; that is why this is a failure."))
      (-> (js/Promise.all
           (clj->js
            (for [f files]
              (let [local (str (fs/readFileSync (path/join dir f) "utf8"))
                    m (re-find header-re local)]
                (if-not m
                  (js/Promise.resolve {:file f :error "no VENDORED-from header — the pinned sha is what makes this checkable"})
                  (let [sha (second m)]
                    (-> (fetch-upstream sha f)
                        (.then (fn [upstream]
                                 {:file f :sha sha
                                  :match (= (str/trimr (strip-header local))
                                            (str/trimr upstream))}))
                        (.catch (fn [e] {:file f :sha sha :error (str e)})))))))))
          (.then
           (fn [results]
             (let [results (js->clj results :keywordize-keys true)
                   bad (remove #(and (nil? (:error %)) (:match %)) results)]
               (if (seq bad)
                 (fail! (str "vendored files differ from " upstream-repo " at their own pinned sha:")
                        (str/join "\n"
                                  (for [{:keys [file sha error]} bad]
                                    (str "  " file " @" (or sha "?") " — " (or error "content differs"))))
                        ""
                        "Re-vendor: copy the upstream file and update the sha in the header.")
                 (do (println (str "vendored-kaiyu-parity: ok — " (count results)
                                   " file(s) identical to " upstream-repo
                                   " @" (:sha (first results))))
                     (js/process.exit 0))))))))))

(-main)
