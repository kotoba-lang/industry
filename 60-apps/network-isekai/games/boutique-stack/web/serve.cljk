#!/usr/bin/env nbb
;; Static server for the Boutique Stack web build.
;;
;; nbb, not a shell script and not a bare .mjs — new tooling in this workspace
;; is written in nbb (CLAUDE.md, 2026-07-14).
;;
;; 127.0.0.1 rather than a file:// path on purpose: `navigator.gpu` only
;; exists in a secure context, and `about:blank`/`file://` are not one. That
;; single fact is what made an earlier session conclude this machine had no
;; WebGPU (engine-parity ledger, 28th iteration).
(ns serve
  (:require ["http" :as http]
            ["fs" :as fs]
            ["path" :as path]))

(def root (path/resolve "public"))
(def port (js/parseInt (or (second (drop-while #(not= "--port" %) *command-line-args*)) "8177")))

(def types {".html" "text/html; charset=utf-8"
            ".js" "text/javascript; charset=utf-8"
            ".css" "text/css; charset=utf-8"
            ".edn" "application/edn; charset=utf-8"
            ".json" "application/json"})

(defn- handler [req res]
  (let [url (first (.split (.-url req) "?"))
        rel (if (= "/" url) "/index.html" url)
        file (path/join root (path/normalize (str "/" (.replace rel #"^/+" ""))))]
    (if (and (.startsWith file root) (fs/existsSync file) (.isFile (fs/statSync file)))
      (do (.writeHead res 200 #js {"content-type" (get types (path/extname file) "application/octet-stream")
                                   "cache-control" "no-store"})
          (.end res (fs/readFileSync file)))
      (do (.writeHead res 404) (.end res "not found")))))

(.listen (http/createServer handler) port "127.0.0.1"
         #(println (str "boutique-stack on http://127.0.0.1:" port)))
