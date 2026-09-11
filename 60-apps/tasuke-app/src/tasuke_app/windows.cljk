(ns tasuke-app.windows
  "Free public windows: code → who to contact.

  The CODES are the guest's — `windows` returns them, and which ones for which
  kind is its decision. What each code means in the outside world is data about
  the world, kept here and dated, so a wrong phone number is wrong as DATA rather
  than as a rule.

  `.cljc` because two consumers need it and they run on different hosts: the view
  in the browser, and `page-gen`'s `<noscript>` block on the JVM. MEASURED
  2026-08-29: while this lived in `views.cljs`, the noscript fallback printed raw
  codes — `platform-abuse-desk → police-cyber-9110 → jpcert` — to exactly the
  reader who has no JavaScript to look them up with."
  (:require [clojure.string :as str]))

(def directory
  "Checked 2026-08-29. Each entry: [name contact url]."
  {"police-cyber-9110"         ["警察 サイバー犯罪相談窓口" "#9110（各都道府県警）"
                                "https://www.npa.go.jp/bureau/cyber/soudan.html"]
   "platform-abuse-desk"       ["各プラットフォームの abuse / ヘルプ窓口"
                                "サービス内の「不正利用の報告」" ""]
   "jpcert"                    ["JPCERT/CC" "インシデント報告" "https://www.jpcert.or.jp/form/"]
   "consumer-188"              ["消費者ホットライン" "188（いやや）" "https://www.kokusen.go.jp/"]
   "nccc"                      ["国民生活センター" "" "https://www.kokusen.go.jp/"]
   "antiphishing-council"      ["フィッシング対策協議会" "報告受付"
                                "https://www.antiphishing.jp/registration.html"]
   "safeline"                  ["セーフライン" "違法・有害情報の通報" "https://www.safe-line.jp/"]
   "bank-direct"               ["取引金融機関" "各行の緊急連絡先（24時間）" ""]
   "no-and-bank-fund-recovery" ["振り込め詐欺救済法に基づく手続き" "振込先の金融機関へ" ""]})

(defn describe
  "`\"police-cyber-9110\"` → `\"警察 サイバー犯罪相談窓口（#9110（各都道府県警））\"`.
  An unknown code returns the code itself rather than nothing: a victim reading a
  code we failed to document can still search for it."
  [code]
  (if-let [[name contact _] (get directory code)]
    (if (str/blank? contact) name (str name "（" contact "）"))
    code))
