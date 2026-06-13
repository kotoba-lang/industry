(ns build
  "警告をエラーとして扱う ClojureScript ビルド (Svelte 風: 警告が出たらビルド失敗)。
   `clojure -M:build` で実行。cljs.main には warnings-as-errors が無いため、
   コンパイラ API を直接叩き、警告を収集して1件でもあれば exit 1 する。"
  (:require [cljs.build.api :as api]
            [cljs.analyzer :as ana]
            [clojure.java.io :as io]))

(def ^:private collected (atom []))

(defn- collecting-handler
  "cljs コンパイラの警告ハンドラ。発火した警告を場所付きで収集する。"
  [warning-type env extra]
  (when (get ana/*cljs-warnings* warning-type)
    (when-let [msg (ana/error-message warning-type extra)]
      (let [file (or ana/*cljs-file* (:file env) "?")
            loc  (str file ":" (:line env) ":" (:column env))]
        (swap! collected conj (str loc " — " (name warning-type) ": " msg))))))

(defn- rmrf [^java.io.File f]
  (when (.isDirectory f) (run! rmrf (.listFiles f)))
  (.delete f))

(defn -main [& _]
  (reset! collected [])
  ;; インクリメンタルキャッシュが残っていると変更ファイルが再解析されず警告を取りこぼす。
  ;; CI ゲートとして信頼できるよう毎回フルコンパイルする。
  (rmrf (io/file "target/cljs-out"))
  (println "⏳ ClojureScript build (warnings-as-errors, clean)…")
  (api/build "src/cljs"
             {:output-to        "web/app.js"
              :output-dir       "target/cljs-out"
              :main             'gftd.app
              :optimizations    :simple
              ;; 静的に拾える警告を明示的に有効化
              :warnings         (merge ana/*cljs-warnings*
                                       {:undeclared-var     true
                                        :undeclared-ns      true
                                        :undeclared-ns-form true
                                        :fn-arity           true
                                        :fn-deprecated      true
                                        :invalid-arithmetic true
                                        :invoke-ctor        true})
              :warning-handlers [collecting-handler]})
  (let [ws @collected]
    (if (seq ws)
      (binding [*out* *err*]
        (println)
        (doseq [w ws] (println "❌" w))
        (println (str "\nbuild failed: " (count ws) " warning(s) treated as errors"))
        (System/exit 1))
      (println "✅ build ok — 0 warnings"))))
