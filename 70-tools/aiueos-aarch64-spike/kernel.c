/* aiueos AArch64 kernel spike (ADR-2608080600 track a2).
 *
 * Validates the boot-info handoff, replaces the firmware's page tables with
 * its own TTBR0_EL1 four-level-capable map, and proves W^X is actually
 * enforced by deliberately violating it in both directions.
 *
 * This is the AArch64 counterpart of the x86_64 Phase-1/2 evidence in
 * os/aiueos/README.md: "the kernel replaces the firmware CR3 with its own
 * four-level identity map, enables write-protect and NX, and maps text RX,
 * rodata R+NX, and writable state RW+NX. [...] The smoke test writes to text
 * and attempts to execute a byte in rodata; both must raise vector 14 with the
 * expected x86 page fault error-code bits before execution can continue."
 *
 * The AArch64 mechanism differs completely (TTBR0/TCR/MAIR/SCTLR instead of
 * CR3/CR0/EFER, ESR_EL1 instead of an error code, no port I/O) while the
 * decision the evidence encodes is identical.
 */
#include <stdint.h>
#include "bootinfo.h"

/* --------------------------------------------------------------------------
 * PL011 serial. AArch64 has no port I/O, so every marker goes out over MMIO.
 */
#define PL011_DR ((volatile uint32_t *)(PL011_BASE + 0x00))
#define PL011_FR ((volatile uint32_t *)(PL011_BASE + 0x18))
#define PL011_FR_TXFF (1u << 5)

static void serial_putc(char c) {
  while (*PL011_FR & PL011_FR_TXFF) { }
  *PL011_DR = (uint32_t)(unsigned char)c;
}
static void serial_puts(const char *s) {
  for (; *s; s++) { if (*s == '\n') serial_putc('\r'); serial_putc(*s); }
}
static void serial_puthex(uint64_t v) {
  serial_puts("0x");
  for (int shift = 60; shift >= 0; shift -= 4)
    serial_putc("0123456789abcdef"[(v >> shift) & 0xf]);
}
static void serial_putdec(uint64_t v) {
  char buf[21]; int i = 0;
  if (!v) { serial_putc('0'); return; }
  while (v) { buf[i++] = (char)('0' + v % 10); v /= 10; }
  while (i--) serial_putc(buf[i]);
}

/* --------------------------------------------------------------------------
 * Linker-provided section bounds. These are what let each region get distinct
 * permissions; without the 4 KiB alignment in kernel.ld the W^X split would
 * not be expressible at page granularity.
 */
extern char __text_start[], __text_end[];
extern char __rodata_start[], __rodata_end[];
extern char __end[];

/* --------------------------------------------------------------------------
 * Stage-1 EL1 translation tables. 4 KiB granule, T0SZ=25 (39-bit VA), so the
 * walk starts at level 1 with 1 GiB entries.
 */
#define PT_ENTRIES 512

static uint64_t l1_table[PT_ENTRIES] __attribute__((aligned(4096)));
static uint64_t l2_table[PT_ENTRIES] __attribute__((aligned(4096)));
static uint64_t l3_table[PT_ENTRIES] __attribute__((aligned(4096)));

#define DESC_TABLE 0x3ULL   /* table descriptor at L1/L2 */
#define DESC_BLOCK 0x1ULL   /* block descriptor at L1/L2 */
#define DESC_PAGE  0x3ULL   /* page descriptor at L3 */

#define ATTR_IDX(n) ((uint64_t)(n) << 2)
#define ATTR_AF     (1ULL << 10)
#define ATTR_SH_IS  (3ULL << 8)     /* inner shareable */
#define ATTR_AP_RW  (0ULL << 6)     /* EL1 read/write */
#define ATTR_AP_RO  (2ULL << 6)     /* EL1 read-only */
#define ATTR_PXN    (1ULL << 53)
#define ATTR_UXN    (1ULL << 54)

/* MAIR: attr0 = Device-nGnRnE (0x00), attr1 = Normal write-back (0xFF). */
#define MAIR_VALUE 0xFF00ULL
#define MAIR_DEVICE 0
#define MAIR_NORMAL 1

#define KERNEL_REGION_BASE 0x40000000ULL          /* L1[1] covers 1..2 GiB */
#define KERNEL_L2_INDEX ((AIUEOS_KERNEL_LOAD_BASE - KERNEL_REGION_BASE) / 0x200000ULL)
#define KERNEL_L3_BASE (KERNEL_REGION_BASE + KERNEL_L2_INDEX * 0x200000ULL)

static uint64_t tcr_value(void) {
  return (25ULL)             /* T0SZ = 25 -> 39-bit VA */
       | (1ULL << 8)         /* IRGN0 = write-back write-allocate */
       | (1ULL << 10)        /* ORGN0 = write-back write-allocate */
       | (3ULL << 12)        /* SH0   = inner shareable */
       | (0ULL << 14)        /* TG0   = 4 KiB granule */
       | (25ULL << 16)       /* T1SZ */
       | (1ULL << 23)        /* EPD1  = disable TTBR1 walks (we use TTBR0 only) */
       | (1ULL << 24) | (1ULL << 26) | (3ULL << 28)
       | (2ULL << 30)        /* TG1   = 4 KiB */
       | (2ULL << 32);       /* IPS   = 40-bit intermediate physical address */
}

/* The table walker reads through the caches once the MMU is back on, but the
   tables were written while the firmware's mapping was active. Clean them by
   VA so the walk cannot observe a stale line. */
static void clean_dcache_range(const void *base, uint64_t size) {
  uint64_t addr = (uint64_t)base & ~63ULL;
  uint64_t end = (uint64_t)base + size;
  for (; addr < end; addr += 64)
    __asm__ volatile("dc cvac, %0" :: "r"(addr) : "memory");
  __asm__ volatile("dsb ish" ::: "memory");
}

static void build_page_tables(void) {
  for (int i = 0; i < PT_ENTRIES; i++) { l1_table[i] = 0; l2_table[i] = 0; l3_table[i] = 0; }

  /* L1[0]: 0..1 GiB as Device memory. PL011, the GIC and the rest of the
     QEMU virt MMIO live here. Never executable. */
  l1_table[0] = DESC_BLOCK | ATTR_IDX(MAIR_DEVICE) | ATTR_AF | ATTR_AP_RW | ATTR_PXN | ATTR_UXN;

  /* L1[1]: 1..2 GiB is RAM; descend so the kernel's own 2 MiB can be split. */
  l1_table[1] = DESC_TABLE | (uint64_t)l2_table;

  /* L1[2..3]: remaining RAM as 1 GiB normal blocks, never executable. */
  for (int i = 2; i < 4; i++)
    l1_table[i] = DESC_BLOCK | ((uint64_t)i << 30) | ATTR_IDX(MAIR_NORMAL)
                | ATTR_AF | ATTR_SH_IS | ATTR_AP_RW | ATTR_PXN | ATTR_UXN;

  /* L2: 2 MiB normal blocks across 1..2 GiB, except the kernel's own block. */
  for (int i = 0; i < PT_ENTRIES; i++) {
    if ((uint64_t)i == KERNEL_L2_INDEX) continue;
    l2_table[i] = DESC_BLOCK | (KERNEL_REGION_BASE + (uint64_t)i * 0x200000ULL)
                | ATTR_IDX(MAIR_NORMAL) | ATTR_AF | ATTR_SH_IS | ATTR_AP_RW
                | ATTR_PXN | ATTR_UXN;
  }
  l2_table[KERNEL_L2_INDEX] = DESC_TABLE | (uint64_t)l3_table;

  /* L3: 4 KiB pages over the kernel image. This is the W^X split —
     text RX, rodata RO+NX, everything writable RW+NX. */
  for (int i = 0; i < PT_ENTRIES; i++) {
    uint64_t va = KERNEL_L3_BASE + (uint64_t)i * 4096ULL;
    uint64_t attrs = ATTR_IDX(MAIR_NORMAL) | ATTR_AF | ATTR_SH_IS;

#ifdef AIUEOS_BREAK_WX
    /* Negative control for the gate (never a build option for real use):
       map every kernel page writable AND privileged-executable. The probes
       then take no fault, and a gate that actually checks W^X must reject
       this build. Without this, the gate would only be proving that markers
       print — not that the permissions mean anything. */
    attrs |= ATTR_AP_RW | ATTR_UXN;
#else
    if (va >= (uint64_t)__text_start && va < (uint64_t)__text_end)
      attrs |= ATTR_AP_RO | ATTR_UXN;                 /* RX: privileged-executable */
    else if (va >= (uint64_t)__rodata_start && va < (uint64_t)__rodata_end)
      attrs |= ATTR_AP_RO | ATTR_PXN | ATTR_UXN;      /* RO + no execute */
    else
      attrs |= ATTR_AP_RW | ATTR_PXN | ATTR_UXN;      /* RW + no execute */
#endif

    l3_table[i] = DESC_PAGE | va | attrs;
  }

  clean_dcache_range(l1_table, sizeof l1_table);
  clean_dcache_range(l2_table, sizeof l2_table);
  clean_dcache_range(l3_table, sizeof l3_table);
}

static void mmu_enable(void) {
  uint64_t sctlr;

  /* Drop the firmware's mapping first. We are identity-mapped either way, so
     execution continues at the same physical address across the switch. */
  __asm__ volatile("mrs %0, sctlr_el1" : "=r"(sctlr));
  sctlr &= ~((1ULL << 0) | (1ULL << 2) | (1ULL << 12));   /* M, C, I */
  __asm__ volatile("msr sctlr_el1, %0; isb" :: "r"(sctlr) : "memory");

  __asm__ volatile("msr mair_el1, %0" :: "r"(MAIR_VALUE));
  __asm__ volatile("msr tcr_el1,  %0" :: "r"(tcr_value()));
  __asm__ volatile("msr ttbr0_el1,%0" :: "r"((uint64_t)l1_table));
  __asm__ volatile("dsb ish; tlbi vmalle1; dsb ish; isb" ::: "memory");

  __asm__ volatile("mrs %0, sctlr_el1" : "=r"(sctlr));
  sctlr |= (1ULL << 0) | (1ULL << 2) | (1ULL << 12);      /* M, C, I */
  __asm__ volatile("msr sctlr_el1, %0; isb" :: "r"(sctlr) : "memory");
}

/* --------------------------------------------------------------------------
 * Fault plumbing for the W^X probes.
 */
volatile uint64_t g_fault_count;
volatile uint64_t g_last_esr;
volatile uint64_t g_last_far;
volatile uint64_t g_recovery_pc;

/* ESR_EL1 EC values for aborts taken without a change of exception level. */
#define EC_INSTRUCTION_ABORT_SAME_EL 0x21
#define EC_DATA_ABORT_SAME_EL        0x25
#define FSC_PERMISSION_FAULT_L3      0x0F

void aiueos_exception(void) {
  uint64_t esr, far;
  __asm__ volatile("mrs %0, esr_el1" : "=r"(esr));
  __asm__ volatile("mrs %0, far_el1" : "=r"(far));

  g_last_esr = esr;
  g_last_far = far;
  g_fault_count++;

  if (g_recovery_pc) {
    __asm__ volatile("msr elr_el1, %0" :: "r"(g_recovery_pc));
    return;
  }

  /* No recovery armed: this fault was not part of a probe. Report and stop
     rather than looping on the faulting instruction. */
  serial_puts("AIUEOS_KERNEL_UNEXPECTED_FAULT esr=");
  serial_puthex(esr);
  serial_puts(" far=");
  serial_puthex(far);
  serial_puts("\n");
  for (;;) __asm__ volatile("wfi");
}

/* A valid `ret` sitting in .rodata. If PXN were NOT enforced this would
   execute and return cleanly, so the failure mode is an observable
   "no fault" line rather than a hang. */
static const uint32_t rodata_ret_instruction __attribute__((used)) = 0xd65f03c0;

static void report_fault(const char *label, uint64_t expect_ec) {
  uint64_t ec = (g_last_esr >> 26) & 0x3f;
  uint64_t fsc = g_last_esr & 0x3f;
  if (g_fault_count == 0) {
    serial_puts(label);
    serial_puts(" NO_FAULT — W^X NOT ENFORCED\n");
    return;
  }
  serial_puts(label);
  serial_puts(" ec=");
  serial_puthex(ec);
  serial_puts(fsc == FSC_PERMISSION_FAULT_L3 ? " fsc=permission-l3" : " fsc=other");
  serial_puts(ec == expect_ec ? " expected-ec" : " UNEXPECTED-EC");
  serial_puts(" far=");
  serial_puthex(g_last_far);
  serial_puts("\n");
}

void kernel_main(struct aiueos_boot_info *info) {
  serial_puts("AIUEOS_KERNEL_ENTRY\n");

  /* Boot-info admission: the handoff is rejected loudly, never assumed. */
  if (!info || info->magic != AIUEOS_BOOT_INFO_MAGIC) {
    serial_puts("AIUEOS_BOOTINFO_BAD_MAGIC\n");
    goto done;
  }
  if (info->version != AIUEOS_BOOT_INFO_VERSION) {
    serial_puts("AIUEOS_BOOTINFO_BAD_VERSION\n");
    goto done;
  }
  serial_puts("AIUEOS_BOOTINFO_OK entries=");
  serial_putdec(info->descriptor_size ? info->memory_map_size / info->descriptor_size : 0);
  serial_puts(" fb=");
  serial_puthex(info->framebuffer_base);
  serial_puts(" w=");
  serial_putdec(info->framebuffer_width);
  serial_puts(" h=");
  serial_putdec(info->framebuffer_height);
  serial_puts("\n");

  serial_puts("AIUEOS_KERNEL_IMAGE text=");
  serial_puthex((uint64_t)__text_start);
  serial_puts("..");
  serial_puthex((uint64_t)__text_end);
  serial_puts(" rodata=");
  serial_puthex((uint64_t)__rodata_start);
  serial_puts("..");
  serial_puthex((uint64_t)__rodata_end);
  serial_puts(" end=");
  serial_puthex((uint64_t)__end);
  serial_puts("\n");

  build_page_tables();
  serial_puts("AIUEOS_PAGETABLES_BUILT ttbr0=");
  serial_puthex((uint64_t)l1_table);
  serial_puts("\n");

  mmu_enable();

  {
    uint64_t sctlr, ttbr0;
    __asm__ volatile("mrs %0, sctlr_el1" : "=r"(sctlr));
    __asm__ volatile("mrs %0, ttbr0_el1" : "=r"(ttbr0));
    /* Reaching this line at all means instruction fetch, the stack and PL011
       are all being translated by tables this kernel built. */
    serial_puts("AIUEOS_MMU_OK sctlr=");
    serial_puthex(sctlr);
    serial_puts(" ttbr0=");
    serial_puthex(ttbr0);
    serial_puts("\n");
    if (!(sctlr & 1ULL)) serial_puts("AIUEOS_MMU_NOT_ENABLED\n");
  }

  /* W^X probe 1: store into .text, which is mapped read-only. */
  g_fault_count = 0;
  g_recovery_pc = (uint64_t)&&wx_write_done;
  {
    volatile uint32_t *text = (volatile uint32_t *)__text_start;
    *text = 0xdeadbeefu;
  }
wx_write_done:
  g_recovery_pc = 0;
  report_fault("AIUEOS_WX_WRITE_TEXT", EC_DATA_ABORT_SAME_EL);

  /* W^X probe 2: execute a byte in .rodata, which is mapped PXN. */
  g_fault_count = 0;
  g_recovery_pc = (uint64_t)&&wx_exec_done;
  {
    void (*fn)(void) = (void (*)(void))&rodata_ret_instruction;
    fn();
  }
wx_exec_done:
  g_recovery_pc = 0;
  report_fault("AIUEOS_WX_EXEC_RODATA", EC_INSTRUCTION_ABORT_SAME_EL);

  serial_puts("AIUEOS_AARCH64_KERNEL_OK\n");

done:
  /* PSCI SYSTEM_OFF — the AArch64 replacement for isa-debug-exit. */
  {
    register uint64_t x0 __asm__("x0") = 0x84000008ULL;
    __asm__ volatile("hvc #0" : "+r"(x0) :: "memory");
  }
  for (;;) __asm__ volatile("wfi");
}
