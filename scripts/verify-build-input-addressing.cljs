#!/usr/bin/env nbb
(ns verify-build-input-addressing
  "Every build input in this workspace, and whether it is addressed by content.

  ## Why this exists

  A build is only as trustworthy as the identity of what it pulls in. This
  workspace already owns the machinery for content addressing -- IPLD, CIDs,
  kotoba.storage.verify/verifying-block-store, and ADR-2607289500's ruling that
  identity is a semantic definition CID -- and the build uses none of it. It
  resolves names, paths and prefixes.

  Measured 2026-09-09 across 4,335 project files:

    :git/sha, 40 hex            6,954   addressed (git's hash over commit/tree/blob)
    :git/sha, 7 hex             3,674   A PREFIX. 2^28, grindable, and the tag
                                        beside it is mutable. 3,673 are one
                                        value, copied into nearly every deps.edn
    :mvn/version                6,317   a NAME. tools.deps carries no integrity
                                        field, so nothing here pins bytes
    :local/root, cross-repo     3,128   a PATH. whatever is checked out is what
                                        gets built
    :local/root, same repo         19   fine: one content unit either way

  ## What the other systems do, and what this is measured against

  Unison has no package: a definition is the hash of its typed AST, so there is
  nothing to confuse. Nix declares outputHash before fetching, which is why its
  caches need no trust. Deno's lock carries a SHA-256 per fetched URL. Go adds a
  transparency log so a server cannot serve different bytes to different people.
  All four address the BYTES; this workspace mostly addresses the name.

  ## What is reported

    :warn   a sha PREFIX. This is the one class that should reach zero -- it is
            mechanical, and a wave is expanding them.
    :info   maven coordinates and cross-repo :local/root. Both are standing
            counts rather than defects: fixing them needs a lock format and a
            revision respectively, not a rewrite. They are here so the number
            moves visibly when either lands.

  ## What it measures, and what that is not

  THE CHECKOUT, not the repos' mains. This workspace's checkouts are routinely
  behind -- measured repeatedly on 2026-09-09 -- so a wave that expands prefixes
  on every main will not move this number until `west update` brings the
  checkouts along. A count that has not moved is not evidence that nothing
  landed.

  Exit: 0 clean, 1 findings, 2 REFUSED (could not measure).

    nbb --classpath \".:scripts/nbb_compat\" scripts/verify-build-input-addressing.cljs [--findings] [--root DIR]"
  (:require [clojure.string :as str]))

(def fs (js/require "node:fs"))
(def pm (js/require "node:path"))
(def cp (js/require "node:child_process"))

(def argv (vec (drop 2 (js->clj js/process.argv))))
(def findings-mode? (some #{"--findings"} argv))
(def root (or (second (drop-while #(not= "--root" %) argv)) "."))

(defn- project-files []
  ;; only the directories that exist: naming one that does not makes ripgrep
  ;; exit 2, which this detector correctly reads as "could not measure" -- and
  ;; then no fixture tree can ever exercise it.
  (let [dirs (filterv #(try (.isDirectory (fs.statSync (pm.join root %)))
                            (catch :default _ false))
                      ["orgs" "scripts" "70-tools"])
        _ (when (empty? dirs)
            (println "REFUSED: none of orgs/, scripts/ or 70-tools/ exist under the root.")
            (js/process.exit 2))
        r (.spawnSync cp "rg" (clj->js (concat ["--files" "-g" "deps.edn" "-g" "bb.edn"
                                                "-g" "!node_modules" "-g" "!target" "-g" "!out"]
                                               dirs))
                      #js {:cwd root :encoding "utf8" :maxBuffer 268435456})]
    (when (<= (.-status r) 1)
      (remove str/blank? (str/split-lines (or (.-stdout r) ""))))))

(defn- repo-of [p]
  (let [ps (str/split p #"/")]
    (when (and (= "orgs" (first ps)) (>= (count ps) 3))
      (str (nth ps 1) "/" (nth ps 2)))))

(defn -main []
  (let [files (project-files)]
    (when (nil? files)
      (println "REFUSED: ripgrep did not enumerate project files; nothing was measured.")
      (js/process.exit 2))
    (when (< (count files) 500)
      (println (str "REFUSED: only " (count files) " project files found; this workspace"
                    " has thousands, so the walk did not run."))
      (js/process.exit 2))
    (let [rows
          (for [f files
                :let [txt (try (str (fs.readFileSync (pm.join root f) "utf8"))
                               (catch :default _ nil))]
                :when txt
                row (concat
                     (for [m (re-seq #":git/sha\s+\"([0-9a-fA-F]+)\"" txt)
                           :let [sha (second m)]]
                       {:kind (if (= 40 (count sha)) :addressed-git :prefix-git)
                        :file f :detail sha})
                     (for [_ (re-seq #":mvn/version\s+\"" txt)]
                       {:kind :name-maven :file f})
                     (for [m (re-seq #":local/root\s+\"([^\"]+)\"" txt)
                           :let [target (second m)
                                 abs (pm.resolve (pm.dirname (pm.resolve (pm.join root f))) target)
                                 rel (pm.relative (pm.resolve (pm.join root "orgs")) abs)
                                 ps  (str/split rel #"/")
                                 same? (and (>= (count ps) 2)
                                            (= (repo-of f) (str (nth ps 0) "/" (nth ps 1))))]]
                       {:kind (cond (str/starts-with? rel "..") :path-outside
                                    same? :addressed-same-repo
                                    :else :path-cross-repo)
                        :file f :detail target}))]
            row)
          rows (vec rows)
          by (group-by :kind rows)
          prefixes (vec (:prefix-git by))]
      (println (str "SCANNED\t" (count files) "\tproject files, " (count rows) " build inputs"))
      (if findings-mode?
        (do
          ;; one finding per FILE, not per coordinate: the same file repeated
          ;; would key the same finding many times and the count would not move
          (doseq [f (sort (distinct (map :file prefixes)))]
            (println (str "FINDING\twarn\t" f ":prefix-sha\t" f
                          " pins a dependency by a sha PREFIX. Seven hex is 2^28 and"
                          " the tag beside it is mutable, so this is a name with a"
                          " checksum rather than an address")))
          (println (str "FINDING\tinfo\tname-maven\t" (count (:name-maven by))
                        " :mvn/version coordinate(s) carry no integrity field."
                        " tools.deps has nowhere to put one, so this needs a lock --"
                        " Deno's shape, a SHA-256 per fetched artifact"))
          (println (str "FINDING\tinfo\tpath-cross-repo\t" (count (:path-cross-repo by))
                        " :local/root coordinate(s) point at ANOTHER repo by relative"
                        " path, so whatever is checked out is what gets built."
                        " Addressing them means giving each a revision"))
          (println (str "FINDING\tinfo\taddressed\t" (count (:addressed-git by))
                        " coordinate(s) are pinned by a full 40-hex sha and "
                        (count (:addressed-same-repo by))
                        " :local/root stay inside their own repo -- one content unit"
                        " either way")))
        (do
          (println)
          (doseq [[k n] (sort-by (comp - val) (frequencies (map :kind rows)))]
            (println (str "  " (name k) "\t" n)))
          (println)
          (when (seq prefixes)
            (println (str "  most repeated prefix: "
                          (let [[[v n]] (take 1 (sort-by (comp - val) (frequencies (map :detail prefixes))))]
                            (str "\"" v "\" x" n)))))))
      (js/process.exit (if (seq prefixes) 1 0)))))

(-main)
