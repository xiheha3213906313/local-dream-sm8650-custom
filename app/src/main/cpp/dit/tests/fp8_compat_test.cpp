#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <set>
#include "fp8-compat.h"
#include "matmul-ops.h"

static float reference(unsigned x) {
    unsigned e = x >> 3 & 15, m = x & 7;
    float v = (e == 15 && m == 7) ? NAN : e ? std::ldexp(1.f + m / 8.f, int(e) - 7) : std::ldexp(float(m), -9);
    return x & 128 ? -v : v;
}
int main() {
    for (unsigned x = 0; x < 256; ++x) {
        uint16_t bits = htp_fp8_to_fp16_bits(x);
        _Float16 h; std::memcpy(&h, &bits, 2);
        float want = reference(x), actual = float(h);
        assert(std::isnan(want) ? std::isnan(actual) : (want == actual && std::signbit(want) == std::signbit(actual)));
    }
    std::set<size_t> src, dst;
    for (unsigned n = 0; n < 32; ++n) for (unsigned k = 0; k < 32; ++k) {
        // Independently spell the hardware layouts: packed 4-column FP8 groups,
        // and paired K lanes in the FP16 transposed HMX tile.
        const size_t a = (k / 4) * 128 + n * 4 + ((k % 4 == 1) ? 2 : (k % 4 == 2) ? 1 : k % 4);
        const size_t b = (k / 2) * 64 + n * 2 + k % 2;
        assert(a == htp_fp8_tile_offset(n, k)); assert(b == htp_fp16_tile_offset(n, k));
        src.insert(a); dst.insert(b);
    }
    assert(src.size() == 1024 && dst.size() == 1024);
    for (unsigned k : {32u, 128u, 3072u, 12288u}) for (unsigned m : {1u, 32u, 257u})
    for (bool pipeline : {false, true}) for (size_t budget : {512ul*1024, 2ul*1024*1024, 8ul*1024*1024}) {
        size_t mc=0,nc=0,used=0; int threads=0;
        bool ok=htp_mm_hmx_solve_2d_params(HTP_TYPE_F8_E4M3_COMPAT,k,0,3072,((m+31)/32)*32,m,4,pipeline,false,1024,budget,&mc,&nc,&threads,&used);
        if (!ok) continue;
        htp_mm_hmx_vtcm_layout l{};
        htp_mm_hmx_vtcm_layout_build(&l,HTP_MM_KERNEL_HMX_2D,HTP_TYPE_F8_E4M3_COMPAT,k,mc,nc,1,false,pipeline,threads,1024);
        assert(l.total_bytes <= budget && l.total_bytes <= used);
        assert(l.scratch_bytes[0] >= nc*k*2 && (!pipeline || l.scratch_bytes[1] >= nc*k*2));
        assert(l.off_scratch[0] >= l.off_weight[0]+l.weight_area_bytes);
        assert(mc % 32 == 0 && nc % 32 == 0);
    }
    std::puts("PASS: all 256 encodings, tile permutations, and bounded serial/pipelined layouts");
}
