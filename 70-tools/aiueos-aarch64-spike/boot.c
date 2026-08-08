/* aiueos AArch64 UEFI loader spike.
 *
 * Mirrors the shape of os/aiueos/uefi/main.c (x86_64) to measure exactly what
 * the aarch64 port costs. The single most load-bearing difference is the
 * calling convention: on x86_64 UEFI is ms_abi, on AArch64 it is the platform
 * AAPCS64 default, so EFIAPI is empty here. Everything below is the same
 * table-walking shape as the x86_64 loader.
 */
#include <stdint.h>

#define EFIAPI /* AArch64 UEFI uses AAPCS64 — no attribute, unlike x86_64 ms_abi */
#define EFI_SUCCESS 0
#define EFI_BUFFER_TOO_SMALL ((uint64_t)0x8000000000000005ULL)

typedef uint64_t efi_status;
typedef void *efi_handle;
typedef uint16_t char16;

struct efi_guid { uint32_t a; uint16_t b, c; uint8_t d[8]; };
struct efi_table_header { uint64_t signature; uint32_t revision, header_size, crc32, reserved; };

struct efi_simple_text_output;
typedef efi_status(EFIAPI *efi_output_string)(struct efi_simple_text_output *, const char16 *);
struct efi_simple_text_output { void *reset; efi_output_string output_string; void *rest[8]; };

typedef efi_status(EFIAPI *efi_get_memory_map)(uint64_t *, void *, uint64_t *, uint64_t *, uint32_t *);
typedef efi_status(EFIAPI *efi_allocate_pool)(uint32_t, uint64_t, void **);
typedef efi_status(EFIAPI *efi_locate_protocol)(const struct efi_guid *, void *, void **);
typedef efi_status(EFIAPI *efi_exit_boot_services)(efi_handle, uint64_t);

struct efi_boot_services {
  struct efi_table_header header;
  void *raise_tpl, *restore_tpl;
  void *allocate_pages, *free_pages;
  efi_get_memory_map get_memory_map;
  efi_allocate_pool allocate_pool;
  void *free_pool;
  void *create_event, *set_timer, *wait_for_event, *signal_event, *close_event, *check_event;
  void *install_protocol_interface, *reinstall_protocol_interface, *uninstall_protocol_interface;
  void *handle_protocol;
  void *reserved, *register_protocol_notify;
  void *locate_handle, *locate_device_path;
  void *install_configuration_table, *load_image, *start_image, *exit, *unload_image;
  efi_exit_boot_services exit_boot_services;
  void *get_next_monotonic_count, *stall, *set_watchdog_timer;
  void *connect_controller, *disconnect_controller;
  void *open_protocol, *close_protocol, *open_protocol_information;
  void *protocols_per_handle, *locate_handle_buffer;
  efi_locate_protocol locate_protocol;
};

struct efi_system_table {
  struct efi_table_header header;
  char16 *firmware_vendor; uint32_t firmware_revision;
  efi_handle console_in_handle; void *con_in;
  efi_handle console_out_handle; struct efi_simple_text_output *con_out;
  efi_handle standard_error_handle; void *std_err;
  void *runtime_services;
  struct efi_boot_services *boot_services;
};

/* EFI_GRAPHICS_OUTPUT_PROTOCOL — the same GOP the x86_64 loader hands to the
   kernel as the desktop-surface aperture (os/aiueos/README.md). */
static const struct efi_guid gop_guid =
  {0x9042a9de, 0x23dc, 0x4a38, {0x96, 0xfb, 0x7a, 0xde, 0xd0, 0x80, 0x51, 0x6a}};

struct efi_pixel_bitmask { uint32_t red, green, blue, reserved; };
struct efi_gop_mode_info {
  uint32_t version, horizontal_resolution, vertical_resolution;
  uint32_t pixel_format; struct efi_pixel_bitmask pixel_information;
  uint32_t pixels_per_scan_line;
};
struct efi_gop_mode {
  uint32_t max_mode, mode; struct efi_gop_mode_info *info;
  uint64_t size_of_info; uint64_t frame_buffer_base; uint64_t frame_buffer_size;
};
struct efi_gop { void *query_mode, *set_mode, *blt; struct efi_gop_mode *mode; };

/* QEMU `virt` PL011 UART. The x86_64 path uses COM1 port I/O (0x3f8); AArch64
   has no port I/O at all, so evidence goes out over MMIO. */
#define PL011_BASE 0x09000000ULL
#define PL011_DR   ((volatile uint32_t *)(PL011_BASE + 0x00))
#define PL011_FR   ((volatile uint32_t *)(PL011_BASE + 0x18))
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

efi_status EFIAPI efi_main(efi_handle image, struct efi_system_table *systab) {
  struct efi_boot_services *bs = systab->boot_services;

  serial_puts("AIUEOS_AARCH64_UEFI_ENTRY\n");

  /* Evidence 1: firmware revision, proving we are running under real UEFI
     boot services rather than a bare -kernel load. */
  serial_puts("AIUEOS_UEFI_REVISION ");
  serial_puthex(systab->header.revision);
  serial_puts("\n");

  /* Evidence 2: GOP aperture. This is the aarch64 counterpart of the
     x86_64 desktop-surface bootstrap — same protocol, same fields. */
  struct efi_gop *gop = 0;
  if (bs->locate_protocol(&gop_guid, 0, (void **)&gop) == EFI_SUCCESS && gop && gop->mode) {
    struct efi_gop_mode_info *info = gop->mode->info;
    serial_puts("AIUEOS_GOP_OK base=");
    serial_puthex(gop->mode->frame_buffer_base);
    serial_puts(" size=");
    serial_putdec(gop->mode->frame_buffer_size);
    serial_puts(" w=");
    serial_putdec(info->horizontal_resolution);
    serial_puts(" h=");
    serial_putdec(info->vertical_resolution);
    serial_puts(" stride=");
    serial_putdec(info->pixels_per_scan_line);
    serial_puts(" format=");
    serial_putdec(info->pixel_format);
    serial_puts("\n");
  } else {
    serial_puts("AIUEOS_GOP_ABSENT\n");
  }

  /* Evidence 3: the UEFI memory map, then ExitBootServices with the map key
     the firmware just handed back — the same handshake as the x86_64 loader. */
  uint64_t map_size = 0, map_key = 0, descriptor_size = 0;
  uint32_t descriptor_version = 0;
  void *map = 0;
  efi_status st = bs->get_memory_map(&map_size, 0, &map_key, &descriptor_size, &descriptor_version);
  if (st != EFI_BUFFER_TOO_SMALL) { serial_puts("AIUEOS_MEMMAP_PROBE_FAIL\n"); goto hang; }

  map_size += 8 * descriptor_size;           /* headroom: allocate_pool perturbs the map */
  if (bs->allocate_pool(4 /* EfiLoaderData */, map_size, &map) != EFI_SUCCESS) {
    serial_puts("AIUEOS_MEMMAP_ALLOC_FAIL\n"); goto hang;
  }
  if (bs->get_memory_map(&map_size, map, &map_key, &descriptor_size, &descriptor_version) != EFI_SUCCESS) {
    serial_puts("AIUEOS_MEMMAP_FAIL\n"); goto hang;
  }
  serial_puts("AIUEOS_MEMMAP_OK entries=");
  serial_putdec(map_size / descriptor_size);
  serial_puts(" descsize=");
  serial_putdec(descriptor_size);
  serial_puts("\n");

  if (bs->exit_boot_services(image, map_key) != EFI_SUCCESS) {
    serial_puts("AIUEOS_EXIT_BOOT_SERVICES_FAIL\n"); goto hang;
  }
  /* Past this point firmware services are gone; only our own MMIO remains. */
  serial_puts("AIUEOS_EXIT_BOOT_SERVICES_OK\n");

  /* Evidence 4: we are executing ring-0-equivalent aarch64 code with the
     firmware out of the way. CurrentEL tells us which exception level UEFI
     left us at (EL1 under AAVMF on `virt`). */
  {
    uint64_t current_el;
    __asm__ volatile("mrs %0, CurrentEL" : "=r"(current_el));
    serial_puts("AIUEOS_CURRENT_EL ");
    serial_putdec((current_el >> 2) & 3);
    serial_puts("\n");
  }

  serial_puts("AIUEOS_AARCH64_UEFI_OK\n");

  /* Deterministic termination: PSCI SYSTEM_OFF over HVC, the aarch64
     counterpart of the x86_64 isa-debug-exit device. */
  {
    register uint64_t x0 __asm__("x0") = 0x84000008ULL; /* PSCI SYSTEM_OFF */
    __asm__ volatile("hvc #0" : "+r"(x0) :: "memory");
  }

hang:
  for (;;) __asm__ volatile("wfi");
  return EFI_SUCCESS;
}
