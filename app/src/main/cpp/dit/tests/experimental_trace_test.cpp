#include "core/experimental_trace.h"
#include <cassert>
#include <fstream>
#include <iterator>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>

struct TensorFixture {
    std::vector<float> values;
    std::vector<int64_t> dimensions;
    int64_t numel() const { return values.size(); }
    const float* data() const { return values.data(); }
    bool empty() const { return values.empty(); }
    const std::vector<int64_t>& shape() const { return dimensions; }
};
static std::string read(const std::string& path) {
    std::ifstream file(path);
    return {std::istreambuf_iterator<char>(file),std::istreambuf_iterator<char>()};
}
int main() {
    using namespace sd::experimental_trace;
    const float samples[]={-2,0,0.5f,1,2,NAN,INFINITY,-INFINITY};
    auto stats=summarize(samples,8);
    assert(stats.finite==5 && stats.nan==1 && stats.inf==2 && stats.zero==1);
    assert(stats.below_zero==1 && stats.above_one==1 && stats.lo==-2 && stats.hi==2);
    assert(std::abs(stats.mean()-0.3)<1e-12 && std::abs(stats.rms()-std::sqrt(1.85))<1e-12);
    assert(!stats.valid());
    std::string path="/tmp/sm8650-trace-test-"+std::to_string(getpid())+".log";
    setenv("LOCAL_DREAM_DIAGNOSTIC_PATH",path.c_str(),1);
    unsetenv("LOCAL_DREAM_DIT_PROFILE");
    emit("disabled");
    assert(!std::ifstream(path).good());
    setenv("LOCAL_DREAM_DIT_PROFILE","sm8650_fp8_compat",1);
    TensorFixture rgb{{1,1,1,1,0,0,0,0,0,0,0,0},{2,2,3,1}};
    assert(tensor("vae.raw_before_clamp",rgb));
    assert(read(path).find("channel=0 finite=4 nan=0 inf=0 min=1 max=1")!=std::string::npos);
    assert(read(path).find("channel=1 finite=4 nan=0 inf=0 min=0 max=0")!=std::string::npos);
    rgb.values[0]=NAN;
    assert(!tensor("vae.invalid",rgb));
    assert(read(path).find("REJECT non-finite tensor")!=std::string::npos);
    reset();
    assert(read(path).empty());
    std::vector<std::thread> workers;
    for(unsigned i=0;i<16;++i)workers.emplace_back([i]{emit("stage=parallel id=%u",i);});
    for(auto& worker:workers)worker.join();
    const auto text=read(path);
    size_t lines=0;for(char c:text)if(c=='\n')++lines;
    assert(lines==16);
    for(unsigned i=0;i<16;++i)assert(text.find("stage=parallel id="+std::to_string(i)+"\n")!=std::string::npos);
    std::remove(path.c_str());
    puts("PASS: finite/NaN/Inf summaries, per-channel detection, opt-in private trace, reset and concurrent records");
}
