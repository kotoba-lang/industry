# aiueos AArch64 UEFI boot spike

Staging area for work whose real home is **`kotoba-lang/aiueos`** at
`os/aiueos/aarch64/`. It lives here because this session could not attach the
`kotoba-lang` tier (`add_repo`: cross-tier adds unsupported), and stranding it
outside version control would have lost the evidence. **Move it to `aiueos` and
delete this directory** when a session with `kotoba-lang` access picks the work
up — that migration is the first task in ADR-2608080600's track (a).

Decision record: `90-docs/adr/2608080600-*.edn`
Measured evidence: `90-docs/evidence/260808-aiueos-aarch64-uefi-boot-v1.edn`

## What it is

**`boot.c`** — an AArch64 UEFI loader mirroring the shape of the x86_64
`os/aiueos/uefi/main.c`. Walks boot services, locates GOP, reads
`\EFI\AIUEOS\KERNEL.ELF` off the ESP, admits only bounded `ET_EXEC` AArch64
`PT_LOAD` segments into a reserved 2 MiB window, captures the UEFI memory map,
calls `ExitBootServices`, and hands a boot-info struct to the kernel.

**`kernel.c` / `kentry.S` / `kernel.ld`** — the freestanding AArch64 kernel.
Validates the boot-info handoff, builds its own stage-1 EL1 translation tables
(4 KiB granule, `T0SZ=25`), replaces the firmware's mapping via `TTBR0_EL1`, and
then **proves W^X is enforced** by violating it in both directions.

**`bootinfo.h`** — the handoff ABI, field-for-field identical to the x86_64
`struct aiueos_boot_info`. The ABI is one of the genuinely arch-neutral pieces
of the port; only the mechanism that fills it differs.

**`smoke-qemu-aarch64-uefi.cljs`** — the evidence gate. nbb, not shell, per the
2026-07-14 owner rule.

This is the AArch64 counterpart of the x86_64 Phase-1/2 evidence: *"the kernel
replaces the firmware CR3 with its own four-level identity map, enables
write-protect and NX, and maps text RX, rodata R+NX, and writable state RW+NX.
The smoke test writes to text and attempts to execute a byte in rodata; both
must raise vector 14."* The mechanism differs completely — `TTBR0`/`TCR`/`MAIR`/
`SCTLR` instead of `CR3`/`CR0`/`EFER`, `ESR_EL1` instead of an error code, no
port I/O — while the decision the evidence encodes is identical.

## Running it

```bash
nbb smoke-qemu-aarch64-uefi.cljs                   # boot evidence gate
nbb smoke-qemu-aarch64-uefi.cljs --display-matrix   # GOP probe across devices
nbb smoke-qemu-aarch64-uefi.cljs --expect-fail      # both negative controls
```

Needs `clang`, `lld-link`, `ld.lld`, `qemu-system-aarch64`, `mkfs.vfat`,
`mcopy`, `mmd`, and AAVMF firmware at `/usr/share/AAVMF/`. On Debian/Ubuntu:
`qemu-system-arm qemu-efi-aarch64 ipxe-qemu seabios dosfstools mtools`.

## The gate judges substance, not markers

It parses `SCTLR_EL1` to confirm bit 0 is actually set, and requires each W^X
probe to report the exact ESR exception class (`0x25` data abort / `0x21`
instruction abort at the current EL) with a level-3 permission fault.

That matters because of the second negative control. `--expect-fail` builds a
kernel with `-DAIUEOS_BREAK_WX`, which maps every kernel page writable *and*
executable. It boots all the way through and still prints `AIUEOS_MMU_OK` and
`AIUEOS_AARCH64_KERNEL_OK` — a marker-presence gate would pass it. This gate
refuses it on the two W^X checks alone. The first control (an ESP with no
loader) covers the trivial case.

Both controls were watched being rejected before this was recorded as landed.

## Three findings worth reading before continuing the port

1. **`virtio-vga` does not exist on aarch64.** It is x86-only because it bundles
   VGA compatibility — and it is exactly the device the x86_64 smoke gate uses.
2. **`virtio-gpu-pci` gives Blt-only GOP with no linear aperture**
   (`base=0x0`, `size=0`, `format=3`). The x86_64 desktop-surface bootstrap maps
   a linear aperture, so it does not port to the idiomatic aarch64 device. Only
   `ramfb` exposed a real aperture in the probe.
3. **UEFI is AAPCS64 here, not `ms_abi`.** `EFIAPI` is empty on aarch64; that
   attribute difference touches every protocol function pointer in the loader.

Two smaller ones that cost time: an AArch64 vector slot is exactly 0x80 bytes
(32 instructions), so a register-save sequence inlined into each slot overflows
it — the slots hold a branch and the save lives once. And the page tables are
built under the firmware's mapping but walked under ours, so they are cleaned by
VA (`dc cvac`) before the switch.

## Deliberately not done

No compositor, no input path, no virtio-gpu 2D resource path, no scheduler, no
userspace. `ramfb` is probed but not driven. Nothing measured on macOS or on
real Apple Silicon — the entire HVF track is unmeasured. See
`:not-demonstrated` in the evidence EDN.
