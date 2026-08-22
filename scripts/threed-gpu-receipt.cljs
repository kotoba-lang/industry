#!/usr/bin/env nbb
;; threed-gpu-receipt — 実ブラウザ・実 GPU の測定を 1 回走らせ、**受領書**を書く。
;;
;; なぜ probe から切り離すか: `:render/realtime-gpu` の probe は
;; `kotoba-lang/webgpu` の Playwright スイート（実測 130 秒、実ブラウザ起動込み）を
;; 直接回していた。単体で走らせれば通るが、**60 本の probe と並ぶ全軸 audit の
;; 中では 600 秒の上限に当たり、UNMEASURABLE になる**（実測 2026-08-23、load
;; 100〜308 の両方で再現）。つまり軸の状態がマシンの混み具合で揺れる —— 「測れ
;; なかった」は正直だが、揺れる信号は判断に使えない。
;;
;; だから測定はここで 1 回だけ行い、**何を測ったかを受領書に残す**:
;;   - 測定時の `kotoba-lang/webgpu` の HEAD sha
;;   - 走ったテスト数 / assertion 数 / 失敗数
;;   - GPU の申告文字列（SwiftShader へ落ちていないことの証拠）
;; probe は受領書を読み、**sha が現在の HEAD と違えば PASS を出さない**。
;; 古い受領書で緑を出すのは、測っていないものを緑にすることと同じ。
;;
;;   nbb --classpath ".:scripts/nbb_compat" scripts/threed-gpu-receipt.cljs
;;
;; exit 0 = 受領書を書いた  3 = 測れなかった（ブラウザが無い等）  1 = テストが落ちた

(ns threed-gpu-receipt
  (:require [clojure.string :as str]
            ["fs" :as fs] ["path" :as path] ["os" :as os] ["child_process" :as cp]))

(def root (or (aget js/process.env "THREED_ROOT") (js/process.cwd)))
(def orgs (or (aget js/process.env "THREED_ORGS") (path/join root "orgs")))
(def repo (path/join orgs "kotoba-lang" "webgpu"))
(def out-file (path/join root "90-docs" "maturity" "receipts" "realtime-gpu.edn"))

(defn refuse! [code msg]
  (binding [*print-fn* *print-err-fn*] (println (str "REFUSED\t" msg)))
  (js/process.exit code))

(defn chrome-binary
  "ms-playwright キャッシュから Chrome for Testing を**探す**。パスを定数で持たない
  —— バージョン付きディレクトリは playwright の更新で動く。"
  []
  (let [cache (path/join (os/homedir) "Library" "Caches" "ms-playwright")]
    (when (fs/existsSync cache)
      (->> (fs/readdirSync cache)
           (filter #(str/starts-with? % "chromium-"))
           sort reverse
           (map #(path/join cache % "chrome-mac-arm64"
                            "Google Chrome for Testing.app" "Contents" "MacOS"
                            "Google Chrome for Testing"))
           (filter fs/existsSync)
           first))))

(defn -main []
  (when-not (fs/existsSync repo)
    (refuse! 3 (str "kotoba-lang/webgpu の checkout が無い（" repo "）")))
  (let [exe (chrome-binary)]
    (when-not exe
      (refuse! 3 "Chrome for Testing が ms-playwright キャッシュに無い —— この機械では実ブラウザ E2E を回せない"))
    (let [sha (str/trim (str (.-stdout (cp/spawnSync "git" #js ["-C" repo "rev-parse" "HEAD"]
                                                     #js {:encoding "utf8"}))))
          started (.now js/Date)
          r (cp/spawnSync "clojure" #js ["-M:test" "-r" "playwright.*-test$"]
                          #js {:cwd repo :encoding "utf8"
                               :env (js/Object.assign #js {} js/process.env #js {"PW_EXE" exe})
                               :timeout 1800000})
          out (str (.-stdout r) (.-stderr r))
          took (Math/round (/ (- (.now js/Date) started) 1000))
          ;; **最後の**要約を取る。runner は名前空間ごとにも「Ran N tests」を出すので、
          ;; 最初の一致は 1 名前空間分にすぎない。
          ran (last (re-seq #"Ran (\d+) tests containing (\d+) assertions" out))
          fails (last (re-seq #"(\d+) failures, (\d+) errors" out))
          gpu (last (re-seq #"real GPU: ([^\n]+)" out))]
      (when (some? (.-signal r))
        (refuse! 3 (str "playwright スイートが signal " (.-signal r) " で終了した")))
      (when (re-find #"Failed to launch|executable doesn't exist" out)
        (refuse! 3 "ブラウザを起動できなかった"))
      (when-not ran
        (refuse! 3 (str "テストが走った形跡が出力に無い: "
                        (str/join " / " (take-last 3 (str/split-lines (str/trim out)))))))
      (let [receipt {:receipt/kind :threed/realtime-gpu
                     :receipt/repo "kotoba-lang/webgpu"
                     :receipt/revision sha
                     :receipt/tests (js/parseInt (nth ran 1))
                     :receipt/assertions (js/parseInt (nth ran 2))
                     :receipt/failures (js/parseInt (nth fails 1))
                     :receipt/errors (js/parseInt (nth fails 2))
                     :receipt/gpu (when gpu (str/trim (nth gpu 1)))
                     :receipt/seconds took
                     :receipt/browser (path/basename exe)
                     :receipt/at (.toISOString (js/Date.))
                     ;; 測ったときの負荷を値の隣に置く —— 秒数だけ見て
                     ;; 「速くなった/遅くなった」と読ませないため。
                     :receipt/load1 (first (.loadavg os))}]
        (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
        (fs/writeFileSync out-file
                          (str ";; threed-gpu-receipt — **生成物。手で編集しない。**\n"
                               ";; 再生成: nbb --classpath \".:scripts/nbb_compat\" scripts/threed-gpu-receipt.cljs\n"
                               ";; probe は :receipt/revision が webgpu の現 HEAD と一致するときだけ\n"
                               ";; これを証拠として使う。古い受領書で緑を出さないため。\n"
                               (str (pr-str receipt) "\n")))
        (println (str "RECEIPT\t" sha "\t" (:receipt/tests receipt) " tests / "
                      (:receipt/assertions receipt) " assertions / "
                      (:receipt/failures receipt) " failures / "
                      (or (:receipt/gpu receipt) "GPU 申告なし") "\t" took "s"))
        (js/process.exit (if (and (zero? (:receipt/failures receipt))
                                  (zero? (:receipt/errors receipt))) 0 1))))))

(-main)
