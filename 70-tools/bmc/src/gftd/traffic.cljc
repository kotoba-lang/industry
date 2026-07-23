(ns gftd.traffic
  "Shared channel-measurement traffic classification (ADR-2607231800):
   separates real content routes from bot/vulnerability-scanner probe
   requests when summarizing a product's top-accessed paths.

   `collect.cljs`'s `zone-top-paths` already splits 2xx/3xx from 4xx/5xx via
   Cloudflare's edgeResponseStatus — that alone is enough for most products
   (e.g. cloud-itonami's canvas already shows a clean '4xx(probe) N%' split).
   It is NOT enough for network-isekai: Cloudflare Pages answers unmatched
   paths with a 2xx/3xx status (no custom 404), so the \"ok\" 2xx/3xx bucket
   itself gets polluted by scanner probe paths (evidence:
   90-docs/business/canvas-ledger.edn :network-isekai.channels ticks
   2026-07-09..22 show /mailer.php /wp.php /a1.php /1996.php /biufile.php
   /w3lls.php /inputs.php /wp-lvminl.php /wp-admin/css/colors/index.php
   /.git /config/config.php /config/nexmo.php /Dockerfile
   /package-updates/yum.cgi //abcd.php //avcqlevnbk.php as top-paths).

   `classify-path` adds a second, per-product content classification layer
   on top of the status split: real routes (`channel-allowlist`) vs known
   scanner signatures (`probe-path-re`, product-agnostic) vs neither
   (`:unclassified`, surfaced rather than silently dropped — 捏造ゼロ: an
   unclassified path may just be a real route not yet added to the
   allowlist, which is a signal to update the allowlist, not noise to
   discard).

   Pure .cljc, no side effects — matches the repo's existing gftd.*
   BMC module conventions (canvas/gate/funnel/react/score) and the
   .cljc-first runtime-priority rule. io stays in collect.cljs."
  (:require [clojure.string :as str]))

(def probe-path-re
  ;; Universal vulnerability-scanner signatures — product-agnostic because
  ;; these hit every internet-facing host regardless of what it actually
  ;; serves (evidence: see namespace docstring).
  #"(?i)(\.php$|\.(cgi|asp|aspx|jsp)$|wp-admin|wp-content|wp-includes|wp-login|wp-json|xmlrpc\.php|\.(env|git|htpasswd|htaccess)(/|$)|docker-compose\.ya?ml$|^/*Dockerfile$|/(phpmyadmin|cpanel|package-updates)/|^//)")

(def channel-allowlist
  ;; product → {:exact #{...} :prefixes [...]}. Real content routes only,
  ;; hand-maintained (not generated) — update whenever a product ships a new
  ;; page/route. Products with no entry here fall back to the pre-existing
  ;; status-only behavior in zone-top-paths (opt-in, zero risk to the other
  ;; products this file also collects for).
  {:network-isekai
   {:exact #{"/" "/robots.txt" "/favicon.ico" "/sitemap.xml"
             "/assets" "/assets.html" "/benchmarks" "/benchmarks.html"
             "/dance" "/dance.html" "/generate" "/generate.html"
             "/play" "/play.html" "/preview" "/preview.html"
             "/project" "/project.html" "/studio" "/studio.html"}
    :prefixes ["/feed/" "/assets/" "/benchmarks/" "/team/" "/js/" "/kototama/"
               "/wasm/" "/api/" "/gftd/" "/itonami/" "/studio/gftd/" "/studio/itonami/"]}
   :cloud-itonami
   {:exact #{"/" "/robots.txt" "/favicon.ico" "/sitemap.xml"}
    :prefixes ["/isco-" "/api/" "/itonami/"]}}) ; refine against itonami.cloud's actual route map before relying on this

(defn classify-path
  "channel-allowlist に登録済みの product だけ、real-route allowlist →
   product-agnostic scanner denylist の順で3値分類する:
     :channel — allowlist の既知route (top-paths に出す)
     :probe   — probe-path-re に一致する既知スキャナ signature (top-paths から除外、% だけ集計)
     :unclassified — どちらにも一致しない (捏造ゼロ: 黙って捨てず % を surface — 新route が
                     allowlist 未登録なだけかもしれないので人間へのシグナルとして残す)"
  [product path]
  (let [{:keys [exact prefixes]} (get channel-allowlist product)]
    (cond
      (or (contains? exact path) (some #(str/starts-with? path %) prefixes)) :channel
      (re-find probe-path-re path) :probe
      :else :unclassified)))
