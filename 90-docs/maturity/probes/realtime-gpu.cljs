(ns probe-realtime-gpu
  (:require ["child_process" :as cp] ["fs" :as fs] ["path" :as path] ["os" :as os]
            [clojure.string :as str]))
;; リアルタイム描画は実 GPU が要る。**nbb の Node に `navigator.gpu` が無いことは、
;; この能力が測れないことを意味しない** —— 2026-08-23 に測り直したところ、
;; `kotoba-lang/webgpu` は既に **8 本の Playwright テスト**を持っており、実ブラウザで
;; 実 GPU（"real GPU: apple metal-3"）に描いて**画素を数えて**いる。
;; 前版の probe は「Node に navigator.gpu が無い」で止まり、**別の経路で測れることを
;; 探さずに UNMEASURABLE を出していた** —— 測れない理由の報告としては正しく、
;; 測定の試みとしては不足だった。
;;
;; ここで守る 3 つ:
;;   (1) ブラウザが無い機械では **FAIL ではなく UNMEASURABLE**（機械の話であって
;;       ライブラリの話ではない）
;;   (2) **evidence floor** —— 走ったテスト数と assertion 数に床を置く。0 件を
;;       「失敗 0 件」として緑にしない
;;   (3) **実 GPU の証拠**を出力から要求する。SwiftShader へ落ちた実行を
;;       「WebGPU リアルタイム描画が動く」と読ませない
(defn- chrome-binary
  "ms-playwright キャッシュから Chrome for Testing を**探して**返す。パスを定数で
  持たない —— バージョン付きディレクトリは playwright の更新で動く。"
  []
  (let [root (path/join (os/homedir) "Library" "Caches" "ms-playwright")]
    (when (fs/existsSync root)
      (->> (fs/readdirSync root)
           (filter #(str/starts-with? % "chromium-"))
           sort
           reverse
           (map #(path/join root % "chrome-mac-arm64"
                            "Google Chrome for Testing.app" "Contents" "MacOS"
                            "Google Chrome for Testing"))
           (filter fs/existsSync)
           first))))

(try
  (let [orgs (or (aget js/process.env "THREED_ORGS")
                 (path/join (js/process.cwd) "orgs"))
        repo (path/join orgs "kotoba-lang" "webgpu")
        exe (chrome-binary)]
    (cond
      (not (fs/existsSync repo))
      (println "PROBE realtime-gpu UNMEASURABLE"
               (str "kotoba-lang/webgpu の checkout が無い（" repo "）"))
      (nil? exe)
      (println "PROBE realtime-gpu UNMEASURABLE"
               "Chrome for Testing が ms-playwright キャッシュに無い —— この機械では実ブラウザ E2E を回せない（ライブラリの状態ではない）")
      :else
      (let [r (cp/spawnSync "clojure" #js ["-M:test" "-r" "playwright.*-test$"]
                            #js {:cwd repo :encoding "utf8"
                                 :env (js/Object.assign #js {} js/process.env #js {"PW_EXE" exe})
                                 :timeout 900000})
            out (str (.-stdout r) (.-stderr r))
            ;; **最後の**要約を取る。cognitect の runner は名前空間ごとにも
            ;; 「Ran N tests」を出すので、`re-find` は最初の 1 名前空間分
            ;; （実測 1 tests / 3 assertions）を掴み、床に引っかかって
            ;; 「本数が減った」と誤報する。合計は最後の行にある。
            ran (last (re-seq #"Ran (\d+) tests containing (\d+) assertions" out))
            fails (last (re-seq #"(\d+) failures, (\d+) errors" out))
            n (when ran (js/parseInt (nth ran 1)))
            a (when ran (js/parseInt (nth ran 2)))
            real-gpu (re-find #"real GPU: ([^\n]+)" out)]
        (cond
          (= "SIGTERM" (.-signal r))
          (println "PROBE realtime-gpu UNMEASURABLE" "playwright スイートが 900 秒で切れた")
          ;; ブラウザが起動できなかったのは**機械の話**で、ライブラリが
          ;; 壊れている話ではない。床の検査より先に切り分ける —— さもないと
          ;; 「8 本中 1 本しか走らなかった」という**本当だが誤解を招く**理由が
          ;; 報告される（実測: 壊れた PW_EXE で 1 tests / 1 assertion）。
          (re-find #"Failed to launch|executable doesn't exist" out)
          (println "PROBE realtime-gpu UNMEASURABLE"
                   (str "ブラウザを起動できない: "
                        (first (re-find #"(executable doesn't exist at [^\n]+)" out))))
          (nil? ran)
          (println "PROBE realtime-gpu UNMEASURABLE"
                   (str "テストが走った形跡が出力に無い: " (str/join " / " (take-last 3 (str/split-lines (str/trim out))))))
          ;; evidence floor —— 0 件を「失敗 0 件」として緑にしない
          (or (< n 8) (< a 30))
          (println "PROBE realtime-gpu FAIL"
                   (str "走った本数が床を下回る: " n " tests / " a " assertions（床 8 / 30）"
                        " —— 静かに減った suite は「全部通った」と同じ顔をする"))
          (not= ["0 failures, 0 errors" "0" "0"] (vec fails))
          (println "PROBE realtime-gpu FAIL"
                   (str "実ブラウザの描画テストが落ちている: " (first fails)))
          (nil? real-gpu)
          (println "PROBE realtime-gpu FAIL"
                   "出力に実 GPU の申告が無い —— SwiftShader へ落ちた実行を「リアルタイム描画が動く」と読まない")
          :else
          (println "PROBE realtime-gpu PASS"
                   (str "実ブラウザ E2E が " n " tests / " a " assertions で通る（"
                        (str/trim (nth real-gpu 1)) "）—— sky/mesh/frame/vertex-layout/"
                        "rect-extent/anim/webgl2 を実画素で検査")))))) 
  (catch :default ex (println "PROBE realtime-gpu UNMEASURABLE" (.-message ex))))
