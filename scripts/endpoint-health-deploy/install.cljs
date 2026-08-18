#!/usr/bin/env nbb
(ns install
  "Install the endpoint-health resident on an always-on host.

  Copies what the resident needs (the probe, the manifest, the resident, and
  uptime's source) to <root> on the host, substitutes the unit templates, and
  enables the timer.

  This is deliberately a copy and not a git checkout: gad holds no GitHub
  credential (measured 2026-08-18: no gh, no netrc), and giving it one would
  turn a :slot/anonymous residency into an attested one for no gain. The cost
  is that the host's copy is only as fresh as the last install -- so the
  installed tree records the commit it came from, and `--check` reports drift.

  usage:
    nbb scripts/endpoint-health-deploy/install.cljs <user@host> [--root DIR]
                                                    [--home DIR] [--check]"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(def argv (vec *command-line-args*))
(defn opt [k d] (if-let [i (first (keep-indexed #(when (= %2 k) %1) argv))]
                  (get argv (inc i) d) d))
(def host (first (remove #(str/starts-with? % "--") argv)))
(def remote-root (opt "--root" "/opt/endpoint-health"))
(def remote-home (opt "--home" "/var/lib/endpoint-health"))
(def check? (some #{"--check"} argv))
(def ws (js/process.cwd))

(defn sh [cmd] (.execSync cp cmd #js {:encoding "utf8" :stdio "pipe"}))
(defn ssh [cmd] (sh (str "ssh -o ConnectTimeout=10 -o BatchMode=yes " host " " (pr-str cmd))))

(defn -main []
  (when-not host
    (println "usage: install.cljs <user@host> [--root DIR] [--home DIR] [--check]")
    (js/process.exit 2))
  (let [commit (str/trim (sh "git rev-parse HEAD"))
        uptime-src (.join path ws "orgs" "kotoba-lang" "uptime" "src")]
    (when-not (.existsSync fs uptime-src)
      (println (str "REFUSING to install: uptime source is not checked out at " uptime-src
                    "\n  west update --fetch smart uptime"))
      (js/process.exit 2))
    (if check?
      (let [there (try (str/trim (ssh (str "cat " remote-root "/INSTALLED_COMMIT 2>/dev/null")))
                       (catch :default _ ""))]
        (println (str "workspace: " commit "\ninstalled:  " (if (seq there) there "(nothing installed)")))
        (if (= there commit)
          (println "in sync")
          (do (println "DRIFT -- the host is running a different tree than this checkout")
              (js/process.exit 1))))
      (do
        (println (str "installing " (subs commit 0 12) " to " host ":" remote-root))
        ;; Resolve the remote user HERE. `$(whoami)` inside the ssh argument is
        ;; expanded by the LOCAL shell before ssh ever sees it, which put this
        ;; laptop's username on the remote chown (measured 2026-08-18:
        ;; `chown: invalid user: junkawasaki`).
        (let [ruser (str/trim (ssh "whoami"))]
          (ssh (str "sudo mkdir -p " remote-root "/scripts " remote-root "/manifest "
                    remote-root "/uptime && sudo chown -R " ruser " " remote-root
                    " && sudo mkdir -p " remote-home " && sudo chown " ruser " " remote-home)))
        (doseq [[src dst] [["scripts/verify-endpoint-health.cljs" "scripts/"]
                           ["scripts/endpoint-health-resident.cljs" "scripts/"]
                           ["manifest/endpoint-health.edn" "manifest/"]]]
          (sh (str "scp -q " (.join path ws src) " " host ":" remote-root "/" dst)))
        (sh (str "scp -qr " uptime-src "/uptime " host ":" remote-root "/uptime/"))
        (ssh (str "echo " commit " > " remote-root "/INSTALLED_COMMIT"))
        (let [nbb (str/trim (ssh "command -v nbb"))
              user (str/trim (ssh "whoami"))]
          (doseq [u ["endpoint-health.service" "endpoint-health.timer"]]
            (let [body (-> (.readFileSync fs (.join path ws "scripts/endpoint-health-deploy" u) "utf8")
                           (str/replace "@NBB@" nbb)
                           (str/replace "@USER@" user)
                           (str/replace "@ROOT@" remote-root)
                           (str/replace "@HOME@" remote-home)
                           (str/replace "@UPTIME@" (str remote-root "/uptime")))
                  tmp (.join path "/tmp" u)]
              (.writeFileSync fs tmp body)
              (sh (str "scp -q " tmp " " host ":/tmp/" u))
              (ssh (str "sudo mv /tmp/" u " /etc/systemd/system/" u)))))
        (ssh "sudo systemctl daemon-reload && sudo systemctl enable --now endpoint-health.timer")
        (println (str/trim (ssh "systemctl list-timers endpoint-health.timer --no-pager | head -3")))
        (println "installed.")))))

(-main)
