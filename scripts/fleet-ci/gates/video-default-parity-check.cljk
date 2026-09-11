#!/usr/bin/env nbb
;; video-default-parity — the :video default in the spec must be the default the
;; server actually uses, and it must not be a model marked broken.
;;
;; These live in two repos. cloud-murakumo's resources/murakumo.edn declares
;; `:default`; the thing that actually answers POST /v1/generation is
;; kotoba-lang/murakumo's scripts/hunyuan3d-generation-api, whose
;; VIDEO_DEFAULT_MODEL is a separate literal. Nothing compared them.
;;
;; That is not hypothetical. ADR-2608036800 (2026-08-03) found ltx-2.3 returning
;; all-black frames while reporting status=done, wrote "leaving a broken model as
;; the default means model-less calls silently receive an empty video", and moved
;; the default to wan2.2-ti2v-5b — in the spec only. The serving file kept saying
;; "ltx-2.3" for seven more days, so every model-less call went on being routed to
;; the broken model, under the ADR written to prevent exactly that. It was found by
;; hand on 2026-08-10 (ADR-2608100600), not by any check.
;;
;; A per-model :status is only worth having if something reads it. This gate is
;; that reader. It asserts three things:
;;
;;   1. spec :default  ==  serving VIDEO_DEFAULT_MODEL
;;   2. that model exists in the spec's :models
;;   3. its :status is not :broken, and not :pending/:unverified either — a
;;      default is what you get when you did not choose, so it has to be a model
;;      someone actually ran.
;;
;; Runs against cloud-murakumo's tree and fetches the serving file from GitHub,
;; because the point is to compare against what is deployed-from, not against
;; whatever happens to be checked out next to it.
;;
;;   nbb video-default-parity-check.cljs [--dir <repo-root>] [--ref main]

(ns video-default-parity-check
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def argv (vec (drop 2 (js->clj js/process.argv))))

(defn- arg [flag default]
  (let [i (.indexOf argv flag)]
    (if (neg? i) default (nth argv (inc i) default))))

(def repo-root (arg "--dir" "."))
(def upstream-ref (arg "--ref" "main"))
(def spec-path "resources/murakumo.edn")
(def serving-repo "kotoba-lang/murakumo")
(def serving-path "scripts/hunyuan3d-generation-api")

;; A default may only be a status someone has actually observed working.
(def acceptable-status #{:production :verified})

(defn- fail! [& msgs]
  (println (str/join "\n" (cons "video-default-parity: FAIL" msgs)))
  (js/process.exit 1))

(defn- video-gen-block
  "Find the :gen block that owns the video model catalogue. Located by content
  (it has both :models and :default, and its models include a known fleet video
  model) rather than by path, so moving it in the tree does not silently disable
  this gate."
  [spec]
  (letfn [(walk [x]
            (cond
              (map? x) (concat (when (and (map? (:models x)) (:default x)
                                          (some #{"wan2.2-ti2v-5b" "ltx-2.3" "minimax-h3"}
                                                (keys (:models x))))
                                 [x])
                               (mapcat walk (vals x)))
              (sequential? x) (mapcat walk x)
              :else nil))]
    (first (walk spec))))

(defn- serving-default
  "Extract VIDEO_DEFAULT_MODEL from the Python serving file. Comment lines are
  dropped first: the current file explains its own history in a comment block
  that mentions the previous value, and a naive regex happily matches that."
  [src]
  (let [code (->> (str/split-lines src)
                  (remove #(str/starts-with? (str/triml %) "#"))
                  (str/join "\n"))]
    (some-> (re-find #"(?m)^VIDEO_DEFAULT_MODEL\s*=\s*[\"']([^\"']+)[\"']" code)
            second)))

(defn- fetch-serving []
  (-> (js/fetch (str "https://raw.githubusercontent.com/" serving-repo "/"
                     upstream-ref "/" serving-path))
      (.then (fn [^js res]
               (if (.-ok res)
                 (.text res)
                 (js/Promise.reject
                  (js/Error. (str "GET " serving-repo "/" serving-path
                                  " @" upstream-ref " -> " (.-status res)))))))))

(defn -main []
  (let [p (path/join repo-root spec-path)]
    (when-not (fs/existsSync p)
      (fail! (str "spec not found: " p)
             "This gate is declared against cloud-murakumo; if the spec moved, move the gate with it."))
    (let [spec (edn/read-string (str (fs/readFileSync p "utf8")))
          blk  (video-gen-block spec)]
      (when-not blk
        (fail! (str "no :video :gen block with both :models and :default in " spec-path)
               "A spec this gate cannot locate is a spec it cannot check — that is a failure, not a pass."))
      (let [spec-default (:default blk)
            models (:models blk)]
        (-> (fetch-serving)
            (.then
             (fn [src]
               (let [srv (serving-default src)
                     entry (get models spec-default)
                     status (:status entry)
                     problems
                     (cond-> []
                       (nil? srv)
                       (conj (str "could not read VIDEO_DEFAULT_MODEL from " serving-repo "/" serving-path))

                       (and srv (not= srv spec-default))
                       (conj (str "spec says :default " (pr-str spec-default)
                                  " but " serving-repo " serves " (pr-str srv)
                                  "\n    The spec is not what answers the request. Change both or neither."))

                       (nil? entry)
                       (conj (str ":default " (pr-str spec-default)
                                  " is not in :models — a default nobody declared cannot carry a :status"))

                       (and entry (= status :broken))
                       (conj (str ":default " (pr-str spec-default) " has :status :broken"
                                  (when-let [n (:status-note entry)] (str "\n    " n))
                                  "\n    A model-less call would silently receive whatever a broken model returns."))

                       (and entry (not= status :broken) (not (acceptable-status status)))
                       (conj (str ":default " (pr-str spec-default) " has :status " (pr-str status)
                                  " — a default has to be a model someone actually ran"
                                  " (" (str/join "/" (map str acceptable-status)) ")")))]
                 (if (seq problems)
                   (apply fail! (map #(str "  " %) problems))
                   (do (println (str "video-default-parity: ok — spec and " serving-repo
                                     " both default to " (pr-str spec-default)
                                     ", :status " (pr-str status)))
                       (js/process.exit 0))))))
            (.catch (fn [e] (fail! (str "  " e)))))))))

(-main)
