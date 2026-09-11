#!/usr/bin/env nbb
;; itonami cli — thin wrapper over gftd.cli (.cljc). ADR-2607021600.
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[babashka.fs :as fs])
(require '[gftd.cli :as cli])
(cli/-main-for :itonami *command-line-args* (or (scripts.nbb-compat/getenv "COM_JUNKAWASAKI_ROOT")
                            ;; legacy; `gftd` is retired (manifest/gftd-retirement.edn).
                            ;; Successor-first with a fallback, so this keeps working
                            ;; whichever name the caller still sets.
                            (scripts.nbb-compat/getenv "GFTD_ROOT")))
