#!/usr/bin/env nbb
;; aiueos AArch64 UEFI boot evidence gate.
;;
;; The x86_64 counterpart is `os/aiueos/scripts/smoke-qemu-uefi.sh`. This one is
;; nbb per the 2026-07-14 owner rule (new harnesses are `.cljs`, never `.sh`).
;;
;; Builds the AArch64 UEFI loader, stages it on a FAT32 ESP, boots it under real
;; AAVMF firmware, and requires every evidence marker to appear on PL011 serial.
;; Fail-closed: a missing marker is a hard failure, never a warning.
;;
;;   nbb smoke-qemu-aarch64-uefi.cljs                 # boot evidence gate
;;   nbb smoke-qemu-aarch64-uefi.cljs --display-matrix # GOP probe across devices
;;   nbb smoke-qemu-aarch64-uefi.cljs --expect-fail    # prove the gate can fail

(ns smoke-qemu-aarch64-uefi
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

;; nbb does not expose `import.meta.url`; `*file*` is the script path and
;; process.argv is [node, nbb, script, ...user args].
(def here (path/resolve (path/dirname (str *file*))))

(def aavmf-code "/usr/share/AAVMF/AAVMF_CODE.fd")
(def aavmf-vars "/usr/share/AAVMF/AAVMF_VARS.fd")

;; Every marker the loader must emit, in order. `AIUEOS_AARCH64_UEFI_OK` is last
;; on purpose: it only prints after ExitBootServices and the CurrentEL read, so
;; its presence implies the whole chain ran.
(def required-markers
  ["AIUEOS_AARCH64_UEFI_ENTRY"
   "AIUEOS_UEFI_REVISION"
   "AIUEOS_MEMMAP_OK"
   "AIUEOS_EXIT_BOOT_SERVICES_OK"
   "AIUEOS_CURRENT_EL 1"
   "AIUEOS_AARCH64_UEFI_OK"])

(defn- run! [cmd args opts]
  (let [r (cp/spawnSync cmd (clj->js args)
                        (clj->js (merge {:encoding "utf8" :stdio "pipe"} opts)))]
    {:status (.-status r)
     :stdout (or (.-stdout r) "")
     :stderr (or (.-stderr r) "")}))

(defn- must! [cmd args]
  (let [{:keys [status stdout stderr]} (run! cmd args {})]
    (when-not (zero? status)
      (println (str "error: " cmd " " (str/join " " args) " failed (" status ")"))
      (println stderr) (println stdout)
      (js/process.exit 1))
    stdout))

(defn- need-tool! [tool]
  (when-not (zero? (:status (run! "sh" ["-c" (str "command -v " tool)] {})))
    (println (str "error: required tool missing: " tool))
    (js/process.exit 1)))

(defn build-efi!
  "Compile + link the AArch64 PE32+ UEFI application.
   AArch64 UEFI uses AAPCS64, so there is no ms_abi attribute here — that is the
   single largest source-level difference from the x86_64 loader."
  [out-dir]
  (let [src (path/join here "boot.c")
        obj (path/join out-dir "boot.obj")
        efi (path/join out-dir "BOOTAA64.EFI")]
    (must! "clang" ["--target=aarch64-unknown-windows" "-ffreestanding" "-fshort-wchar"
                    "-fno-stack-protector" "-O1" "-c" "-o" obj src])
    (must! "lld-link" ["-subsystem:efi_application" "-entry:efi_main"
                       "-nodefaultlib" (str "-out:" efi) obj])
    efi))

(defn build-esp!
  "Stage the loader at the removable-media fallback path EFI/BOOT/BOOTAA64.EFI."
  [out-dir efi]
  (let [esp (path/join out-dir "esp.img")]
    (must! "dd" ["if=/dev/zero" (str "of=" esp) "bs=1M" "count=64" "status=none"])
    (must! "mkfs.vfat" ["-F" "32" "-n" "AIUEOS" esp])
    (must! "mmd" ["-i" esp "::/EFI" "::/EFI/BOOT"])
    (must! "mcopy" ["-i" esp efi "::/EFI/BOOT/BOOTAA64.EFI"])
    esp))

(defn boot!
  "Boot the ESP under AAVMF and return the PL011 serial transcript.
   `display-device` nil means no display device at all."
  [out-dir esp display-device]
  (let [vars (path/join out-dir "vars.fd")
        serial (path/join out-dir "serial.log")]
    (fs/copyFileSync aavmf-vars vars)
    (when (fs/existsSync serial) (fs/unlinkSync serial))
    (let [args (concat
                ["-machine" "virt" "-cpu" "cortex-a72" "-m" "512M" "-smp" "2"
                 "-L" "/usr/share/seabios"
                 "-drive" (str "if=pflash,format=raw,readonly=on,file=" aavmf-code)
                 "-drive" (str "if=pflash,format=raw,file=" vars)
                 "-drive" (str "if=none,id=esp,format=raw,file=" esp)
                 "-device" "virtio-blk-pci,drive=esp"]
                (when display-device ["-device" display-device])
                ["-display" "none" "-serial" (str "file:" serial)
                 "-monitor" "none" "-no-reboot"])
          {:keys [status stderr]} (run! "timeout" (cons "90" (cons "qemu-system-aarch64" args)) {})]
      {:qemu-status status
       :qemu-stderr stderr
       :serial (if (fs/existsSync serial) (str (fs/readFileSync serial "utf8")) "")})))

(defn- gop-line [serial]
  (some->> (str/split-lines serial)
           (filter #(str/includes? % "AIUEOS_GOP_"))
           first
           str/trim))

(defn display-matrix!
  "Probe which aarch64 `virt` display devices expose a GOP linear framebuffer
   aperture. The x86_64 desktop-surface bootstrap depends on that aperture, so
   this table decides whether it ports as-is."
  [out-dir esp]
  (println "\n=== AArch64 `virt` GOP display matrix (AAVMF) ===")
  (doseq [dev ["virtio-gpu-pci" "ramfb" "VGA" "bochs-display" "virtio-vga"]]
    (let [{:keys [serial qemu-status qemu-stderr]} (boot! out-dir esp dev)
          line (gop-line serial)]
      (println (str (.padEnd dev 16) " "
                    (cond
                      line line
                      (str/includes? qemu-stderr "not a valid device model")
                      "DEVICE NOT AVAILABLE ON AARCH64"
                      (not (zero? qemu-status)) (str "qemu failed: "
                                                     (first (str/split-lines qemu-stderr)))
                      :else "no GOP line")))))
  (println))

(defn -main [& args]
  (let [args (set args)
        expect-fail? (contains? args "--expect-fail")]
    (doseq [t ["clang" "lld-link" "qemu-system-aarch64" "mkfs.vfat" "mcopy" "mmd"]]
      (need-tool! t))
    (doseq [f [aavmf-code aavmf-vars]]
      (when-not (fs/existsSync f)
        (println (str "error: AAVMF firmware not found: " f)) (js/process.exit 1)))

    (let [out-dir (fs/mkdtempSync "/tmp/aiueos-aa64-")
          efi (build-efi! out-dir)
          esp (build-esp! out-dir efi)]
      (println (str "built " efi " (" (.-size (fs/statSync efi)) " bytes)"))

      (if (contains? args "--display-matrix")
        (display-matrix! out-dir esp)
        (let [;; --expect-fail boots with no ESP content mounted at all, proving the
              ;; gate actually rejects a boot that produces no evidence.
              esp (if expect-fail?
                    (let [empty-esp (path/join out-dir "empty.img")]
                      (must! "dd" ["if=/dev/zero" (str "of=" empty-esp) "bs=1M" "count=64" "status=none"])
                      (must! "mkfs.vfat" ["-F" "32" empty-esp])
                      empty-esp)
                    esp)
              {:keys [serial qemu-status]} (boot! out-dir esp "virtio-gpu-pci")
              missing (remove #(str/includes? serial %) required-markers)]
          (println (str "qemu exit=" qemu-status))
          (doseq [l (str/split-lines serial)
                  :when (str/starts-with? (str/trim l) "AIUEOS_")]
            (println (str "  " (str/trim l))))
          (if (seq missing)
            (do (println (str "\nFAIL: missing evidence markers: " (str/join ", " missing)))
                (js/process.exit (if expect-fail? 0 1)))
            (do (when expect-fail?
                  (println "\nFAIL: --expect-fail run unexpectedly produced full evidence")
                  (js/process.exit 1))
                (println "\nPASS: AIUEOS_AARCH64_UEFI boot evidence complete")
                (js/process.exit 0))))))))

(apply -main (js->clj (.slice js/process.argv 3)))
