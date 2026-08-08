#!/usr/bin/env nbb
;; org-id-derivation-check.cljs — reverse-DNS 導出が 1 つであることを押さえる gate。
;;
;; **なぜ要るか。** 同じ規則（registrable domain のラベル逆順）が 3 箇所に在った:
;; `manifest/repository-rules.edn` の `:org-domain` が持つ手書きの `:reversed` 列、
;; `verify-repository-roles.cljs` の `domain->prefix`、そして cloud-itonami の
;; org id 受付。実測 2026-08-08、受付は 6 org すべてで**逆向き**を返しており
;; （`awai.network -> awai-network`、規則は `network-awai` = west.yml の remote 名）、
;; どの検査もそれを見ていなかった（ADR-2608094000）。
;;
;; **この gate は規則を実装しない。** 3 箇所を 4 箇所にするだけなので。
;; 代わりに、既に在る 3 つの表現を互いに突き合わせる:
;;
;;   1. **artifact <-> source** —— `manifest/org-id.mjs` の `sourceDigest` が
;;      `manifest/org-id.kotoba` の sha256 と一致するか。`kotoba -M compile` は
;;      sha256 をそのまま焼くので、ノードに kotoba ツールチェーンが無くても
;;      束縛を確かめられる（committed な生成物は sha256 で見る、という規則）。
;;   2. **purity** —— `requiredCapabilities` が空。ドメイン文字列を畳むだけの
;;      関数が ambient authority を得たら、そこで止める。
;;   3. **kotoba == cljs** —— `scripts/org_id_derivation.cljs` を**実際に呼んで**
;;      記録されている全ドメインで突き合わせる。gate は単一ファイルで配られ
;;      classpath を持たないので、`cloud-itonami-sites-check` と同じく
;;      **対象を子プロセスで起動する**（ロジックを複製しない）。
;;   4. **手書きの `:reversed` 列 == 導出** —— これまで誰も見ていなかった。
;;      7 org 分しかないので手で合っていたが、合っている理由が「たまたま」だった。
;;   5. **形の判定**（registrable-shape）が両実装で一致するか。
;;
;; **`:reversed` を消して導出に置き換えない。** 手書きの列が残っているのは
;; 一致が**検査できる**からで、消すと突き合わせる相手がいなくなる。
;;
;; ノード側で `npx nbb org-id-derivation-check.cljs <dir> [--min N]`。

(ns fleet-ci.gates.org-id-derivation-check
  (:require ["node:child_process" :as cp]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(def ^:private value-flags #{"--min"})

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

(defn- opt [flag default]
  (let [i (.indexOf args flag)]
    (if (neg? i) default (get args (inc i)))))

(def min-domains (js/parseInt (opt "--min" "90")))

(def failures (atom []))

(defn- fail! [& parts]
  (let [m (str/join " " (map str parts))]
    (swap! failures conj m)
    (println "FAIL" m)))

(defn- read-edn [p] (edn/read-string (fs/readFileSync p "utf8")))

(defn- sha256 [buf]
  (-> (crypto/createHash "sha256") (.update buf) (.digest "hex")))

;; ---------------------------------------------------------------------------
;; 入力: 記録されているドメインと org 規則。

(defn- recorded-domains []
  (let [p (path/join root "manifest" "origin-domains.edn")]
    (if-not (fs/existsSync p)
      (do (fail! "missing" p) [])
      (let [d (read-edn p)]
        (vec (sort (distinct (vals (merge (:anchors d {}) (:domains d {}))))))))))

(defn- org-domain-rules []
  (let [p (path/join root "manifest" "repository-rules.edn")]
    (if-not (fs/existsSync p)
      (do (fail! "missing" p) [])
      (let [doc (read-edn p)
            v (first (filter #(= :org-domain (:vocabulary/id %)) (:vocabularies doc)))]
        (vec (:vocabulary/rules v))))))

(def shape-cases ["localhost" "a..b" ".x.com" "x.com." "co.jp" "awai.network"])

;; ---------------------------------------------------------------------------
;; cljs 側: 検査対象を子プロセスで起動して答えだけ受け取る。**規則を写さない。**

(defn- cljs-answers
  "`scripts/org_id_derivation.cljs` を classpath 付きで起動し、
  {domain -> prefix} と {domain -> shape?} を EDN で受け取る。"
  [domains]
  (let [expr (str "(require '[scripts.org-id-derivation :as o])"
                  "(prn {:prefix (into {} (map (juxt identity o/domain->prefix)) "
                  (pr-str (vec domains)) ")"
                  " :shape (into {} (map (juxt identity (comp boolean o/registrable-shape?))) "
                  (pr-str (vec (concat domains shape-cases))) ")})")
        r (cp/spawnSync "npx" (clj->js ["--yes" "nbb" "--classpath" "." "-e" expr])
                        #js {:cwd root :encoding "utf8" :maxBuffer (* 32 1024 1024)})
        out (str (aget r "stdout"))
        err (str (aget r "stderr"))]
    (if (or (not= 0 (aget r "status")) (str/blank? out))
      (do (fail! "could not run scripts/org_id_derivation.cljs —"
                 (str/join " " (take-last 3 (str/split-lines (str err out)))))
          nil)
      (try (edn/read-string out)
           (catch :default e (fail! "unreadable output from the cljs derivation:" (.-message e)) nil)))))

;; ---------------------------------------------------------------------------

(defn- check-parity! [m cljs domains rules]
  (let [instantiate (aget m "instantiateKotoba")
        ;; fuel は instance ごとに尽きる（実測: 1 instance を 7 回使い回して
        ;; fuel-exhausted）。1 呼び出し 1 instance にする。
        k-derive (fn [d] ((aget (instantiate #js {}) "derive-org-id") d))
        k-shape  (fn [d] (js/Number ((aget (instantiate #js {}) "registrable-shape") d)))
        prefixes (:prefix cljs)
        shapes   (:shape cljs)]
    ;; 3. 記録されている全ドメインで kotoba == cljs
    (doseq [d domains]
      (let [a (k-derive d) b (get prefixes d)]
        (when-not (= a b)
          (fail! d "— kotoba says" (pr-str a) "but cljs says" (pr-str b)))))
    ;; 4. 手書きの :reversed 列 == 導出
    (doseq [{:keys [org domain reversed]} rules]
      (let [a (k-derive domain)]
        (when-not (= a reversed)
          (fail! ":org-domain" org "records :reversed" (pr-str reversed)
                 "but" domain "derives" (pr-str a)))))
    ;; 5. 形の判定が一致するか
    (doseq [d (concat domains shape-cases)]
      (let [a (= 1 (k-shape d)) b (boolean (get shapes d))]
        (when-not (= a b)
          (fail! "shape disagreement on" (pr-str d) "— kotoba:" a "cljs:" b))))))

(defn- main! []
  (let [kotoba-src (path/join root "manifest" "org-id.kotoba")
        artifact   (path/join root "manifest" "org-id.mjs")]
    (cond
      (not (fs/existsSync kotoba-src)) (do (fail! "missing" kotoba-src)
                                           (set! (.-exitCode js/process) 1))
      (not (fs/existsSync artifact))   (do (fail! "missing" artifact)
                                           (set! (.-exitCode js/process) 1))
      :else
      (let [want (sha256 (fs/readFileSync kotoba-src))
            js   (fs/readFileSync artifact "utf8")
            got  (second (re-find #"sourceDigest:\"([0-9a-f]+)\"" js))
            caps (second (re-find #"requiredCapabilities:Object\.freeze\(\[([^\]]*)\]\)" js))]
        ;; 1. artifact <-> source
        (when-not (= want got)
          (fail! "artifact does not belong to the committed source:"
                 "org-id.kotoba sha256" want "but org-id.mjs carries" (str got)))
        ;; 2. purity
        (when-not (= "" (str/trim (str caps)))
          (fail! "the derivation is no longer pure — requiredCapabilities:" caps))
        (println "artifact<->source:" (if (= want got) "bound" "BROKEN")
                 "· capabilities:" (if (= "" (str/trim (str caps))) "none" caps))

        (let [domains (recorded-domains)
              rules   (org-domain-rules)]
          ;; 絞り込みが壊れて 0 件を「合格」にしない床。上限ではない。
          (when (< (count domains) min-domains)
            (fail! "only" (count domains) "recorded domains found, floor is" min-domains))
          (if-let [cljs (cljs-answers domains)]
            (-> (js/import (str "file://" (path/resolve artifact)))
                (.then (fn [m]
                         (check-parity! m cljs domains rules)
                         (println (str "checked " (count domains) " recorded domains + "
                                       (count rules) " org rules + "
                                       (count shape-cases) " shape cases, "
                                       (count @failures) " failing"))
                         (when (seq @failures)
                           (println "")
                           (println "ADR-2608094000: 導出の正本は manifest/org-id.kotoba。")
                           (println "cljs 側は scripts/org_id_derivation.cljs の 1 箇所だけ。")
                           (set! (.-exitCode js/process) 1))))
                (.catch (fn [e]
                          (fail! "could not import the compiled artifact:" (.-message e))
                          (set! (.-exitCode js/process) 1))))
            (set! (.-exitCode js/process) 1)))))))

(main!)
