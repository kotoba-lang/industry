#!/usr/bin/env nbb
;; provision-resend-1password.cljs
;;
;; Copy the Resend API key from macOS Keychain (gftd.resend / API_KEY) into
;; 1Password vault gftdcojp as item "gftd.resend", then print the op:// path.
;;
;; Prerequisites:
;;   - `op` signed in via 1Password app integration (Settings → Developer →
;;     Integrate with 1Password CLI; Touch ID unlock when prompted)
;;   - Keychain entry: service=gftd.resend account=API_KEY
;;
;; Usage:
;;   nbb scripts/provision-resend-1password.cljs
;;   nbb scripts/provision-resend-1password.cljs --update   # edit existing
;;
;; Canonical read path after success:
;;   op read "op://gftdcojp/gftd.resend/credential"
;;   # field aliases also tried: password, API_KEY
;;
;; Does NOT print the secret value.

(ns provision-resend-1password
  (:require ["node:child_process" :as cp]
            ["node:process" :as process]
            [clojure.string :as str]))

(def vault "gftdcojp")
(def title "gftd.resend")
(def keychain-service "gftd.resend")
(def keychain-account "API_KEY")

(def notes
  (str/join
   "\n"
   ["Resend API key for gftd / kotobase mail (smtp.kotobase.net)."
    "Keychain mirror: service=gftd.resend account=API_KEY"
    "Worker secret: RESEND_API_KEY on net-kotobase (+ legacy mailer workers)"
    "Domains: email.gftd.ai, mail.kotobase.net (a95782a1-dcef-4e3c-bf1b-71c865423253),"
    "  etzhayyim.com, mail.itonami.cloud, gftd.ai, email.gftd.co.jp"
    "op read: op://gftdcojp/gftd.resend/credential"
    "Provisioned by scripts/provision-resend-1password.cljs"]))

(defn sh
  "Run argv; returns {:code :out :err}. Never throws on non-zero."
  [argv]
  (try
    (let [r (cp/spawnSync (first argv) (clj->js (vec (rest argv)))
                          #js {:encoding "utf8"})]
      {:code (or (.-status r) 1)
       :out  (or (.-stdout r) "")
       :err  (or (.-stderr r) "")})
    (catch :default e
      {:code 1 :out "" :err (str e)})))

(defn die! [msg code]
  (js/console.error msg)
  (process/exit (or code 1)))

(defn keychain-get []
  (let [{:keys [code out err]}
        (sh ["security" "find-generic-password"
             "-s" keychain-service "-a" keychain-account "-w"])]
    (when-not (zero? code)
      (die! (str "Keychain miss: " keychain-service "/" keychain-account
                 "\n" err) 2))
    (str/trim out)))

(defn op-whoami-ok? []
  (zero? (:code (sh ["op" "whoami"]))))

(defn item-exists? []
  (zero? (:code (sh ["op" "item" "get" title "--vault" vault]))))

(defn create-item! [secret]
  (let [args ["op" "item" "create"
              "--vault" vault
              "--category" "API Credential"
              "--title" title
              "--tags" "resend,email,kotobase,mailer,smtp"
              (str "credential=" secret)
              (str "username=" keychain-account)
              "hostname=https://api.resend.com"
              (str "notesPlain=" notes)]
        {:keys [code out err]} (sh args)]
    (when-not (zero? code)
      (die! (str "op item create failed:\n" err "\n" out) 3))
    out))

(defn edit-item! [secret]
  (let [args ["op" "item" "edit" title
              "--vault" vault
              (str "credential=" secret)
              (str "notesPlain=" notes)]
        {:keys [code out err]} (sh args)]
    (when-not (zero? code)
      (die! (str "op item edit failed:\n" err "\n" out) 4))
    out))

(defn verify-read! []
  (doseq [field ["credential" "password" "API_KEY"]]
    (let [path (str "op://" vault "/" title "/" field)
          {:keys [code out]} (sh ["op" "read" path])]
      (when (and (zero? code) (pos? (count (str/trim out))))
        (js/console.log (str "verify ok: " path " (len=" (count (str/trim out)) ")"))
        (reduced true)))))

(defn -main [& args]
  (let [update? (some #{"--update" "-u"} args)]
    (when-not (op-whoami-ok?)
      (die! (str "op is not signed in.\n"
                 "1) Open 1Password app and unlock\n"
                 "2) Settings → Developer → Integrate with 1Password CLI\n"
                 "3) Run: op signin --account my.1password.com\n"
                 "4) Approve Touch ID / app prompt, then re-run this script")
            10))
    (let [secret (keychain-get)]
      (js/console.log (str "keychain ok (len=" (count secret)
                           " prefix=" (subs secret 0 (min 6 (count secret))) "…)"))
      (cond
        (and (item-exists?) (not update?))
        (do (js/console.log (str "item already exists: op://" vault "/" title
                                 " (pass --update to refresh credential)"))
            (verify-read!))

        (item-exists?)
        (do (js/console.log "updating existing item…")
            (edit-item! secret)
            (verify-read!))

        :else
        (do (js/console.log "creating item…")
            (create-item! secret)
            (verify-read!))))
    (js/console.log (str "canonical: op://" vault "/" title "/credential"))
    (js/console.log "keychain mirror remains: gftd.resend / API_KEY")))

(apply -main *command-line-args*)
