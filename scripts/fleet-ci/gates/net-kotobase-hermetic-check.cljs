#!/usr/bin/env nbb
;; net-kotobase-hermetic-check.cljs — network-awai/net-kotobase の hermetic gate。
;;
;; **なぜ fleet に置くか。** network-awai org の GitHub Actions は
;; 2026-08-03T01:10Z 以降 1 本も起動していない（実測: 全 workflow が failure、
;; job は steps を 1 件も記録せず 1-10 秒で終了）。com-junkawasaki / gftdcojp が
;; 2026-07-26 に落ちたのと同じ症状で、ADR-2607300900 が「Actions ではなく fleet」
;; と決めた経路を、この org にも適用する。
;;
;; **何を検査するか。** 死んだ workflow のうち、ノードで hermetic に再現できる
;; ものだけ。検査ロジックはここに複製しない — repo 自身の verifier をそのまま
;; 呼ぶ（fleet 側と repo 側で別実装を持つと、片方だけ通る状態が黙って生まれる）。
;;
;;   A. enterprise-isolation.yml 全体   node scripts/verify-tenant-isolation.mjs
;;   B. metadata.yml 全体               bb scripts/check-metadata.cljc
;;   C. worker.yml の syntax 半分       bb <script> --syntax-check（root + worker/scripts）
;;   D. sdk.yml の python leg           py_compile + unittest test_gremlin.py
;;   E. 大容量 object grant             nbb で object-grant-test（判断規則）と
;;                                     object-grant-route-test（shell: 拒否が
;;                                     presigner にも recorder にも到達しない）
;;
;; **2026-08-04: レイアウト追従。** 初版は `clj-edge/` `worker/` を前提にしていたが、
;; main はその後 `kotobase-api-gateway-cljs/` / `kotobase-api-gateway/` に作り替え
;; られ、west pin (1fd2f4a3) が 55 commit 分それを追っていなかった。pin を main に
;; 進めた時点でこの gate は `worker/scripts` を見失って即死する（= 検査が 1 つも
;; 走らないまま赤）ので、pin 前進と同じ commit で追従させる。この gate が
;; 「main の実体」ではなく「pin の実体」を見ていたことが、ズレに気づけなかった理由。
;;
;; **持ってこられないもの**（黙って落とさず、ADR に理由付きで列挙する）:
;;   - `npm ci` / `pnpm install` / `shadow-cljs` / `cargo` を要するもの
;;     （worker release, protocols-worker, kotobase-cf-wasm, authn, sdk の
;;      ts/rust leg, repository-security の npm audit）。ノードは tailnet だけに
;;      繋がっていて npm registry に届かない（tick.cljs `ship-git-deps!` が
;;      同じ理由で JVM 依存を operator 側から配っている）。
;;   - live smoke（public-smoke / protocols-public-smoke）。kotobase.net への
;;     egress がノードに無い。**operator 側には有る**ので、これは別経路の課題。
;;   - deploy と b2-dr-drill。credential が要る = 「鍵はノードに配らない」
;;     （fleet-ci 不変条件 3）と両立しない。
;;
;; ノード側で `npx nbb net-kotobase-hermetic-check.cljs <dir> [--min-surfaces N]
;; [--min-syntax N]` として実行。
(ns fleet-ci.gates.net-kotobase-hermetic-check
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def args (vec *command-line-args*))

(def ^:private value-flags #{"--min-surfaces" "--min-syntax"})

(defn- flag [nm default]
  (let [i (.indexOf args nm)]
    (if (neg? i) default (get args (inc i) default))))

(def root
  (loop [[a & more] args prev nil]
    (cond (nil? a) "."
          (str/starts-with? a "--") (recur more a)
          (contains? value-flags prev) (recur more nil)
          :else a)))

;; false-pass の床。検査対象が壊れて空になったときに「0 件検証して合格」を
;; 返さないため（docs-edn-check の --min と同じ役割）。
(def min-surfaces (js/parseInt (str (flag "--min-surfaces" 8)) 10))
(def min-syntax (js/parseInt (str (flag "--min-syntax" 20)) 10))

(defn- die! [code & msg]
  (println (str "FLEET-CI: " (str/join " " msg)))
  (js/process.exit code))

(defn- run
  "ノード内の子プロセスを 1 回。-> {:rc n :out s}。
  ここの exit code は信用してよい（信用できないのは ssh 越しの終了ステータスで、
  それは gate 全体の verdict 伝達の話。ADR-2607255500）。

  **stdout と stderr を必ず両方拾う。** execFileSync は成功時に stdout しか
  返さないので、summary を stderr に書く道具（python の unittest がまさにそう）
  を『何も報告しなかった』と誤読する。実際に最初の版はこれで
  `Ran 2 tests ... OK` を取りこぼし、通っているテストを fail と報告した。"
  [cmd argv opts]
  (let [r (cp/spawnSync cmd (clj->js (vec argv))
                        (clj->js (merge {:encoding "utf8" :maxBuffer 33554432} opts)))]
    {:rc (if (nil? (.-status r)) 1 (.-status r))
     :out (str (or (some-> (.-stdout r) str) "")
               (or (some-> (.-stderr r) str) "")
               (when (.-error r) (str "\n" (.. r -error -message))))}))

(defn- have? [bin]
  (zero? (:rc (run "bash" ["-c" (str "command -v " bin)] {}))))

(defn- tail [s n]
  (str/join "\n" (take-last n (str/split-lines (str/trim (str s))))))

;; ---------------------------------------------------------------------------
;; 展開が正しいことを先に確かめる（tarball の中身が想定と違うまま「合格」しない）

(def isolation-verifier (path/join root "scripts" "verify-tenant-isolation.mjs"))
(def root-scripts-dir (path/join root "scripts"))
(def worker-scripts-dir (path/join root "kotobase-api-gateway" "scripts"))
(def py-dir (path/join root "sdk" "kotobase-py"))

(doseq [p [isolation-verifier root-scripts-dir worker-scripts-dir py-dir]]
  (when-not (fs/existsSync p)
    (die! 90 "missing after extract:" p "— refusing to report a pass")))

;; ノードに道具が無いのは「検証できなかった」であって「合格」ではない。
(doseq [bin ["node" "bb" "python3" "git"]]
  (when-not (have? bin)
    (die! 91 bin "is not installed on this node — refusing to report a pass"
          "(provision it with scripts/fleet-ci/provision.cljs)")))

(def failures (atom []))
(defn- fail! [step & msg]
  (swap! failures conj step)
  (println (str "  FAIL " step ": " (str/join " " msg))))

;; ---------------------------------------------------------------------------
;; A. tenant isolation matrix（= enterprise-isolation.yml 全体）

(let [{:keys [rc out]} (run "node" ["scripts/verify-tenant-isolation.mjs"] {:cwd root})
      n (some-> (re-find #"surfaces=(\d+)" (str out)) second js/parseInt)]
  (println "A tenant-isolation:" (str/trim (str (tail out 3))))
  (cond
    (not (zero? rc)) (fail! "tenant-isolation" "verifier exited" rc)
    (nil? n) (fail! "tenant-isolation" "no `surfaces=N` in output — the verifier"
                    "did not report what it checked")
    (< n min-surfaces) (fail! "tenant-isolation" "only" n "surfaces (<" min-surfaces
                              ") — refusing to report a pass on a near-empty matrix")))

;; ---------------------------------------------------------------------------
;; B. metadata（= metadata.yml 全体）
;;
;; fleet-ci は git checkout ではなく `git archive` の tarball を配る。
;; check-metadata.cljc は対象ファイルの列挙に
;; `git ls-files --cached --others --exclude-standard` を使うので、素の展開
;; ディレクトリでは signal 128 で死ぬ（= 検査が 1 つも走らないのに出力は出る）。
;; tarball の中身は **その sha で tracked だったファイルそのもの**なので、
;; init + add -A で同じ集合を復元する。ファイルを足しも引きもしない。

(when-not (fs/existsSync (path/join root ".git"))
  (let [i (run "git" ["init" "-q"] {:cwd root})
        a (run "git" ["add" "-A"] {:cwd root})]
    (when-not (and (zero? (:rc i)) (zero? (:rc a)))
      (die! 90 "could not reconstruct a git index for the extracted tree:"
            (tail (str (:out i) (:out a)) 3)
            "— check-metadata enumerates files with `git ls-files`, so without it"
            "the check silently inspects nothing"))))

(let [{:keys [rc out]} (run "bb" ["scripts/check-metadata.cljc"] {:cwd root})
      lines (count (remove str/blank? (str/split-lines (str out))))]
  (println "B metadata:" lines "checks reported")
  (cond
    (not (zero? rc)) (do (println (tail out 15))
                         (fail! "metadata" "check-metadata exited" rc))
    (zero? lines) (fail! "metadata" "check-metadata printed nothing — refusing to"
                         "report a pass on a check that reported no work")))

;; ---------------------------------------------------------------------------
;; C. babashka script syntax（= worker.yml の syntax 半分）
;;
;; workflow は 22 本を名指しで列挙していた。ここでは **ディレクトリを走査して、
;; 対象外だけを名指しで除外する**（包含リストではなく除外リスト）。
;; 包含リストは新しい script が足されたときに黙って取り残される＝ fail open。
;; 除外リストなら、`--syntax-check` を実装していない script が増えたとき gate が
;; 赤くなり、「flag を実装する」か「理由付きでここに足す」かの判断を強制できる。
;;
;; 除外の理由は 3 本とも「そもそも --syntax-check を受け付けない」:
;;   check-metadata        フラグを取らない。**B で素のまま実行しているので既に検査済み**
;;   cognitect-wire-check  bb ではなく nbb 用で、clj-edge/src を classpath に要求する
;;   testnet-secrets       kagi を叩く secrets helper。引数無しでは usage を出して落ちる

;; **`--syntax-check` が実際に見ているもの**（過大評価しないこと、実測 2026-08-03）:
;; bb は form を頭から読んで評価し、script が flag を見て自分で exit する。つまり
;; **その exit 点より後ろにある壊れた form は読まれない**。感度測定で確認した:
;; ops-check.cljc の末尾に `(defn broken [` を足しても 21/21 pass のまま、ns form の
;; 直後に同じものを入れると edamame の parse error で落ちた。GitHub Actions の同じ
;; step も同じ意味しか持っていなかった（この gate はそれを忠実に移しただけで、
;; 強くはしていない）。
(def syntax-skip
  {"scripts/check-metadata.cljc" "no --syntax-check flag (covered bare in check B)"
   "scripts/cognitect-wire-check.cljc" "nbb script, needs kotobase-api-gateway-cljs/src on the classpath"
   "kotobase-api-gateway/scripts/testnet-secrets.cljc" "secrets helper, requires arguments"})

(def syntax-candidates
  (vec (concat
        (for [f (sort (fs/readdirSync root-scripts-dir))
              :when (str/ends-with? f ".cljc")]
          {:cwd root :rel (str "scripts/" f) :key (str "scripts/" f)})
        (for [f (sort (fs/readdirSync worker-scripts-dir))
              :when (str/ends-with? f ".cljc")]
          {:cwd (path/join root "kotobase-api-gateway") :rel (str "scripts/" f)
           :key (str "kotobase-api-gateway/scripts/" f)}))))

(def syntax-targets (vec (remove #(contains? syntax-skip (:key %)) syntax-candidates)))

(doseq [[k why] (sort syntax-skip)]
  (println "  skip" k "—" why))

;; 除外リストも腐る。消えた script を除外し続けていると、次に同名の script が
;; 生えたとき黙って検査対象から外れる。
(let [present (set (map :key syntax-candidates))
      dead (remove present (keys syntax-skip))]
  (when (seq dead)
    (fail! "syntax" "stale exclusions (files no longer exist):" (str/join ", " dead))))

(if (< (count syntax-targets) min-syntax)
  (fail! "syntax" "only" (count syntax-targets) "babashka scripts found (<" min-syntax
         ") — the tree is not what this gate expects")
  (let [bad (atom [])]
    (doseq [{:keys [cwd rel]} syntax-targets]
      (let [{:keys [rc out]} (run "bb" [rel "--syntax-check"] {:cwd cwd})]
        (when-not (zero? rc)
          (swap! bad conj [rel (tail out 3)]))))
    (println "C syntax:" (count syntax-targets) "babashka scripts,"
             (count @bad) "failed")
    (doseq [[rel o] @bad] (println "    " rel "→" o))
    (when (seq @bad) (fail! "syntax" (count @bad) "scripts failed --syntax-check"))))

;; ---------------------------------------------------------------------------
;; D. Python SDK conformance（= sdk.yml の python leg）
;;
;; hermetic: test_gremlin.py は localhost に mock の Gremlin WebSocket サーバを
;; 立てて自分の client をそれに喋らせる。**kotobase 本体が Gremlin を喋る証明では
;; ない** — SDK が GraphSON bytecode envelope を正しく組み立てる証明である。

(let [c (run "python3" ["-m" "py_compile" "kotobase.py" "kotobase_auth.py"] {:cwd py-dir})]
  (if-not (zero? (:rc c))
    (fail! "sdk-python" "py_compile failed:" (tail (:out c) 5))
    (let [{:keys [rc out]} (run "python3" ["-m" "unittest" "-v" "test_gremlin.py"] {:cwd py-dir})
          n (some-> (re-find #"Ran (\d+) tests?" (str out)) second js/parseInt)]
      (println "D sdk-python:" (str "Ran " n " tests, rc=" rc))
      (cond
        (nil? n) (do (println (tail out 10))
                     (fail! "sdk-python" "no `Ran N tests` summary — refusing to"
                            "report a pass"))
        (zero? n) (fail! "sdk-python" "zero tests ran")
        (not (zero? rc)) (do (println (tail out 15))
                             (fail! "sdk-python" "unittest exited" rc))))))

;; ---------------------------------------------------------------------------
;; E. 大容量 object grant の判断規則（ADR-2608012600 D4）
;;
;; hermetic: 純関数の決定ロジックのみで、署名も storage も datom も触らない。
;; nbb で走るのは対象が Maven 依存を持たない `.cljc` だから（ノードは registry に
;; 届かない）。検査は複製せず repo 側の test をそのまま呼ぶ。
;;
;; **org の Actions は 2026-08-03 以降起動していない**ので、repo 側の
;; api-gateway.yml に同じ step があっても今日は発火しない。ここが実際に走る唯一の
;; 場所である。

(let [gw-dir (path/join root "kotobase-api-gateway-cljs")
      suites [["object_grant_test.cljc" "kotobase.object-grant-test"]
              ;; The shell as well as the decision. The route is where the
              ;; capability is bound to the URL and where a refusal must reach
              ;; neither the presigner nor the recorder — properties the pure
              ;; layer cannot have, because it performs nothing.
              ["object_grant_route_test.cljs" "kotobase.object-grant-route-test"]]
      missing (remove #(fs/existsSync (path/join gw-dir "test" "kotobase" (first %))) suites)]
  (if (seq missing)
    (fail! "object-grant" (str/join ", " (map first missing))
           "が無い — gate が対象を見失っている(レイアウト変更?)")
    (let [nss (str/join " " (map #(str "'" (second %)) suites))
          {:keys [rc out]}
          (run "npx" ["nbb" "--classpath" "src:test" "-e"
                      (str "(require '[clojure.test :as t] " nss ")"
                           " (t/run-tests " nss ")")]
               {:cwd gw-dir})
          n (some-> (re-find #"Ran (\d+) tests?" (str out)) second js/parseInt)
          summary (re-find #"(\d+) failures?, (\d+) errors?" (str out))]
      (println "E object-grant:" (str "Ran " n " tests, rc=" rc))
      (cond
        (nil? n) (do (println (tail out 10))
                     (fail! "object-grant" "no `Ran N tests` summary — refusing to"
                            "report a pass"))
        (zero? n) (fail! "object-grant" "zero tests ran")
        ;; nbb の run-tests は失敗しても exit code を立てない。rc だけ見ていると
        ;; 常に緑になるので、サマリの数字を読む。
        (nil? summary) (fail! "object-grant" "no failures/errors summary to read")
        (not= ["0" "0"] [(nth summary 1) (nth summary 2)])
        (do (println (tail out 15))
            (fail! "object-grant" (nth summary 1) "failures," (nth summary 2) "errors"))
        (not (zero? rc)) (do (println (tail out 15))
                             (fail! "object-grant" "nbb exited" rc))))))

;; ---------------------------------------------------------------------------

(if (seq @failures)
  (die! 1 (count @failures) "of 5 hermetic checks failed:" (str/join ", " @failures))
  (println "OK — tenant-isolation + metadata +" (count syntax-targets)
           "syntax checks + python sdk conformance + object-grant decisions"))
