// Compile with the Hexagon compiler and run under hexagon-sim -mv75.
// Uses the same HVX conversion and tile routine as the shipped DSP kernel.
#include <stdio.h>
#include <string.h>
#include "fp8-compat-hvx.h"

int main(void) {
    unsigned errors = 0;
    uint8_t input[256] __attribute__((aligned(128)));
    uint16_t output[256] __attribute__((aligned(128)));
    for (unsigned order = 0; order < 8; ++order) {
        for (unsigned i = 0; i < 256; ++i) input[i] = (uint8_t)(i * 73 + order * 29);
        for (unsigned i = 0; i < 256; i += 128) {
            const HVX_VectorPair p = Q6_Wuh_vunpack_Vub(*(HVX_Vector *)(input + i));
            *(HVX_Vector *)(output + i) = fp8_compat_decode_half(Q6_V_lo_W(p));
            *(HVX_Vector *)(output + i + 64) = fp8_compat_decode_half(Q6_V_hi_W(p));
        }
        for (unsigned i = 0; i < 256; ++i) {
            if (output[i] != htp_fp8_to_fp16_bits(input[i])) {
                printf("FAIL code=%02x actual=%04x expected=%04x\n", input[i], output[i], htp_fp8_to_fp16_bits(input[i]));
                ++errors;
            }
        }
    }
    uint8_t packed[3 * 1024] __attribute__((aligned(128)));
    uint16_t tiles[3 * 1024 + 128] __attribute__((aligned(128)));
    memset(tiles, 0x5a, sizeof(tiles));
    for (unsigned t = 0; t < 3; ++t) {
        for (unsigned n = 0; n < 32; ++n) for (unsigned k = 0; k < 32; ++k) {
            unsigned lane = k % 4 == 1 ? 2 : k % 4 == 2 ? 1 : k % 4;
            packed[t * 1024 + (k / 4) * 128 + n * 4 + lane] = (uint8_t)(t * 17 + n * 73 + k * 29);
        }
        htp_fp8_tile_to_fp16(tiles + 64 + t * 1024, packed + t * 1024);
        for (unsigned n = 0; n < 32; ++n) for (unsigned k = 0; k < 32; ++k) {
            uint8_t code = (uint8_t)(t * 17 + n * 73 + k * 29);
            unsigned offset = 64 + t * 1024 + (k / 2) * 64 + n * 2 + k % 2;
            if (tiles[offset] != htp_fp8_to_fp16_bits(code)) ++errors;
        }
    }
    for (unsigned i = 0; i < 64; ++i)
        if (tiles[i] != 0x5a5a || tiles[64 + 3 * 1024 + i] != 0x5a5a) ++errors;
    if (!fp8_compat_vector_selftest()) ++errors;
    printf("FP8_HVX_%s errors=%u: all 256 encodings, 8 lane orders, 3 tiles and guards\n", errors ? "FAIL" : "PASS", errors);
    return errors != 0;
}
