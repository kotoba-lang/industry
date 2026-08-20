#!/usr/bin/env nbb
;; itonami cli — thin wrapper over gftd.cli (.cljc). ADR-2607021600.
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[babashka.fs :as fs])
(require '[gftd.cli :as cli])
(cli/-main-for :itonami *command-line-args* (scripts.nbb-compat/getenv "GFTD_ROOT"))
