#!/usr/bin/env nbb
;; Issue a signing key to ONE enumerated actor, and refuse the rest.
;;
;; `etzhayyim.com/actor/<handle>/did.json` resolves for any handle -- 104 named
;; actors are enumerated and 42,659 more are `society-scale keyless mirror-
;; actors`, each marked `keyless observational mirror -- NOT the entity itself
;; (no impersonation, G1)`. A document with no verification method cannot sign,
;; and that is the entire distance between a mirror and an impersonator.
;;
;; So this refuses any handle outside the enumerated set, loudly, before it
;; generates anything. `scripts/verify-actor-did-keys.cljs` catches the same
;; mistake afterwards; this one is so the mistake is not made.
;;
;; THE SECRET NEVER TOUCHES THE REPOSITORY. It is written to
;; ~/.itonami/actor-keys/<handle>.ed25519 with mode 0600 and printed nowhere, and
;; the command to move it into kagi is printed instead. A private key committed
;; once is a private key forever, whatever the next commit says.
;;
;;   nbb --classpath <did-src>:<ed-src> scripts/issue-actor-did-key.cljs <handle> [--root <root>] [--write]
;;
;; Without --write it prints what it would do and changes nothing.
;;
;; exit 0 done (or dry run), 1 refused, 2 could not be checked.

(ns issue-actor-did-key
  (:require [clojure.string :as str]
            [did.core :as did]
            [ed25519.sign :as ed]
            ["node:crypto" :as crypto]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private argv (vec *command-line-args*))
(def ^:private flags (set (filter #(str/starts-with? % "--") argv)))
(def ^:private positional (vec (remove #(str/starts-with? % "--") argv)))
(def ^:private handle (first positional))
(def ^:private root (or (second positional) "."))
(def ^:private write? (contains? flags "--write"))

(def ^:private seed-bytes 32)

(def ^:private did-web-dir "orgs/etzhayyim/root/50-infra/etzhayyim-did-web")

(defn- refuse! [code why]
  (println (str "REFUSED: " why))
  (set! (.-exitCode js/process) code))

(defn- enumerated
  "The named/service actors: the union of the two files INFRA_ACTORS spreads.
  nil when it could not be read -- which is reported, never treated as empty."
  [dir]
  (let [hs (mapcat (fn [rel]
                     (let [f (path/join dir rel)]
                       (when (fs/existsSync f)
                         (map second
                              (re-seq #"(?m)^\s*[\"']?([a-z0-9][a-z0-9-]*)[\"']?\s*:\s*\{"
                                      (fs/readFileSync f "utf8"))))))
                   ["src/registry/infra-actors.ts" "src/registry/tier-b-actors.gen.ts"])]
    (when (seq hs) (set hs))))

(defn- document
  "The actor's document with a key, in the shape the one keyed actor already
  uses -- `tomoshibi` carries Ed25519VerificationKey2020 + publicKeyMultibase,
  names the method in authentication and assertionMethod, and keeps the did:key
  form in alsoKnownAs. Following it rather than inventing a second shape is the
  point; two shapes is two verifiers."
  [doc did-id vm-id multibase did-key]
  (-> doc
      (assoc "verificationMethod"
             [{"id" vm-id
               "type" "Ed25519VerificationKey2020"
               "controller" did-id
               "publicKeyMultibase" multibase}])
      (assoc "authentication" [vm-id])
      (assoc "assertionMethod" [vm-id])
      (assoc "alsoKnownAs" (vec (distinct (conj (vec (get doc "alsoKnownAs")) did-key))))))

(defn -main []
  (let [dir (path/join root did-web-dir)
        doc-path (path/join dir "public/actor" (str handle) "did.json")]
    (cond
      (str/blank? (str handle))
      (refuse! 1 "no handle given")

      (not (fs/existsSync dir))
      (refuse! 2 (str dir " is not checked out; the enumerated set is unreadable"))

      :else
      (let [known (enumerated dir)]
        (cond
          (nil? known)
          (refuse! 2 (str "the enumerated actor set could not be read; refusing to "
                          "decide whether " handle " may hold a key"))

          (not (contains? known handle))
          (refuse! 1 (str handle " is not in the enumerated actor set (" (count known)
                          " actors). Beyond that set the handles are keyless "
                          "observational mirrors, and a mirror that can sign is an "
                          "impersonator."))

          (not (fs/existsSync doc-path))
          (refuse! 1 (str "no document at " doc-path))

          :else
          (let [doc (js->clj (js/JSON.parse (fs/readFileSync doc-path "utf8")) :keywordize-keys false)
                did-id (get doc "id")]
            ;; Every refusal is decided BEFORE any key material exists. Generating
            ;; a seed and then deciding it may not be used leaves a secret that
            ;; was never meant to be, and the first version did exactly that --
            ;; it threw on the seed before it ever reached the rotation check.
            (cond
              (seq (get doc "verificationMethod"))
              (refuse! 1 (str handle " already carries a verification method; "
                              "rotating a key is a different operation with a "
                              "different question (who still trusts the old one)"))

              (not (str/starts-with? (str did-id) "did:web:"))
              (refuse! 1 (str "the document's id is " (pr-str did-id)
                              ", which is not a did:web"))

              :else
              (let [seed (js->clj (js/Array.from (crypto/randomBytes seed-bytes)))
                    pk (ed/public-key seed)
                    did-key (did/public-key->did-key pk)
                    multibase (subs did-key (count "did:key:"))
                    vm-id (str did-id "#node-key-0")
                    updated (document doc did-id vm-id multibase did-key)
                    key-dir (path/join (os/homedir) ".itonami/actor-keys")
                    key-path (path/join key-dir (str handle ".ed25519"))]
                (println (str "actor:      " handle))
                (println (str "did:        " did-id))
                (println (str "did:key:    " did-key))
                (println (str "method:     " vm-id))
                (println (str "document:   " doc-path))
                (println (str "secret:     " key-path " (mode 0600, never the repo)"))
                (if-not write?
                  (println "\nDRY RUN -- nothing written. Add --write to issue.")
                  (do
                    (fs/mkdirSync key-dir #js {:recursive true :mode 0700})
                    (fs/writeFileSync key-path (ed/hex seed) #js {:mode 0600})
                    (fs/writeFileSync doc-path (str (js/JSON.stringify (clj->js updated) nil 2) "\n"))
                    (println "\nwritten.")
                    (println (str "Move the secret into the credential store and remove the file:\n"
                                  "  kagi put actor-key-" handle " < " key-path "\n"
                                  "  rm " key-path))
                    (println (str "Then verify from outside:\n"
                                  "  curl -sS " (did/did-web-url did-id)))))))))))))

(-main)
