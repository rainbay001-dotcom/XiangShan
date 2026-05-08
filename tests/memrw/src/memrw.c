/*
 * Memory Read/Write Verification Test for XiangShan CHIHNSubNode
 *
 * Exercises the CHI hierarchy: L2 (RN) -> CHIHNSubNode (HN/RN) -> OpenLLC (HN)
 * Tests various access patterns to verify correctness of the inserted HN layer.
 *
 * Build: make ARCH=riscv64-xs (in nexus-am environment)
 */

#include <stdio.h>
#include <stdint.h>

/* Cached DRAM region, offset far enough from code/stack */
#define TEST_BASE  0x80200000UL
#define CACHELINE  8   /* 8 x uint64_t = 64 bytes */

static int g_pass = 0;
static int g_fail = 0;

#define CHECK(cond, tag) do {                               \
    if (cond) { g_pass++; }                                 \
    else { printf("FAIL [%s] line %d\n", tag, __LINE__);    \
           g_fail++; }                                      \
} while (0)

/* -------- 1. Byte granularity -------- */
static void test_byte(void)
{
    volatile uint8_t *p = (volatile uint8_t *)(TEST_BASE + 0x0000);
    printf("--- Byte R/W ---\n");
    for (int i = 0; i < 64; i++)
        p[i] = (uint8_t)(i * 3 + 7);
    for (int i = 0; i < 64; i++)
        CHECK(p[i] == (uint8_t)(i * 3 + 7), "byte");
}

/* -------- 2. Halfword granularity -------- */
static void test_halfword(void)
{
    volatile uint16_t *p = (volatile uint16_t *)(TEST_BASE + 0x1000);
    printf("--- Halfword R/W ---\n");
    for (int i = 0; i < 64; i++)
        p[i] = (uint16_t)(i * 0x1357 + 0xAB);
    for (int i = 0; i < 64; i++)
        CHECK(p[i] == (uint16_t)(i * 0x1357 + 0xAB), "half");
}

/* -------- 3. Word granularity -------- */
static void test_word(void)
{
    volatile uint32_t *p = (volatile uint32_t *)(TEST_BASE + 0x2000);
    printf("--- Word R/W ---\n");
    for (int i = 0; i < 64; i++)
        p[i] = (uint32_t)(i * 0xDEADBEEFu);
    for (int i = 0; i < 64; i++)
        CHECK(p[i] == (uint32_t)(i * 0xDEADBEEFu), "word");
}

/* -------- 4. Doubleword granularity -------- */
static void test_dword(void)
{
    volatile uint64_t *p = (volatile uint64_t *)(TEST_BASE + 0x3000);
    printf("--- Doubleword R/W ---\n");
    for (int i = 0; i < 64; i++)
        p[i] = (uint64_t)i * 0x0102030405060708ULL;
    for (int i = 0; i < 64; i++)
        CHECK(p[i] == (uint64_t)i * 0x0102030405060708ULL, "dword");
}

/* -------- 5. Cache-line aligned write then read -------- */
static void test_cacheline(void)
{
    volatile uint64_t *p = (volatile uint64_t *)(TEST_BASE + 0x4000);
    printf("--- Cache-line R/W (16 lines) ---\n");
    /* Write 16 full 64-byte cache lines */
    for (int cl = 0; cl < 16; cl++)
        for (int w = 0; w < CACHELINE; w++)
            p[cl * CACHELINE + w] = (uint64_t)((cl << 8) | w) * 0xFEDCBA9876543210ULL;
    for (int cl = 0; cl < 16; cl++)
        for (int w = 0; w < CACHELINE; w++) {
            uint64_t exp = (uint64_t)((cl << 8) | w) * 0xFEDCBA9876543210ULL;
            CHECK(p[cl * CACHELINE + w] == exp, "cacheline");
        }
}

/* -------- 6. Stride access (exercises prefetch & miss paths) -------- */
static void test_stride(void)
{
    /* stride = 8 cache lines = 512 bytes */
    const int STRIDE = CACHELINE * 8;
    volatile uint64_t *p = (volatile uint64_t *)(TEST_BASE + 0x8000);
    printf("--- Stride R/W (stride=512B, 32 iters) ---\n");
    for (int i = 0; i < 32; i++)
        p[(uint64_t)i * STRIDE] = (uint64_t)i * 0x123456789ABCDEF0ULL;
    for (int i = 0; i < 32; i++)
        CHECK(p[(uint64_t)i * STRIDE] == (uint64_t)i * 0x123456789ABCDEF0ULL, "stride");
}

/* -------- 7. Block copy (exercises sustained BW through HNSubNode) -------- */
static void test_block_copy(void)
{
    const int SZ = 0x2000; /* 8 KB */
    volatile uint8_t *src = (volatile uint8_t *)(TEST_BASE + 0x10000);
    volatile uint8_t *dst = (volatile uint8_t *)(TEST_BASE + 0x18000);
    printf("--- Block Copy 8KB ---\n");
    for (int i = 0; i < SZ; i++)
        src[i] = (uint8_t)(i & 0xFF);
    for (int i = 0; i < SZ; i++)
        dst[i] = src[i];
    int ok = 1;
    for (int i = 0; i < SZ; i++)
        if (dst[i] != (uint8_t)(i & 0xFF)) { ok = 0; break; }
    CHECK(ok, "block_copy");
}

/* -------- 8. Read-Modify-Write (RMW coherency path) -------- */
static void test_rmw(void)
{
    volatile uint32_t *p = (volatile uint32_t *)(TEST_BASE + 0x20000);
    printf("--- RMW (16 rounds x 64 words) ---\n");
    for (int i = 0; i < 64; i++) p[i] = 0;
    for (int round = 0; round < 16; round++)
        for (int i = 0; i < 64; i++)
            p[i] = p[i] + 1;
    for (int i = 0; i < 64; i++)
        CHECK(p[i] == 16, "rmw");
}

/* -------- 9. Write-back eviction: write large region then re-read -------- */
static void test_eviction(void)
{
    /* Write > L1+L2 capacity to force evictions down through HNSubNode */
    const int SZ = 0x20000; /* 128 KB — larger than typical L1 (64KB) */
    volatile uint32_t *p = (volatile uint32_t *)(TEST_BASE + 0x40000);
    printf("--- Write-back eviction 128KB ---\n");
    for (int i = 0; i < (int)(SZ / 4); i++)
        p[i] = (uint32_t)i * 0x13579BDFu;
    /* Re-read — must fetch from OpenLLC through HNSubNode */
    int ok = 1;
    for (int i = 0; i < (int)(SZ / 4); i++)
        if (p[i] != (uint32_t)i * 0x13579BDFu) { ok = 0; break; }
    CHECK(ok, "eviction");
}

/* ====================================================== */
int main(void)
{
    printf("=== XiangShan CHIHNSubNode Memory R/W Test ===\n");
    printf("Base: 0x%08lx\n\n", (unsigned long)TEST_BASE);

    test_byte();
    test_halfword();
    test_word();
    test_dword();
    test_cacheline();
    test_stride();
    test_block_copy();
    test_rmw();
    test_eviction();

    printf("\n=== Summary: PASS=%d  FAIL=%d ===\n", g_pass, g_fail);
    if (g_fail == 0) {
        printf("ALL TESTS PASSED — CHIHNSubNode R/W OK\n");
        return 0;
    }
    printf("SOME TESTS FAILED\n");
    return 1;
}
