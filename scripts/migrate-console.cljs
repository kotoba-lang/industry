;; cloud-itonami の operator console 生成器(各 repo の src/**/render_html.clj)を
;; jp-go-dds の互換スキンへ移行する。superproject ADR-2607261600 follow-up。
;;
;; **markup は一切変えない。** 生成器が `<style>` と `</style>` の間に書いている
;; 自前 CSS を、jp-go-dds の `dds.css + skin-css` を返す式に差し替えるだけ。
;; 生成物の見た目だけが DADS になり、表の中身(実 actor 実行の結果)は不変。
;;
;; 生成器は 1 repo ずつ形が違うが、`<style>` / `</style>` は必ず**文字列リテラルの
;; 内側**にある。そこで「`<style>` の直後で文字列を閉じ、式を挟み、`</style>` の
;; 直前で文字列を開き直す」形に書き換える —— リテラルの構造に依存しないので
;; 141 通りの書き方すべてに効く。
;;
;; usage:
;;   nbb migrate_console.cljs <repo-root> [--execute]
(require '[clojure.string :as str]
         '["fs" :as fs]
         '["path" :as path]
         '["child_process" :as cp])

(def ^:private dds-dep
  (str "        io.github.kotoba-lang/jp-go-digital-design-system\n"
       "        {:git/url \"https://github.com/kotoba-lang/jp-go-digital-design-system\"\n"
       "         :git/sha \"83db99546eabc53613ae826ea41d1533a3c574af\"}\n"))

(defn- find-render-html [root]
  (let [src (path/join root "src")]
    (when (fs/existsSync src)
      (first
       (for [d (fs/readdirSync src)
             :let [f (path/join src d "render_html.clj")]
             :when (fs/existsSync f)]
         f)))))

;; --- deps.edn に jp-go-dds を足す ------------------------------------------
(defn- patch-deps! [root execute?]
  (let [p (path/join root "deps.edn")
        s (fs/readFileSync p "utf8")]
    (cond
      (str/includes? s "jp-go-digital-design-system") :already
      (not (re-find #"(?m)^\s*:deps\s*\{" s)) :no-deps-map
      :else
      (let [out (str/replace s #"(?m)(^\s*:deps\s*\{)" (fn [m] (str (if (vector? m) (first m) m) "\n" dds-dep)))]
        (when execute? (fs/writeFileSync p out))
        :patched))))

;; --- render_html.clj の <style> 中身を差し替える ----------------------------
(def ^:private skin-expr
  ;; リテラルを閉じて式を挟み、また開く
  (str "\"\n   (jp-go-dds.skin/dds+skin)\n   \""))

;; 一部の生成器は HTML 全体を `format` で組み立てている。その template 文字列に
;; CSS を差し込むと、CSS 中の `%`(`width: 100%` 等)が変換指定子として解釈され
;; UnknownFormatConversionException になる(実測: 17 件中 15 件)。
;; その場合は `%` を `%%` にエスケープした式を差し込む。
(def ^:private skin-expr-format-safe
  (str "\"\n   (clojure.string/replace (jp-go-dds.skin/dds+skin) \"%\" \"%%\")\n   \""))

(defn- patch-render! [f execute? & [format-safe?]]
  (let [s (fs/readFileSync f "utf8")
        i (str/index-of s "<style>")
        j (when i (str/index-of s "</style>" i))]
    (cond
      (str/includes? s "jp-go-dds.skin") :already
      (or (nil? i) (nil? j)) :no-style
      :else
      (let [expr (if format-safe? skin-expr-format-safe skin-expr)
            head (subs s 0 (+ i (count "<style>")))
            tail (subs s j)
            ;; ns 形に require を足す(既存の :require ベクタの先頭へ)
            body (str head expr tail)
            body (if (re-find #"\(:require" body)
                   (str/replace body #"\(:require\s" "(:require [jp-go-dds.skin]\n            " )
                   body)]
        (when execute? (fs/writeFileSync f body))
        :patched))))

(let [[root & flags] *command-line-args*
      execute? (some #{"--execute"} flags)
      format-safe? (some #{"--format-safe"} flags)
      f (find-render-html root)]
  (if-not f
    (println "  render_html.clj が見つからない:" root)
    (do (println (str "  deps.edn   : " (name (patch-deps! root execute?))))
        (println (str "  " (path/basename f) " : " (name (patch-render! f execute? format-safe?)))))))
