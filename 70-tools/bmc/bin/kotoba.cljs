#!/usr/bin/env nbb
;; kotoba cli — thin wrapper over gftd.cli (.cljc). ADR-2607021600.
(require '[scripts.nbb-compat :refer [slurp spit file-seq format]]
         '[babashka.fs :as fs])
(require '[gftd.cli :as cli])
(cli/-main-for :kotoba *command-line-args* (scripts.nbb-compat/getenv "GFTD_ROOT"))
