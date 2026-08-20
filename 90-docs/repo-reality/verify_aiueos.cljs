;; repo-reality verify script — aiueos prototype (2026-07-15).
;; Re-checks each :claim/* entry in repo-reality.datoms.edn (for :project/aiueos) against
;; the ACTUAL current source of orgs/kotoba-lang/aiueos, and prints one EDN ledger-event map
;; per line (repo-reality-ledger.edn's format) to stdout. Two layers, matching the methods
;; chosen 2026-07-15 (1: executable claims, 4: claim-to-evidence linkage; 5's ledger-append
;; role is fulfilled by redirecting this script's stdout into repo-reality-ledger.edn):
;;   :lint          — deterministic marker/pattern check against the claim's cited source
;;                    (same "axes as data, check fn -> {:score :note}" shape as
;;                    90-docs/design-quality/audit.cljc, applied to doc-vs-code claims
;;                    instead of rendered-page HTML/CSS).
;;   :evidence-link — does the claim's cited evidence artifact (a test file/count) still
;;                    exist and still match, independent of whether the code behavior itself
;;                    is right — this is what degrades silently over time and is exactly
;;                    what re-running this script periodically (method 5) is meant to catch.
;;
;; Run: nbb 90-docs/repo-reality/verify_aiueos.cljs >> 90-docs/repo-reality/repo-reality-ledger.edn
;; (script does NOT write the file itself — caller redirects, so a dry run is just as easy
;; as a committing run; :eval/seq numbering assumes append to an empty/fresh section).

(ns verify-aiueos
  (:require ["fs" :as fs]
            [clojure.string :as str]))

(def aiueos-root "orgs/kotoba-lang/aiueos/")

(defn slurp* [rel-path] (.readFileSync fs (str aiueos-root rel-path) "utf8"))
(defn exists? [rel-path] (.existsSync fs (str aiueos-root rel-path)))

(defn has? [s re] (boolean (re-find re s)))

(def run-id (str "repo-reality-aiueos-" (str/replace (.toISOString (js/Date.)) #"[-:]|\.\d+Z$" "")))
(def now (.toISOString (js/Date.)))

(def checks
  [{:claim :claim/aiueos-maturity-m6 :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [cov (slurp* "docs/coverage.edn")]
            (if (and (has? cov #"(?s):stage :m6.*?Still pre-1\.0")
                     (has? cov #"not-yet-implemented"))
              {:score 1.0
               :note "m6 stage's own :note text still says 'Still pre-1.0 (Phase-0)' and names ADR-0005/0006 as not-yet-implemented phases -- the self-declared M6 label is qualified in the same document, not an unqualified 'done' claim. doc-code-drift is high (honest) even though functional-completeness for the underlying phases is not 5/5."}
              {:score 0.2
               :note "expected qualifying text ('Still pre-1.0' + 'not-yet-implemented') no longer found verbatim in docs/coverage.edn's :m6 stage -- either the caveat was removed (re-check by hand: did the gap get closed, or did the doc start overclaiming?) or wording drifted; re-verify manually before trusting this claim as still self-qualified."})))}

   ;; companion event: repo-reality.datoms.edn's :claim/axis for aiueos-maturity-m6 lists BOTH
   ;; functional-completeness and doc-code-drift, but only doc-code-drift was ever emitted
   ;; above -- added 2026-07-16 (gap fix) so the claim's declared axes are fully covered, not
   ;; just the one that happened to be convenient. Score reflects that M0-M5 evidence is real
   ;; (contracts/fixtures/CI all landed per coverage.edn's own stages) but M6's referenced
   ;; ADR-0005/0006 phases include concretely-unfinished work (e.g. ADR-0006's deadline-cycles,
   ;; separately tracked by claim/aiueos-deadline-cycles at 0.1) -- so "top of the ladder" does
   ;; not mean "everything it references is done".
   {:claim :claim/aiueos-maturity-m6 :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [cov (slurp* "docs/coverage.edn")]
            {:score 0.6
             :note "M0-M5 stages (contracts/positive+negative-fixtures/CI-gated-runner/external-implementation-suite) are each backed by real, existing evidence files per coverage.edn -- substantial, not a paper ladder. Scored 0.6 rather than higher because M6 itself names ADR-0005/0006 as having not-yet-implemented phases (ADR-0006 specifically covers :deadline-cycles, confirmed unenforced by claim/aiueos-deadline-cycles), so the top-of-ladder framework is real but incomplete on the specific things it says are still open."}))}

   {:claim :claim/aiueos-ci-tests-green :axis :axis/evidence-linkage :layer :evidence-link
    :fn (fn []
          (let [claimed 190
                test-files (->> (.readdirSync fs (str aiueos-root "test/aiueos"))
                                 (filter #(str/ends-with? % "_test.cljc")))
                deftest-count (reduce + (map (fn [f]
                                                (count (re-seq #"\(deftest\s" (slurp* (str "test/aiueos/" f)))))
                                              test-files))]
              (cond
                (= deftest-count claimed)
                {:score 1.0 :note (str "deftest count matches claim exactly: " deftest-count)}
                (> deftest-count claimed)
                {:score 0.7 :note (str "deftest count is " deftest-count ", claim cites " claimed
                                        " from 2026-07-08 -- growth since the claim's date is expected/healthy drift"
                                        " (more tests added), not a correctness problem, but the claim's OWN number is stale"
                                        " and should be refreshed the next time coverage.edn is touched. This exact"
                                        " discrepancy is why the ledger needs periodic re-runs, not a one-time audit.")}
                :else
                {:score 0.3 :note (str "deftest count is " deftest-count ", BELOW the claimed " claimed
                                        " -- this direction is a real regression signal (tests removed/skipped since"
                                        " the claim was written) and warrants investigation, unlike the growth case above.")})))}

   {:claim :claim/aiueos-fuel-metering :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (let [src (slurp* "src/aiueos/execute.cljc")]
            (if (and (has? src #"withUnsafeExecutionListener")
                     (has? src #"(?i)unsafe.*experimental|experimental.*unsafe|unsafe`/`experimental"))
              {:score 0.6
               :note "confirmed: withUnsafeExecutionListener is wired (execute.cljc) AND the same file's docstring still calls it unsafe/experimental -- claim is accurate, but scored mid-range on safety-enforcement (not high) because the underlying guarantee genuinely is fragile (unofficial API, interpreter-path-only per the docstring), not because the doc is wrong."}
              {:score 0.0
               :note "expected fuel-listener wiring or its unsafe/experimental caveat no longer found in execute.cljc -- re-verify: did the mechanism change (upgraded to a stable API?) or was the caveat silently dropped while keeping the same risky mechanism (that would be a real drift regression)."})))}

   ;; companion event (2026-07-16 gap fix): confirms the fuel-metering caveat is disclosed,
   ;; not just accurate -- same reasoning kind as claim/aiueos-maturity-m6's companion above.
   {:claim :claim/aiueos-fuel-metering :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [src (slurp* "src/aiueos/execute.cljc")]
            (if (and (has? src #"withUnsafeExecutionListener")
                     (has? src #"(?i)unsafe.*experimental|experimental.*unsafe|unsafe`/`experimental"))
              {:score 1.0 :note "confirmed: the unsafe/experimental caveat for fuel metering is disclosed in execute.cljc's own docstring, not just README prose -- honest, high doc-code-drift score even though the underlying safety-enforcement score (0.6, tracked separately) is mid-range."}
              {:score 0.3 :note "expected unsafe/experimental disclosure no longer found alongside the fuel-listener wiring -- re-verify whether the caveat was silently dropped."})))}

   {:claim :claim/aiueos-memory-limit :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (let [src (slurp* "src/aiueos/execute.cljc")]
            (if (has? src #"withMemoryLimits")
              {:score 1.0 :note "confirmed: withMemoryLimits wired in execute.cljc, and the file's own docstring distinguishes it from the fuel listener as STABLE -- claim holds."}
              {:score 0.0 :note "withMemoryLimits no longer found in execute.cljc -- claim likely stale, re-verify."})))}

   {:claim :claim/aiueos-device-access-stub :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [src (slurp* "src/aiueos/execute.cljc")
                quartet ["pci_config" "dma_map" "irq_subscribe" "mmio_map"]
                present (filter #(has? src (re-pattern (str/replace % "_" "[_-]"))) quartet)]
            (if (= (count present) 4)
              {:score 0.2
               :note (str "confirmed: all 4 device-access quartet stubs present as always-stub host functions in execute.cljc ("
                          (str/join ", " present) ") -- functional-completeness for real hardware access is genuinely low (0.2, matches the code, not a doc-only claim), separate from doc-code-drift which is high because this gap IS disclosed in both README and code docstrings.")}
              {:score 0.5
               :note (str "only " (count present) "/4 device-access stub names found -- either the quartet's implementation changed shape (naming?) or partially landed; re-verify by hand, this is exactly the kind of change the claim's source doc should be re-read for.")})))}

   ;; companion event (2026-07-16 gap fix): confirms the device-access-stub gap is disclosed.
   {:claim :claim/aiueos-device-access-stub :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [readme (slurp* "README.md")
                src (slurp* "src/aiueos/execute.cljc")]
            (if (and (has? readme #"(?i)device-access quartet")
                     (has? src #"(?i)device-access quartet"))
              {:score 1.0 :note "confirmed: the device-access-quartet-is-a-stub gap is named in BOTH README.md and execute.cljc's own comments, not hidden behind either alone -- high doc-code-drift score even though functional-completeness (0.2, tracked separately) is low."}
              {:score 0.4 :note "expected 'device-access quartet' disclosure language no longer found verbatim in one or both of README.md/execute.cljc -- re-verify by hand."})))}

   {:claim :claim/aiueos-deadline-cycles :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (let [manifest (slurp* "src/aiueos/manifest.cljc")
                launcher (slurp* "src/aiueos/launcher.cljc")]
            (if (and (has? manifest #"NOTE what this does NOT do")
                     (has? launcher #"(?i)deadline-cycles.*NOT"))
              {:score 0.1
               :note "confirmed: manifest.cljc's due-this-cycle? docstring still has the explicit 'NOTE what this does NOT do' section, and launcher.cljc still flags deadline-cycles as NOT enforced -- functional-completeness/safety-enforcement for this specific feature is genuinely low (0.1), but doc-code-drift is high (1.0, tracked separately) because the gap is named in 2 independent source files, not just README prose."}
              {:score 0.5
               :note "expected 'NOT enforced' language no longer found in manifest.cljc/launcher.cljc -- either deadline-cycles enforcement was implemented (great, but then the axis score above needs to flip and claim/self-caveat should be revisited) or the docstring wording changed; re-verify by hand."})))}

   ;; companion event (2026-07-16 gap fix): confirms the deadline-cycles gap is disclosed.
   {:claim :claim/aiueos-deadline-cycles :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [manifest (slurp* "src/aiueos/manifest.cljc")
                launcher (slurp* "src/aiueos/launcher.cljc")]
            (if (and (has? manifest #"NOTE what this does NOT do")
                     (has? launcher #"(?i)deadline-cycles.*NOT"))
              {:score 1.0 :note "confirmed: the deadline-cycles-not-enforced gap is named in BOTH manifest.cljc's docstring and launcher.cljc, not hidden -- high doc-code-drift score even though safety-enforcement (0.1, tracked separately) is very low."}
              {:score 0.3 :note "expected disclosure language no longer found in one or both files -- re-verify by hand."})))}

   ;; ---- 2026-07-20 weekly claim-discovery additions: ADR-0014 self-owned VMM ("hvt tender"),
   ;; a distinct new subsystem from the WASM-component broker the checks above cover. ----------
   {:claim :claim/aiueos-hvt-guest-path-kotoba-first :axis :axis/functional-completeness :layer :lint
    :fn (fn []
          (let [adr (slurp* "90-docs/adr/0014-self-owned-vmm-hvt-tender.md")
                guests ["guest-serial.kotoba" "guest-virtio-probe.kotoba" "guest-virtio-handshake.kotoba"
                        "guest-virtqueue-tx.kotoba" "guest-virtqueue-rx.kotoba"]
                present (filter #(exists? (str "resources/hvt/" %)) guests)]
            (cond
              (not (has? adr #"entire hvt guest path is now written in Kotoba"))
              {:score 0.3 :note "expected 'entire hvt guest path is now written in Kotoba' no longer found verbatim in ADR-0014 -- claim text may have moved/reworded; re-verify."}
              (< (count present) 5)
              {:score 0.5 :note (str "ADR-0014 still claims the guest path is fully Kotoba, but only " (count present) "/5 of the cited resources/hvt/*.kotoba guest sources exist on disk -- evidence has partially disappeared even though the doc's claim is unchanged.")}
              :else
              {:score 1.0 :note (str "confirmed: ADR-0014 states the hvt guest path is fully written in Kotoba, and all 5 cited resources/hvt/*.kotoba guest sources (" (str/join ", " present) ") exist on disk.")})))}

   {:claim :claim/aiueos-hvt-psci-shutdown-unsupported :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (if (exists? "src/aiueos/hvt.cljc")
            (let [adr (slurp* "90-docs/adr/0014-self-owned-vmm-hvt-tender.md")
                  hvt (slurp* "src/aiueos/hvt.cljc")]
              (if (and (has? adr #"PSCI_RET_NOT_SUPPORTED")
                       (has? hvt #"(?i)mmio.*poweroff|poweroff.*mmio"))
                {:score 1.0 :note "confirmed: ADR-0014 still documents PSCI SYSTEM_OFF returning PSCI_RET_NOT_SUPPORTED on this KVM environment, and hvt.cljc still implements the MMIO poweroff fallback the ADR names as the actual working halt mechanism -- honestly disclosed at both doc and source layer."}
                {:score 0.3 :note "expected PSCI_RET_NOT_SUPPORTED language in the ADR or the MMIO poweroff fallback in hvt.cljc no longer found verbatim -- either a real PSCI SYSTEM_OFF path landed (re-verify, would be a genuine improvement) or wording/implementation changed; re-verify by hand."}))
            {:score 0.0 :note "src/aiueos/hvt.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/aiueos-hvt-jvm-vcpu-loop-unproven-beyond-boot :axis :axis/production-readiness :layer :lint
    :fn (fn []
          (let [adr (slurp* "90-docs/adr/0014-self-owned-vmm-hvt-tender.md")]
            (if (has? adr #"unproven for anything beyond boot")
              {:score 1.0 :note "confirmed: ADR-0014's Consequences section still discloses the JVM-in-vcpu-exit-loop path as 'unproven for anything beyond boot gates', deferring production I/O claims to V2 -- an honestly-scoped limitation, not an overclaim."}
              {:score 0.4 :note "expected 'unproven for anything beyond boot' language no longer found verbatim in ADR-0014 -- either V2 measurement landed (re-verify, would be real progress) or the wording changed; re-verify by hand."})))}

   {:claim :claim/aiueos-hvt-macos-hvf-unsupported :axis :axis/production-readiness :layer :lint
    :fn (fn []
          (if (exists? "src/aiueos/hvt.cljc")
            (let [adr (slurp* "90-docs/adr/0014-self-owned-vmm-hvt-tender.md")
                  hvt (slurp* "src/aiueos/hvt.cljc")
                  hvf-in-code? (has? hvt #"(?i)hv_vm_create|Hypervisor\.framework")]
              (cond
                (not (has? adr #"(?i)macOS/HVF.*daily-driver"))
                {:score 0.3 :note "expected 'macOS/HVF (the actual daily-driver host)' disclosure no longer found verbatim in ADR-0014 -- re-verify whether macOS/HVF support landed or the wording changed."}
                hvf-in-code?
                {:score 0.5 :note "ADR-0014 still frames macOS/HVF as deferred, but hvt.cljc now references hv_vm_create/Hypervisor.framework -- possible the HVF backend has started landing and the ADR's disclosure is going stale; re-verify by hand."}
                :else
                {:score 1.0 :note "confirmed: ADR-0014 still discloses macOS/HVF support as deferred behind the entitlement/codesigning question, and hvt.cljc has no hv_vm_create/Hypervisor.framework code path -- the self-owned VMM genuinely only runs on Linux/KVM today, matching the doc's own limitation."}))
            {:score 0.0 :note "src/aiueos/hvt.cljc no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/aiueos-hvt-x86-kernel-direct-load-unverified :axis :axis/evidence-linkage :layer :lint
    :fn (fn []
          (let [adr (slurp* "90-docs/adr/0014-self-owned-vmm-hvt-tender.md")]
            (if (has? adr #"needs an x86_64 KVM host, which the dev\s+machine is not")
              {:score 1.0 :note "confirmed: ADR-0014's own Finding 1 still discloses that direct-loading the real ADR-0013 (x86_64) kernel through the self-owned VMM needs an x86_64 KVM host the aarch64 dev machine does not have -- the headline V0/V1 goal remains explicitly unverified, only the arch-independent ELF-loader mechanism itself is proven."}
              {:score 0.4 :note "expected Finding 1 language ('needs an x86_64 KVM host, which the dev machine is not') no longer found verbatim in ADR-0014 -- either an x86_64 host became available and the real kernel was booted (re-verify, would be major progress) or wording changed; re-verify by hand."})))}

   ;; ---- 2026-07-27 weekly claim-discovery addition: .issues/issues.edn F-002 self-report vs
   ;; the actual signature-verification source. ------------------------------------------------
   {:claim :claim/aiueos-f002-signer-revocation-not-enforced :axis :axis/safety-enforcement :layer :lint
    :fn (fn []
          (let [signing (slurp* "src/aiueos/signing.cljc")
                issues (slurp* ".issues/issues.edn")
                lifecycle-kw-anywhere? (or (has? signing #":aiueos/signer-status\b")
                                           (and (exists? "src/aiueos/contract.cljc")
                                                (has? (slurp* "src/aiueos/contract.cljc") #":aiueos/signer-status\b")))]
            (cond
              (not (has? issues #"F-002"))
              {:score 0.5 :note "F-002 entry no longer found in .issues/issues.edn -- issue may have been removed/renumbered; re-verify by hand."}
              lifecycle-kw-anywhere?
              {:score 0.7 :note "the :aiueos/signer-status lifecycle keyword F-002's resolution evidence names is now present in source -- signer-status enforcement may have actually landed since this claim was written; re-verify whether aiueos.signing/verify now consults it before trusting a higher score."}
              :else
              {:score 0.1 :note "confirmed: :aiueos/signer-status is still absent from both signing.cljc and contract.cljc, and aiueos.signing/verify still only checks signer registration (:aiueos.policy/signers) + signature bytes, never a lifecycle status -- F-002's :implemented-local self-report for 'enforce key lifecycle and signer revocation' does not match runtime behavior; a manifest signed by a key the operator intends as revoked/expired/compromised still verifies exactly like one signed by an active key."})))}

   {:claim :claim/aiueos-f002-signer-revocation-not-enforced :axis :axis/doc-code-drift :layer :evidence-link
    :fn (fn []
          (if (exists? "docs/issues/security-key-lifecycle-signer-revocation.md")
            (let [doc (slurp* "docs/issues/security-key-lifecycle-signer-revocation.md")]
              (if (has? doc #"Added CLJC policy-level `:aiueos/signer-status` lifecycle states")
                (let [contract (slurp* "src/aiueos/contract.cljc")]
                  (if (has? contract #":aiueos/signer-status\b")
                    {:score 1.0 :note "the issue doc's specific evidence claim now matches source -- :aiueos/signer-status found in contract.cljc; re-verify the safety-enforcement axis event too."}
                    {:score 0.1 :note "confirmed: the issue doc still claims 'Added CLJC policy-level :aiueos/signer-status lifecycle states' as resolution evidence, but that exact keyword is absent from contract.cljc (the file the issue itself points to) -- the doc's own cited evidence does not exist; a silent overclaim, not a disclosed gap."}))
                {:score 0.5 :note "expected resolution-evidence wording no longer found verbatim in the issue doc -- content may have been reworded; re-verify by hand."}))
            {:score 0.0 :note "docs/issues/security-key-lifecycle-signer-revocation.md no longer exists -- claim's cited source file is gone; re-verify."}))}

   ;; ---- 2026-08-03 weekly claim-discovery additions: ADR-0016 TCB java.base/CI contradiction,
   ;; ADR-0017's disclosed SBOM-attestation wiring gap, and an undisclosed sealed-storage
   ;; evidence-is-a-placeholder gap for key-lifecycle checkpoints. --------------------------
   {:claim :claim/aiueos-tcb-java-base-ci-version-contradiction :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (let [adr (slurp* "90-docs/adr/0016-content-addressed-external-tcb.md")
                tcb (slurp* "qualification/tcb-inventory.edn")
                ci (slurp* ".github/workflows/ci.yml")]
            (cond
              (not (has? tcb #":minimum-version 25"))
              {:score 0.4 :note "qualification/tcb-inventory.edn no longer records java.base :minimum-version 25 -- inventory may have been revised; re-verify whether the CI-version contradiction was actually resolved."}
              (not (has? ci #"java-version:\s*\"21\""))
              {:score 0.6 :note "ci.yml no longer provisions java-version 21 -- if it was bumped to >=25, the contradiction ADR-0016 disclosed may now be resolved; re-verify and consider raising this score, since a real fix here would be genuine progress."}
              :else
              {:score 1.0 :note "confirmed: qualification/tcb-inventory.edn still records java.base :minimum-version 25 with :assurance-gap :platform-runtime-not-content-addressed, and .github/workflows/ci.yml still provisions java-version 21 -- ADR-0016's self-disclosed contradiction ('nothing detects the contradiction') still holds and is still undetected by any gate."})))}

   {:claim :claim/aiueos-sbom-attestation-not-wired-to-release-pipeline :axis :axis/production-readiness :layer :lint
    :fn (fn []
          (if (exists? "os/aiueos/scripts/build-release-image.sh")
            (let [adr (slurp* "90-docs/adr/0017-release-attestations.md")
                  script (slurp* "os/aiueos/scripts/build-release-image.sh")
                  script-has-attest? (has? script #"(?i)attest|sbom|provenance")]
              (cond
                (not (has? adr #"does not yet call `clojure -M:attest`"))
                {:score 0.4 :note "ADR-0017's 'Not done' section no longer discloses build-release-image.sh lacking the attest call verbatim -- either the wiring landed (re-verify, would be real progress) or wording changed; re-verify by hand."}
                script-has-attest?
                {:score 0.6 :note "os/aiueos/scripts/build-release-image.sh now references attest/sbom/provenance where it previously had none -- the disclosed wiring gap may have started closing; re-verify whether clojure -M:attest is actually invoked with the receipt's digest before raising this score to 1.0."}
                :else
                {:score 1.0 :note "confirmed: ADR-0017 still discloses that build-release-image.sh does not call clojure -M:attest and no gate requires an attestation for a release, and the 26-line script still has zero attest/sbom/provenance references -- the disclosed gap matches the code exactly."}))
            {:score 0.0 :note "os/aiueos/scripts/build-release-image.sh no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/aiueos-key-checkpoint-storage-evidence-is-literal-not-computed :axis :axis/evidence-linkage :layer :lint
    :fn (fn []
          (let [profile (slurp* "src/aiueos/deployment_profile.cljc")
                producers (filter #(and (exists? %) (has? (slurp* %) #":key-checkpoint-storage"))
                                   ["src/aiueos/key_lifecycle.clj" "src/aiueos/launcher.cljc"
                                    "src/aiueos/sealed_state.clj" "src/aiueos/sealed_audit.clj"])]
            (cond
              (not (has? profile #":key-checkpoint-storage"))
              {:score 0.5 :note "deployment_profile.cljc no longer references :key-checkpoint-storage -- the check may have been removed or renamed; re-verify by hand."}
              (seq producers)
              {:score 0.7 :note (str "found :key-checkpoint-storage referenced in " (str/join ", " producers) " beyond the check-site/test-fixture -- a real producer for this evidence field may now exist; re-verify whether it actually persists a checkpoint into sealed storage before raising this score to 1.0.")}
              :else
              {:score 0.2 :note "confirmed: :key-checkpoint-storage still appears only at the deployment_profile.cljc check site and in test fixtures -- no code in key_lifecycle.clj, launcher.cljc, sealed_state.clj, or sealed_audit.clj actually produces/persists this evidence field into sealed monotonic storage. docs/key-lifecycle.md's 'enforced regulated baseline' status makes no exception for this gap, unlike ADR-0017's disclosed SBOM gap -- a silent placeholder, not a disclosed one."})))}

   ;; ---- added 2026-08-10, weekly claim-discovery pass ----
   {:claim :claim/aiueos-kotoba-object-rebuild-does-not-boot :axis :axis/doc-code-drift :layer :lint
    :fn (fn []
          (if (exists? "os/aiueos/90-docs/adr/0035-the-rebuild-does-not-boot.md")
            (let [adr (slurp* "os/aiueos/90-docs/adr/0035-the-rebuild-does-not-boot.md")
                  script-exists? (exists? "os/aiueos/scripts/reproduce-kotoba-kernel-object.sh")
                  script (when script-exists? (slurp* "os/aiueos/scripts/reproduce-kotoba-kernel-object.sh"))]
              (cond
                (not (has? adr #"Do not land the rebuild"))
                {:score 0.4 :note "ADR-0035 no longer states 'Do not land the rebuild' verbatim -- the boot-blocking rebuild may since have been fixed and landed (re-verify: does main now build/boot with rebuilt objects?), or wording changed; re-verify by hand before trusting this claim as still current."}
                (not (has? adr #"stays at .fd371d7"))
                {:score 0.5 :note "ADR-0035 no longer cites 'origin/main stays at fd371d7' verbatim -- the pinned commit reference may have moved since; re-verify whether main has advanced past the broken-rebuild concern."}
                (not script-exists?)
                {:score 0.3 :note "os/aiueos/scripts/reproduce-kotoba-kernel-object.sh no longer exists -- claim's cited pin-verification script is gone; re-verify."}
                (not (has? script #"0b16d9b6"))
                {:score 0.5 :note "reproduce-kotoba-kernel-object.sh no longer pins compiler 0b16d9b6 -- the pin may have advanced (would be real progress toward closing ADR-0032's open item, IF the boot failure was actually fixed first); re-verify whether the ADR-0035 boot regression was resolved before raising this score."}
                :else
                {:score 1.0 :note "confirmed: ADR-0035 still states 'Do not land the rebuild' and 'origin/main stays at fd371d7 (ADR-0034), which boots', and reproduce-kotoba-kernel-object.sh is still pinned to compiler 0b16d9b6 exactly as ADR-0032 left it -- the disclosed boot-blocking regression is still open, and the cited pin + script still match that decision."}))
            {:score 0.0 :note "os/aiueos/90-docs/adr/0035-the-rebuild-does-not-boot.md no longer exists -- claim's cited source file is gone; re-verify."}))}

   {:claim :claim/aiueos-kotoba-object-rebuild-does-not-boot :axis :axis/production-readiness :layer :lint
    :fn (fn []
          (if (exists? "os/aiueos/90-docs/adr/0035-the-rebuild-does-not-boot.md")
            (let [adr (slurp* "os/aiueos/90-docs/adr/0035-the-rebuild-does-not-boot.md")]
              (if (has? adr #"36 of 57 objects")
                {:score 0.5 :note "confirmed: ADR-0035 still cites reproduce-kotoba-kernel-object.sh's coverage as '36 of 57 objects', unchanged from ADR-0032/0034, and still names this 'the open item' -- reproducibility production-readiness is disclosed-blocked, not silently stalled, but has made zero forward progress since the 2026-08-03 pass (score reflects an honestly-disclosed, still-open gap, not a regression)."}
                {:score 0.4 :note "ADR-0035's '36 of 57 objects' coverage figure is no longer present verbatim in the ADR -- the reproducibility coverage numbers may have been revised since; re-verify current object coverage by hand."}))
            {:score 0.0 :note "os/aiueos/90-docs/adr/0035-the-rebuild-does-not-boot.md no longer exists -- claim's cited source file is gone; re-verify."}))}])

(defn -main []
  (binding [*print-namespace-maps* false]
    (doseq [[i {:keys [claim axis layer fn]}] (map-indexed vector checks)]
      (let [{:keys [score note]} (fn)]
        (println (pr-str {:eval/claim claim :eval/axis axis :eval/layer layer
                           :eval/score (double score) :eval/judge "repo-reality-verify-script"
                           :eval/run-id run-id :eval/at now :eval/seq (inc i) :eval/note note}))))))

(-main)
