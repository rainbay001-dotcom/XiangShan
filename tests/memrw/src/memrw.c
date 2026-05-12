/*
 * Memory read/write validation tests for CHIHNSubNode paths.
 *
 * Tests 9 categories of memory access to exercise the CHI channels
 * (REQ/RSP/DAT/SNP) through the HN subnode on each core.
 *
 * Address space:
 *   0x00000000 - 0x7FFFFFFF : MMIO (bypasses OpenLLC → OpenNCB)
 *   0x80000000+             : Main memory (routed through CHIHNSubNode → OpenLLC)
 */

#include <am.h>
#include <klib.h>
#include <stdint.h>

#define MEM_BASE   0x80000000UL
#define MEM_SIZE   (16 * 1024 * 1024)   /* 16 MiB test window */
#define CACHE_LINE 64

static volatile uint8_t  *mem8  = (volatile uint8_t  *)MEM_BASE;
static volatile uint16_t *mem16 = (volatile uint16_t *)MEM_BASE;
static volatile uint32_t *mem32 = (volatile uint32_t *)MEM_BASE;
static volatile uint64_t *mem64 = (volatile uint64_t *)MEM_BASE;

/* ------------------------------------------------------------------ */
static int pass, fail;

#define ASSERT_EQ(a, b, msg) do { \
  if ((uint64_t)(a) == (uint64_t)(b)) { pass++; } \
  else { printf("FAIL [%s]: got 0x%lx expected 0x%lx\n", (msg), (uint64_t)(a), (uint64_t)(b)); fail++; } \
} while(0)

/* 1. Byte (uint8_t) read/write */
static void test_byte_rw(void) {
  for (int i = 0; i < 64; i++) {
    mem8[i] = (uint8_t)(0xAA ^ i);
  }
  for (int i = 0; i < 64; i++) {
    ASSERT_EQ(mem8[i], (uint8_t)(0xAA ^ i), "byte_rw");
  }
}

/* 2. Half-word (uint16_t) read/write */
static void test_halfword_rw(void) {
  for (int i = 0; i < 32; i++) {
    mem16[i] = (uint16_t)(0xBEEF ^ i);
  }
  for (int i = 0; i < 32; i++) {
    ASSERT_EQ(mem16[i], (uint16_t)(0xBEEF ^ i), "halfword_rw");
  }
}

/* 3. Word (uint32_t) read/write */
static void test_word_rw(void) {
  for (int i = 0; i < 16; i++) {
    mem32[i] = 0xDEAD0000U | (uint32_t)i;
  }
  for (int i = 0; i < 16; i++) {
    ASSERT_EQ(mem32[i], 0xDEAD0000U | (uint32_t)i, "word_rw");
  }
}

/* 4. Double-word (uint64_t) read/write */
static void test_dword_rw(void) {
  for (int i = 0; i < 8; i++) {
    mem64[i] = 0xCAFEBABE00000000ULL | (uint64_t)i;
  }
  for (int i = 0; i < 8; i++) {
    ASSERT_EQ(mem64[i], 0xCAFEBABE00000000ULL | (uint64_t)i, "dword_rw");
  }
}

/* 5. Cache-line aligned read/write (64 bytes) */
static void test_cache_line_rw(void) {
  volatile uint64_t *cl = (volatile uint64_t *)(MEM_BASE + 4096);
  for (int i = 0; i < 8; i++) {
    cl[i] = 0x1234567800000000ULL | (uint64_t)i;
  }
  for (int i = 0; i < 8; i++) {
    ASSERT_EQ(cl[i], 0x1234567800000000ULL | (uint64_t)i, "cacheline_rw");
  }
}

/* 6. Stride access (exercises prefetcher and multiple MSHR entries) */
static void test_stride_rw(void) {
  const int stride = 256; /* 4 cache lines */
  for (int i = 0; i < 64; i++) {
    mem64[i * stride / 8] = (uint64_t)i * 0x100000001ULL;
  }
  for (int i = 0; i < 64; i++) {
    ASSERT_EQ(mem64[i * stride / 8], (uint64_t)i * 0x100000001ULL, "stride_rw");
  }
}

/* 7. Block copy (src → dst, different cache sets) */
static void test_block_copy(void) {
  volatile uint64_t *src = (volatile uint64_t *)(MEM_BASE + 0x10000);
  volatile uint64_t *dst = (volatile uint64_t *)(MEM_BASE + 0x20000);
  const int words = 512;

  for (int i = 0; i < words; i++) {
    src[i] = (uint64_t)i | ((uint64_t)~i << 32);
  }
  for (int i = 0; i < words; i++) {
    dst[i] = src[i];
  }
  for (int i = 0; i < words; i++) {
    ASSERT_EQ(dst[i], src[i], "block_copy");
  }
}

/* 8. Read-Modify-Write (exercises MSHR hit-under-miss and write-back) */
static void test_rmw(void) {
  volatile uint64_t *rmw = (volatile uint64_t *)(MEM_BASE + 0x30000);
  for (int i = 0; i < 64; i++) {
    rmw[i] = 0ULL;
  }
  for (int i = 0; i < 64; i++) {
    rmw[i] += (uint64_t)(i + 1);
  }
  for (int i = 0; i < 64; i++) {
    ASSERT_EQ(rmw[i], (uint64_t)(i + 1), "rmw");
  }
}

/* 9. Write-back / eviction stress (dirty cache-line eviction through HN node) */
static void test_writeback_evict(void) {
  /* Touch enough lines to overflow L2 (typically 512 KB / 8 ways) */
  const size_t evict_size = 2 * 1024 * 1024; /* 2 MiB > typical L2 */
  volatile uint8_t *evict = (volatile uint8_t *)(MEM_BASE + 0x100000);
  for (size_t i = 0; i < evict_size; i += CACHE_LINE) {
    evict[i] = (uint8_t)(i & 0xFF);
  }
  for (size_t i = 0; i < evict_size; i += CACHE_LINE) {
    ASSERT_EQ(evict[i], (uint8_t)(i & 0xFF), "writeback_evict");
  }
}

/* ------------------------------------------------------------------ */
int main(void) {
  printf("CHIHNSubNode Memory R/W Tests\n");
  printf("MEM_BASE=0x%lx  MEM_SIZE=%lu KiB\n", (unsigned long)MEM_BASE, (unsigned long)MEM_SIZE / 1024);
  pass = fail = 0;

  test_byte_rw();
  test_halfword_rw();
  test_word_rw();
  test_dword_rw();
  test_cache_line_rw();
  test_stride_rw();
  test_block_copy();
  test_rmw();
  test_writeback_evict();

  printf("\nResults: %d passed, %d failed\n", pass, fail);
  if (fail == 0) {
    printf("ALL TESTS PASSED\n");
  } else {
    printf("SOME TESTS FAILED\n");
  }
  return fail;
}
