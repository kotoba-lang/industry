(ns tasuke-app.input
  "Reading what a member typed. Portable, so it is testable off the browser.

  This is host work, not a decision: the guest decides what a loss MEANS, this
  decides what the characters SAY. The split matters because the answer lands in
  a filing — 被害額算定書 and 被害届 both print it, and an amount that reads
  differently from what the member meant is a false statement in a document they
  sign.

  ## Why it returns `:ok?` instead of a number

  MEASURED 2026-08-29 on the first version: 「48万5千円」 fell through the 万
  pattern, hit a `[^0-9]`-strip fallback, and became **485** — the filing would
  have claimed 金 485 円 for a 485,000 円 loss, off by three orders of magnitude,
  with nothing anywhere saying so. A parser that guesses is worse here than one
  that says it could not read the input, so unreadable text is reported as
  unreadable and the page asks rather than assuming."
  (:require [clojure.string :as str]))

(defn- ->long [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn- ->double [s]
  #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

(def ^:private none #{"なし" "無し" "ない" "不明" "0"})

(defn- normalize [s]
  (-> (str s)
      (str/replace #"[０-９]" #(str (char (- (int (first %)) 65248))))  ; 全角数字
      (str/replace #"[,，\s　]" "")
      (str/replace "円" "")
      (str/replace "¥" "")
      (str/replace "約" "")
      str/trim))

(defn parse-yen
  "→ `{:jpy <long> :ok? <bool>}`. `:ok? false` means the text carried something
  this could not read; the caller must not present `:jpy` as the member's figure."
  [s]
  (let [t (normalize s)]
    (cond
      (str/blank? t)     {:jpy 0 :ok? true :empty? true}
      (contains? none t) {:jpy 0 :ok? true}
      (re-matches #"\d+" t) {:jpy (->long t) :ok? true}
      :else
      (let [m (re-matches #"(?:(\d+(?:\.\d+)?)億)?(?:(\d+(?:\.\d+)?)万)?(?:(\d+)千)?(\d+)?" t)
            [_ oku man sen rest] m]
        (if (and m (some some? [oku man sen rest]))
          {:jpy (long (+ (* (if oku (->double oku) 0) 100000000)
                         (* (if man (->double man) 0) 10000)
                         (* (if sen (->long sen) 0) 1000)
                         (if rest (->long rest) 0)))
           :ok? true}
          {:jpy 0 :ok? false})))))
