(ns py-ts-prune-migration
  "Python/TypeScript pruning marker.

  Source-of-truth implementation is Clojure/ClojureScript-compatible .cljc.
  Legacy Python/TypeScript sources were pruned from this workspace on 2026-06-29.
  Generated/vendor/build outputs are intentionally outside this marker.")

(def pruned-at "2026-06-29")

(def pruned-source-files
  {:total 24526
   :by-extension {:py 22393
                  :ts 2117
                  :tsx 16}})

(def migration-policy
  {:runtime :cljc
   :kotoba true
   :python :pruned
   :typescript :pruned
   :svelte {:pruned true
            :preserved ["app-aozora-svelte"]}
   :python-bytecode :pruned
   :notes ["Do not reintroduce Python/TypeScript source files."
           "Do not reintroduce Svelte outside app-aozora-svelte."
           "Port required behavior into .cljc namespaces or kotoba EDN data."]})
