# aiueos AArch64 UEFI boot spike

Staging area for work whose real home is **`kotoba-lang/aiueos`** at
`os/aiueos/aarch64/`. It lives here because this session could not attach the
`kotoba-lang` tier (`add_repo`: cross-tier adds unsupported), and stranding it
outside version control would have lost the evidence. **Move it to `aiueos` and
delete this directory** when a session with `kotoba-lang` access picks the work
up — that migration is the first task in ADR-2608080300's track (a).

Decision record: `90-docs/adr/2608080300-*.edn`
Measured evidence: `90-docs/evidence/260808-aiueos-aarch64-uefi-boot-v1.edn`

## What it is

`boot.c` is an AArch64 UEFI loader mirroring the shape of the x86_64
`os/aiueos/uefi/main.c`, built to measure what the aarch64 port actually costs.
It walks boot services, locates GOP, takes the UEFI memory map, calls
`ExitBootServices`, reads `CurrentEL`, and terminates through PSCI `SYSTEM_OFF`.
Every step emits a marker on PL011 serial.

`smoke-qemu-aarch64-uefi.cljs` is the evidence gate. It is nbb, not shell, per
the 2026-07-14 owner rule that new harnesses are `.cljs`.

## Running it

```bash
nbb smoke-qemu-aarch64-uefi.cljs                  # boot evidence gate
nbb smoke-qemu-aarch64-uefi.cljs --display-matrix  # GOP probe across devices
nbb smoke-qemu-aarch64-uefi.cljs --expect-fail     # prove the gate can fail
```

Needs `clang`, `lld-link`, `qemu-system-aarch64`, `mkfs.vfat`, `mcopy`, `mmd`,
and AAVMF firmware at `/usr/share/AAVMF/`. On Debian/Ubuntu:
`qemu-system-arm qemu-efi-aarch64 ipxe-qemu seabios dosfstools mtools`.

The gate is fail-closed and has been watched failing: `--expect-fail` boots an
ESP with no loader on it and requires every marker to be absent.

## Three findings worth reading before continuing the port

1. **`virtio-vga` does not exist on aarch64.** It is x86-only because it bundles
   VGA compatibility — and it is exactly the device the x86_64 smoke gate uses.
2. **`virtio-gpu-pci` gives Blt-only GOP with no linear aperture**
   (`base=0x0`, `size=0`, `format=3`). The x86_64 desktop-surface bootstrap maps
   a linear aperture, so it does not port to the idiomatic aarch64 device. Only
   `ramfb` exposed a real aperture in the probe.
3. **UEFI is AAPCS64 here, not `ms_abi`.** `EFIAPI` is empty on aarch64; that
   attribute difference touches every protocol function pointer in the loader.

## Deliberately not done

No kernel handoff, no compositor, no input path, nothing measured on macOS or on
real Apple Silicon. See `:not-demonstrated` in the evidence EDN.
