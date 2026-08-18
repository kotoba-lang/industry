#!/usr/bin/env nbb
;; datalad-b2-init.cljs — 大容量データ用 DataLad データセットを作成し、
;; Backblaze B2 (S3 互換 API) を git-annex special remote として設定する。
;;
;; 方針 (CLAUDE.md 参照): モデル重み / wasm / 動画 / 画像データセット等の大きな
;; バイナリは git 履歴に直接コミットしない。DataLad データセット配下に置き、実体は
;; B2 へ push、git にはポインタ (annex キー) だけ残す。これで superproject の
;; clone/pull が軽くなる。
;;
;; 必要ツール: datalad, git-annex（導入済み）
;; 認証は環境変数で渡す（リポには保存しない）:
;;   B2_KEY_ID    Backblaze アプリケーションキー ID
;;   B2_APP_KEY   Backblaze アプリケーションキー
;;   B2_BUCKET    バケット名
;;   B2_ENDPOINT  S3 互換エンドポイント（例: s3.us-west-004.backblazeb2.com）
;;
;; 使い方:
;;   B2_KEY_ID=... B2_APP_KEY=... B2_BUCKET=my-bucket \
;;   B2_ENDPOINT=s3.us-west-004.backblazeb2.com \
;;     scripts/datalad-b2-init.cljs <dataset-dir> [remote-name]
;;
;; ## B2 以外の S3 互換エンドポイントにも使う（2026-08-11）
;;
;; git-annex 側は `type=S3` の endpoint/bucket/鍵しか見ておらず、B2 固有の要素は
;; 1 つも無い。**s3.kotobase.net（自前の object plane、ADR-2608100100）を remote に
;; するのに別スクリプトは要らない** —— ADR-2608100100 が custody を取った 3 dataset の
;; `kotobase` remote も、ここで作るのと同じ `type=S3 host=… signature=v4` である。
;;
;; ただし `B2_ENDPOINT=s3.kotobase.net` と書くのは、次に読む者に嘘を教える。
;; そこで **ANNEX_* を第一の名前とし、B2_* は後方互換の別名**として残す:
;;
;;   ANNEX_KEY_ID / ANNEX_APP_KEY / ANNEX_BUCKET / ANNEX_ENDPOINT / ANNEX_FILEPREFIX
;;
;;   ANNEX_ENDPOINT=s3.kotobase.net ANNEX_BUCKET=cloud-itonami-kouhou \
;;   ANNEX_REQUESTSTYLE=path \
;;   ANNEX_KEY_ID=$(security find-generic-password -s cf:kotobase-protocols-worker \
;;                    -a S3_ACCESS_KEY_ID -w) \
;;   ANNEX_APP_KEY=$(security find-generic-password -s cf:kotobase-protocols-worker \
;;                     -a S3_SECRET_ACCESS_KEY -w) \
;;     scripts/datalad-b2-init.cljs orgs/cloud-itonami/kouhou kotobase
;;
;; **bucket は dataset ごとに分ける**（fileprefix で共有しない）。s3.kotobase.net は
;; bucket 単位に chain を 1 本持ち、その head を CAS するので、bucket を共有した
;; dataset 同士は書き込みで直列化して衝突する。ADR-2608100100 が per-bucket 化で
;; PUT を 5.0s → 0.89s にしたのはこれ。逆に B2 直は素の PUT なので共有してよい。
;;
;;   作成後の運用:
;;     cd <dataset-dir>
;;     cp big.safetensors .            ; データを置く
;;     datalad save -m "add weights"   ; annex 化してコミット（実体は手元のみ）
;;     datalad push --to b2            ; 実体を B2 へアップロード
;;     datalad drop big.safetensors    ; 手元の実体を削除（B2 から get で復元可）
;;     datalad get  big.safetensors    ; B2 から実体を取得

(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(defn die [& msg]
  (binding [*out* *err*] (apply println msg))
  (scripts.nbb-compat/exit 1))

(defn env
  "ANNEX_<k> を第一、B2_<k> を後方互換の別名として読む。"
  [k]
  (let [v (scripts.nbb-compat/getenv (str "ANNEX_" k))]
    (if (str/blank? v) (scripts.nbb-compat/getenv (str "B2_" k)) v)))

(defn env-or-die [k hint]
  (let [v (env k)]
    (when (str/blank? v)
      (die (str "ANNEX_" k " (or B2_" k ") is required"
                (when hint (str " (" hint ")")))))
    v))

(defn need-tool [t]
  (when-not (fs/which t)
    (die (str t " が必要です（未インストール）"))))

;; 引数
(let [[dataset-dir remote-name] *command-line-args*]
  (when (str/blank? dataset-dir)
    (die "usage: datalad-b2-init.cljs <dataset-dir> [remote-name]"))
  (let [remote-name (or remote-name "b2")
        key-id   (env-or-die "KEY_ID"   nil)
        app-key  (env-or-die "APP_KEY"  nil)
        bucket   (env-or-die "BUCKET"   nil)
        endpoint (env-or-die "ENDPOINT" "例: s3.us-west-004.backblazeb2.com / s3.kotobase.net")
        ;; 任意。既定はバケット直下だが、共有バケットでは必ず指定する（上記参照）。
        fileprefix (env "FILEPREFIX")
        ;; 任意。s3.kotobase.net では "path" が必須（下記 initremote のコメント）。
        requeststyle (env "REQUESTSTYLE")]

    (need-tool "datalad")
    (need-tool "git-annex")

    ;; 1) データセット作成（既存ならスキップ）。text2git: テキストは通常 git、バイナリは annex。
    ;;
    ;; **既にファイルのある repo を dataset にする経路を持つ**（2026-08-03）。
    ;; 実運用の dataset は大半がこの形で、product-corpus / ghosthacker-shiropico /
    ;; cloud-itonami-gtm-data はいずれも「先にコードがある repo を後から dataset 化」した。
    ;; datalad は非空ディレクトリに --force 無しでは create しないので、既存 repo を
    ;; 渡すとここで必ず落ちていた。
    ;;
    ;; その形では **text2git を入れない**。text2git の既定
    ;; (annex.largefiles=バイナリなら annex) は中身 sniffing であって、実体が
    ;; テキスト EDN のコーパスだと「git 履歴から外す」という目的を満たさない。
    ;; 呼び出し側が .gitattributes でパスを明示的に振り分ける前提にする。
    (when-not (fs/exists? (fs/path dataset-dir ".datalad"))
      ;; 「非空か」ではなく「既に git repo か」で判定する。dataset 化したい既存物は
      ;; 例外なく repo であり、判定が 1 つの述語で済んで誤検出しない。
      (let [existing? (fs/exists? (fs/path dataset-dir ".git"))
            cmd (if existing?
                  ["datalad" "create" "--force" dataset-dir]
                  ["datalad" "create" "-c" "text2git" dataset-dir])]
        (when existing?
          (println (str "既存ファイルのある " dataset-dir " を dataset 化します"
                        " (--force / text2git 無し。振り分けは .gitattributes 側)")))
        (-> (p/process cmd {:inherit true})
            deref :exit (#(when (pos? %) (die "datalad create に失敗しました"))))))

    ;; 2) B2 を S3 互換 special remote として登録（既存ならスキップ）。
    ;;    git-annex S3 は AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY を読む → B2 のキーを割り当てる。
    (let [annex-env (assoc (scripts.nbb-compat/getenv-all)
                           "AWS_ACCESS_KEY_ID"     key-id
                           "AWS_SECRET_ACCESS_KEY" app-key)
          enabled?  (-> (p/process ["git" "annex" "enableremote" remote-name]
                                   {:dir dataset-dir :env annex-env
                                    :out :string :err :string})
                        deref :exit zero?)]
      (if enabled?
        (println (str "既存の special remote '" remote-name "' を有効化しました"))
        (let [{:keys [exit]}
              (-> (p/process (cond-> ["git" "annex" "initremote" remote-name
                                      "type=S3" "protocol=https"
                                      (str "host=" endpoint) "port=443"
                                      (str "bucket=" bucket)
                                      "signature=v4" "chunk=50MiB" "encryption=none"]
                               ;; 1 バケットを複数 dataset で共有するので、実運用の
                               ;; dataset は全て fileprefix を持つ（実測: product-corpus は
                               ;; fileprefix=product-corpus/）。これが無いと全 dataset の
                               ;; キーがバケット直下に混ざり、どの dataset の実体かを
                               ;; バケット側から判別できなくなる。
                               ;; ANNEX_REQUESTSTYLE=path。既定の virtual-host style
                               ;; （`<bucket>.<host>`）は **s3.kotobase.net では DNS が
                               ;; 引けず落ちる** —— apex の 1 ホストしか存在しないため。
                               ;; 実測 2026-08-11: 既定のまま initremote すると
                               ;; `cloud-itonami-kouhou.s3.kotobase.net` を引きに行き
                               ;; ConnectionFailure。ADR-2608100100 が custody を取った
                               ;; 3 dataset も全て requeststyle=path で登録されている。
                               ;; B2 は両方式に応答するので既定のままでよい。
                               requeststyle (conj (str "requeststyle=" requeststyle))
                               fileprefix   (conj (str "fileprefix=" fileprefix)))
                             {:dir dataset-dir :env annex-env :inherit true})
                  deref)]
          (when (pos? exit)
            (die "git annex initremote に失敗しました")))))

    (println
     (str "\n✅ DataLad + B2 設定完了: " dataset-dir " (remote: " remote-name ")\n\n"
          "次の運用例:\n"
          "  cd " dataset-dir "\n"
          "  datalad save -m \"add data\"      ; 変更を annex 化してコミット\n"
          "  datalad push --to " remote-name "  ; 実体を B2 へアップロード\n"
          "  datalad drop <path>             ; 手元の実体を解放（B2 から復元可）\n"
          "  datalad get  <path>             ; B2 から実体を取得\n\n"
          "このデータセットを superproject の submodule にする場合:\n"
          "  datalad clone は使わず、git submodule add で空のポインタとして登録し、\n"
          "  実体は datalad get で各マシンが取得する運用にする。"))))
