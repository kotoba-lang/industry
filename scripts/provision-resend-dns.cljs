#!/usr/bin/env nbb
;; provision-resend-dns.cljs
;;
;; Reconcile Cloudflare DNS against what Resend actually requires, plus the
;; DMARC records the fleet is missing.
;;
;; The desired DKIM / SPF values are FETCHED LIVE from the Resend API — never
;; transcribed into this file — so a rotated DKIM key cannot silently drift.
;;
;; Usage:
;;   nbb scripts/provision-resend-dns.cljs              # dry-run (default)
;;   nbb scripts/provision-resend-dns.cljs --execute    # apply
;;   nbb scripts/provision-resend-dns.cljs --only shinshi.club[,...]
;;
;; Credentials (targeted reads, never printed):
;;   Resend      keychain service=gftd.resend account=API_KEY
;;   Cloudflare  keychain service=gftd.cf     account=API_TOKEN
;;
;; Exit codes: 0 = in sync / applied, 1 = error, 2 = drift found (--check only)

(ns provision-resend-dns
  (:require ["node:child_process" :as cp]
            ["node:process" :as process]
            [clojure.string :as str]))

;; ---------------------------------------------------------------- config

;; Zones we touch, with the Resend domain that lives under each. Zone ids are
;; resolved from the API, not hardcoded.
(def zones
  [{:zone "shinshi.club"   :resend-domain "mail.shinshi.club"}
   {:zone "aozora.app"     :resend-domain "mail.aozora.app"}
   {:zone "x402.nexus"     :resend-domain "mail.x402.nexus"}
   {:zone "kotobase.net"   :resend-domain "mail.kotobase.net"}
   {:zone "itonami.cloud"  :resend-domain "mail.itonami.cloud"}
   {:zone "murakumo.cloud" :resend-domain "mail.murakumo.cloud"}])

;; Inbound mail for these zones is Cloudflare Email Routing, not Resend, so the
;; Resend "Receiving MX" row is deliberately NOT created. Resend will keep
;; reporting `partially_failed` for them; that is expected, not a defect.
;; Turn Resend inbound off per-domain to make the dashboard agree.
(def cloudflare-inbound
  {"mail.kotobase.net"  {:zone "kotobase.net"
                         ;; Mirrors the apex kotobase.net MX set.
                         :mx [["route1.mx.cloudflare.net" 25]
                              ["route3.mx.cloudflare.net" 40]
                              ["route2.mx.cloudflare.net" 55]]}
   "mail.itonami.cloud" {:zone "itonami.cloud" :mx :already-present}})

;; DMARC aggregate reports land in one mailbox: dmarc@kotobase.net, which the
;; kotobase.net Email Routing catch-all already delivers to worker:kotobase-mail.
(def dmarc-rua "dmarc@kotobase.net")
(def dmarc-inbox-zone "kotobase.net")
(def dmarc-value (str "v=DMARC1; p=none; rua=mailto:" dmarc-rua))

;; ---------------------------------------------------------------- helpers

(defn sh [argv]
  (let [r (cp/spawnSync (first argv) (clj->js (vec (rest argv)))
                        #js {:encoding "utf8"})]
    {:code (or (.-status r) 1)
     :out  (or (.-stdout r) "")
     :err  (or (.-stderr r) "")}))

(defn keychain [service account]
  (let [{:keys [code out]} (sh ["security" "find-generic-password"
                                "-s" service "-a" account "-w"])]
    (when-not (zero? code)
      (js/console.error (str "keychain miss: " service "/" account))
      (process/exit 1))
    (str/trim out)))

(defn die! [msg] (js/console.error msg) (process/exit 1))

(defn ->clj [x] (js->clj x :keywordize-keys true))

(defn api-get [url headers]
  (-> (js/fetch url (clj->js {:headers headers}))
      (.then #(.json %))
      (.then ->clj)))

(defn cf-req [token method path body]
  (-> (js/fetch (str "https://api.cloudflare.com/client/v4" path)
                (clj->js (cond-> {:method  method
                                  :headers {"Authorization" (str "Bearer " token)
                                            "Content-Type"  "application/json"}}
                           body (assoc :body (js/JSON.stringify (clj->js body))))))
      (.then #(.json %))
      (.then ->clj)))

;; ---------------------------------------------------------------- desired set

(defn resend-desired
  "Turn one Resend domain payload into the DNS records we intend to own.
   Returns [{:type :name :content :priority}]. Skips the Receiving MX row for
   domains whose inbound is Cloudflare Email Routing."
  [{:keys [name records]}]
  (let [cf-inbound? (contains? cloudflare-inbound name)
        zone        (->> zones (filter #(= (:resend-domain %) name)) first :zone)]
    (keep
     (fn [{rec :record rtype :type rname :name value :value prio :priority}]
       (let [fqdn (if (str/blank? rname) zone (str rname "." zone))]
         (cond
           ;; Resend's inbound MX — only when this domain uses Resend inbound.
           (= rec "Receiving")
           (when-not cf-inbound?
             {:type "MX" :name fqdn :content value :priority (or prio 10)
              :why "Resend inbound"})

           (= rtype "MX")
           {:type "MX" :name fqdn :content value :priority (or prio 10)
            :why "Resend SPF (MAIL FROM)"}

           (= rtype "TXT")
           {:type "TXT" :name fqdn :content value
            :why (if (str/includes? rec "DKIM") "Resend DKIM" "Resend SPF")}

           (= rtype "CNAME")
           {:type "CNAME" :name fqdn :content value :why "Resend"}

           :else nil)))
     records)))

(defn extra-desired
  "Records Resend does not know about: Cloudflare inbound MX, DMARC, and the
   RFC 7489 §7.1 authorization records that let an external rua work."
  []
  (concat
   ;; Cloudflare Email Routing inbound for mail.kotobase.net
   (for [[domain {:keys [zone mx]}] cloudflare-inbound
         :when (vector? mx)
         [host prio] mx]
     {:type "MX" :name domain :content host :priority prio
      :why (str "Cloudflare Email Routing inbound (" zone ")")})
   ;; Apex DMARC for every zone we touch
   (for [{:keys [zone]} zones]
     {:type "TXT" :name (str "_dmarc." zone) :content dmarc-value
      :why "DMARC p=none + rua"})
   ;; External-rua authorization, hosted on the reporting mailbox's zone
   (for [{:keys [zone]} zones
         :when (not= zone dmarc-inbox-zone)]
     {:type "TXT"
      :name (str zone "._report._dmarc." dmarc-inbox-zone)
      :content "v=DMARC1"
      :why (str "authorize " zone " reports -> " dmarc-rua)})))

;; ---------------------------------------------------------------- reconcile

(defn norm [s] (-> (str s) str/trim (str/replace #"^\"|\"$" "")))

(defn matches? [existing {:keys [type name content priority]}]
  (and (= (:type existing) type)
       (= (str/lower-case (:name existing)) (str/lower-case name))
       (= (norm (:content existing)) (norm content))
       (or (nil? priority) (= (:priority existing) priority))))

(defn same-slot?
  "Another record already occupying this name+type+content-ish slot, used to
   report conflicts rather than blindly adding a duplicate."
  [existing {:keys [type name]}]
  (and (= (:type existing) type)
       (= (str/lower-case (:name existing)) (str/lower-case name))))

(defn plan-for-zone [zone-name existing desired]
  (reduce
   (fn [acc d]
     (cond
       (some #(matches? % d) existing) (update acc :ok conj d)
       ;; TXT SPF/DMARC at an occupied name = replace, not duplicate
       (and (= (:type d) "TXT")
            (some #(same-slot? % d) existing)
            (or (str/starts-with? (norm (:content d)) "v=spf1")
                (str/starts-with? (norm (:content d)) "v=DMARC1")))
       (update acc :conflict conj
               (assoc d :existing (->> existing (filter #(same-slot? % d)) first)))
       :else (update acc :create conj d)))
   {:ok [] :create [] :conflict [] :zone zone-name}
   desired))

;; ---------------------------------------------------------------- main

(defn -main []
  (let [argv     (vec (drop 2 (js->clj (.-argv process))))
        execute? (some #{"--execute"} argv)
        only     (when-let [i (.indexOf argv "--only")]
                   (when (>= i 0) (set (str/split (get argv (inc i) "") #","))))
        rs-key   (keychain "gftd.resend" "API_KEY")
        cf-key   (keychain "gftd.cf" "API_TOKEN")
        targets  (if (seq only) (filter #(only (:zone %)) zones) zones)]
    (-> (js/Promise.resolve)
        (.then
         (fn []
           (js/Promise.all
            (clj->js
             [(api-get "https://api.resend.com/domains"
                       {"Authorization" (str "Bearer " rs-key)})
              (js/Promise.all
               (clj->js (map #(cf-req cf-key "GET"
                                      (str "/zones?name=" (:zone %)) nil)
                             targets)))]))))
        (.then
         (fn [[rs-domains cf-zones]]
           (let [rs-domains (->clj rs-domains)
                 cf-zones   (->clj cf-zones)
                 dom-ids    (into {} (map (juxt :name :id) (:data rs-domains)))
                 zone-ids   (into {} (map (fn [t z]
                                            [(:zone t)
                                             (get-in z [:result 0 :id])])
                                          targets cf-zones))]
             (when (some nil? (vals zone-ids))
               (die! (str "unresolved Cloudflare zone(s): "
                          (pr-str (keep (fn [[k v]] (when-not v k)) zone-ids)))))
             ;; Fetch each Resend domain detail + each zone's records
             (-> (js/Promise.all
                  (clj->js
                   [(js/Promise.all
                     (clj->js (map #(api-get (str "https://api.resend.com/domains/"
                                                  (dom-ids (:resend-domain %)))
                                             {"Authorization" (str "Bearer " rs-key)})
                                   targets)))
                    (js/Promise.all
                     (clj->js (map #(cf-req cf-key "GET"
                                            (str "/zones/" (zone-ids (:zone %))
                                                 "/dns_records?per_page=500")
                                            nil)
                                   targets)))]))
                 (.then (fn [[details recs]]
                          [targets zone-ids (->clj details) (->clj recs)]))))))
        (.then
         (fn [[targets zone-ids details recs]]
           (let [extras   (group-by #(->> targets
                                          (filter (fn [{:keys [zone]}]
                                                    (str/ends-with? (:name %) zone)))
                                          (sort-by (comp count :zone))
                                          last :zone)
                                    (extra-desired))
                 plans (mapv
                        (fn [t detail zrecs]
                          (let [zone     (:zone t)
                                existing (:result zrecs)
                                desired  (concat (resend-desired detail)
                                                 (get extras zone []))]
                            (plan-for-zone zone existing desired)))
                        targets details recs)
                 n-create (reduce + (map (comp count :create) plans))
                 n-conf   (reduce + (map (comp count :conflict) plans))]
             (doseq [{:keys [zone ok create conflict]} plans]
               (println (str "\n===== " zone
                             "  ok=" (count ok)
                             " create=" (count create)
                             " conflict=" (count conflict))))
             (doseq [{:keys [zone create conflict]} plans]
               (doseq [d create]
                 (println (str "  + " zone " :: " (:type d) " " (:name d)
                               (when (:priority d) (str " prio=" (:priority d)))
                               "  [" (:why d) "]"))
                 (println (str "        " (subs (str (:content d)) 0
                                                (min 100 (count (str (:content d))))))))
               (doseq [d conflict]
                 (println (str "  ! " zone " :: " (:type d) " " (:name d)
                               " EXISTS with different content [" (:why d) "]"))
                 (println (str "        have: " (norm (get-in d [:existing :content]))))
                 (println (str "        want: " (norm (:content d))))))
             (println (str "\nTOTAL create=" n-create " conflict=" n-conf))
             (if-not execute?
               (do (println "\n(dry-run — pass --execute to apply)")
                   (process/exit (if (or (pos? n-create) (pos? n-conf)) 2 0)))
               ;; apply: creates only. Conflicts are reported, never overwritten
               ;; blind — an existing SPF/DMARC string is someone's decision.
               (-> (js/Promise.all
                    (clj->js
                     (for [{:keys [zone create]} plans, d create]
                       (-> (cf-req cf-key "POST"
                                   (str "/zones/" (zone-ids zone) "/dns_records")
                                   (cond-> {:type (:type d) :name (:name d)
                                            :content (:content d) :ttl 1}
                                     (:priority d) (assoc :priority (:priority d))))
                           (.then (fn [r]
                                    (println (str (if (:success r) "  OK   " "  FAIL ")
                                                  zone " " (:type d) " " (:name d)
                                                  (when-not (:success r)
                                                    (str "  " (pr-str (:errors r))))))
                                    (:success r)))))))
                   (.then (fn [results]
                            (let [results (js->clj results)
                                  bad (count (remove true? results))]
                              (println (str "\napplied=" (- (count results) bad)
                                            " failed=" bad
                                            " conflicts-left=" n-conf))
                              (process/exit (if (pos? bad) 1 0))))))))))
        (.catch (fn [e] (die! (str "error: " e)))))))

(-main)
