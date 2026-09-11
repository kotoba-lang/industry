(ns verify-single-page-app
  "UI that cannot be opened as one page.

  ADR-2608080100 made single-page the rule for kotoba-lang UI: one document,
  one bundle, one mount. Moving between screens changes state, not location.
  The rule was written because two apps had shipped a second HTML document for
  a secondary view, which meant React, the cljs core and the whole design
  system were compiled and shipped twice -- and, worse, only one of the two
  app shells followed the DADS migration. The other went on linking a
  stylesheet that migration had deleted, unstyled, for three days.

  Nothing checked it. This does.

  Two things are reported, because `viewable as one page` fails in two
  directions and they are not the same defect:

    multi-document  the repo builds a browser app and ships two or more
                    hand-written documents that load a script. That is the
                    shape the ADR measured.
    no-document     the repo has a shadow-cljs `:target :browser` build and
                    ships no document at all. There is nothing to open.
                    `viewable` is the half of the rule that says a UI is not
                    finished when it compiles.

  Why `no-document` asks for `:target :browser` specifically, and not merely
  for a build config: measured on 2026-08-26, 232 of 285 repositories with a
  build config ship no document, and nearly all of them are correct -- a
  component library, a codec, a node target. A class that is 81% correct by
  design is not a finding, it is noise, and noise is how a detector stops
  being read. `:target :browser` is the one target that emits a bundle whose
  only entry point is a page, so a repo that selects it and ships no page has
  built something nobody can open. That narrowed the class to 14.

  Deliberate exceptions -- an SSR/OG marketing surface, which ADR-2608080100
  names as out of scope -- are carried as `:accepted` entries in
  manifest/orgs-detectors.edn, where admission makes you write a date, a
  reason and an exit condition.

  Refusals. This answers `I could not measure` with exit 2, never with a pass:
  no west manifest, no orgs/ tree, or zero apps found all end the run without
  a verdict. `SCANNED<TAB>0` is not clean."
  (:require ["fs" :as fs] ["path" :as p] [clojure.string :as str]))

(def argv (vec (drop 2 (.-argv js/process))))
(defn flag [n] (some #{n} argv))
(defn opt [n d] (let [i (.indexOf argv n)] (if (neg? i) d (get argv (inc i) d))))
(def root (opt "--root" "."))
(def findings? (flag "--findings"))

(defn ls [d] (try (vec (.readdirSync fs d)) (catch :default _ [])))
(defn dir? [f] (try (.isDirectory (.statSync fs f)) (catch :default _ false)))
(defn slurp* [f] (try (.readFileSync fs f "utf8") (catch :default _ nil)))

;; Directories whose contents are not authored by this repo, or are not the
;; app: build output, caches, vendored trees, scratch, and the demo/example
;; galleries the rule does not govern.
(def skip-dir
  #"/(node_modules|\.git|\.shadow-cljs|\.cache|target|dist|build|out|public/blog|_archive|_working|coverage|vendor|appview|examples?|demos?|docs?|tests?|e2e|stories|fixtures?|raw)(/|$)")

(defn walk [d depth acc]
  (if (or (> depth 7) (re-find skip-dir (str d "/")))
    acc
    (reduce (fn [a e] (let [f (p/join d e)]
                        (if (dir? f) (walk f (inc depth) a) (conj a f))))
            acc (ls d))))

(def build-config #"/(shadow-cljs\.edn|vite\.config\.[jt]s|svelte\.config\.js)$")
;; The one target whose only entry point is a document. See the docstring.
(def browser-target #":target\s+:browser")
;; 404.html is required by the rule, not a violation of it: a static host needs
;; one to send moved addresses back to the single document.
(def not-a-view #"/(404|test|fixture|bench|demo|smoke)[^/]*\.html$")

(defn west-paths []
  (let [y (slurp* (p/join root "manifest" "west.yml"))]
    (when y
      (into #{} (map second) (re-seq #"(?m)^\s*path:\s*(\S+)\s*$" y)))))

(defn -main []
  (let [registered (west-paths)
        orgs-dir (p/join root "orgs")]
    (when-not registered
      (println "REFUSED\tno manifest/west.yml under" root
               "-- cannot tell a registered checkout from a stale one")
      (.exit js/process 2))
    (when-not (dir? orgs-dir)
      (println "REFUSED\tno orgs/ under" root "-- nothing to scan")
      (.exit js/process 2))
    (let [repos (for [o (ls orgs-dir) :when (dir? (p/join orgs-dir o))
                      r (ls (p/join orgs-dir o))
                      :let [rel (str "orgs/" o "/" r)]
                      :when (and (contains? registered rel) (dir? (p/join root rel)))]
                  rel)
          rows (keep (fn [rel]
                       (let [files (walk (p/join root rel) 0 [])
                             build (filter #(re-find build-config %) files)]
                         (when (seq build)
                           (let [views (->> files
                                            (filter #(str/ends-with? % ".html"))
                                            (remove #(re-find not-a-view %))
                                            (filter #(some-> (slurp* %)
                                                             (->> (re-find #"(?i)<script[^>]+src="))))
                                            sort vec)]
                             {:repo rel
                              :views views
                              :page-target? (boolean
                                              (some #(some->> (slurp* %)
                                                              (re-find browser-target))
                                                    build))}))))
                     repos)
          apps (count rows)
          multi (filter #(>= (count (:views %)) 2) rows)
          none (filter #(and (zero? (count (:views %))) (:page-target? %)) rows)]
      (when (zero? apps)
        (println "REFUSED\tzero repositories with a browser build config were found"
                 "-- a scan that finds no apps has not measured the rule")
        (.exit js/process 2))
      (when findings?
        (doseq [{:keys [repo views]} multi]
          (println (str "FINDING\twarn\tmulti-document:" repo "\t"
                        (count views) " documents load a script, so the UI is not one page: "
                        (str/join " " (map #(subs % (inc (count (p/join root repo)))) (take 6 views))))))
        (doseq [{:keys [repo]} none]
          (println (str "FINDING\twarn\tno-document:" repo
                        "\tbuilds a browser bundle and ships no document that loads it -- nothing to open"))))
      (println (str "SCANNED\t" apps "\trepositories with a browser build config, of "
                    (count repos) " registered checkouts"))
      (println (str "multi-document=" (count multi) " no-document=" (count none)
                    " (of " (count (filter :page-target? rows)) " with :target :browser)"))
      (.exit js/process (if (or (seq multi) (seq none)) 1 0)))))

(-main)
