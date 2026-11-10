#include <initializer_list>
#include <cassert>
#include <cmath>
#include <cstdio>
#include "fp8-compat.h"

int main() {
    unsigned overflow_cases = 0;
    // Exact coherent dot products from the on-device wide-range cases.
    // The old half output overflowed even though the FP32 result is valid.
    for (int k : {256, 3072, 12288}) for (int sign : {-1, 1}) {
        float reference = float(sign * k) * 448.f;
        _Float16 old_output = (_Float16) reference;
        assert(!std::isfinite(float(old_output)));
        ++overflow_cases;
        _Float16 protected_output = (_Float16)(reference * HTP_FP8_HMX_DOWNSCALE);
        float restored = float(protected_output) * HTP_FP8_HMX_RESTORE;
        assert(std::isfinite(restored));
        assert(restored == reference);
    }
    assert(HTP_FP8_HMX_DOWNSCALE * HTP_FP8_HMX_RESTORE == 1.f);
    std::printf("PASS: %u formerly overflowing half-output cases restored exactly in FP32\n", overflow_cases);
}
