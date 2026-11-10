// Runs in its own Android process; stdout is captured by the app's log exporter.
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-hexagon.h"
#include "ggml-alloc.h"
#include "fp8-compat.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <vector>
#include <chrono>
#include <sys/resource.h>

static void check(bool ok, const char *message) { if (!ok) throw std::runtime_error(message); }
static float decoded(uint8_t x) {
    uint16_t bits=htp_fp8_to_fp16_bits(x); __fp16 f; std::memcpy(&f,&bits,2); return f;
}
static void run_case(ggml_backend_t backend, int k, int n, int m, bool segmented) {
    std::printf("FP8_DIAG case_start k=%d n=%d m=%d segmented=%d\n", k, n, m, segmented);
    ggml_init_params params{32*1024*1024, nullptr, true};
    ggml_context *ctx=ggml_init(params); check(ctx,"context allocation failed");
    auto *w=ggml_new_tensor_2d(ctx,GGML_TYPE_F8_E4M3,k,n);
    auto *a=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,segmented?k/2:k,m);
    auto *b=segmented?ggml_new_tensor_2d(ctx,GGML_TYPE_F32,k/2,m):nullptr;
    ggml_set_name(w,"fp8_diagnostic_weights"); ggml_set_param(w);
    auto *product=segmented?ggml_mul_mat_segmented(ctx,w,a,b):ggml_mul_mat(ctx,w,a);
    check(ggml_backend_supports_op(backend,product),"FP8 matmul unsupported on detected DSP");
    auto *scaled=ggml_scale(ctx,product,0.125f);
    check(ggml_backend_supports_op(backend,scaled),"scale unsupported");
    auto *bias=ggml_new_tensor_1d(ctx,GGML_TYPE_F32,n);
    auto *result=ggml_add(ctx,scaled,bias);
    check(ggml_backend_supports_op(backend,result),"bias unsupported");
    auto *graph=ggml_new_graph(ctx); ggml_build_forward_expand(graph,result);
    auto *buf=ggml_backend_alloc_ctx_tensors(ctx,backend); check(buf,"RPC tensor allocation failed");
    // Exercise every finite FP8 code including signed zero, subnormals, and extremes.
    std::vector<uint8_t> weights(k*n);
    for(size_t i=0;i<weights.size();++i) { unsigned x=(i*73+19)%256; weights[i]=(x&127)==127?0:x; }
    std::vector<float> input(k*m),left((segmented?k/2:k)*m),right(segmented?k/2*m:0),biases(n),output(n*m);
    for(size_t i=0;i<input.size();++i) input[i]=float(int((i*17+3)%31)-15)/4096.f;
    for(int row=0;row<m;++row) for(int col=0;col<k;++col)
        if(segmented && col>=k/2) right[row*k/2+col-k/2]=input[row*k+col];
        else left[row*(segmented?k/2:k)+col]=input[row*k+col];
    for(int i=0;i<n;++i) biases[i]=float(i%7-3)/32.f;
    ggml_backend_tensor_set(w,weights.data(),0,weights.size());
    ggml_backend_tensor_set(a,left.data(),0,left.size()*4);
    if(b) ggml_backend_tensor_set(b,right.data(),0,right.size()*4);
    ggml_backend_tensor_set(bias,biases.data(),0,biases.size()*4);
    const auto start=std::chrono::steady_clock::now();
    check(ggml_backend_graph_compute(backend,graph)==GGML_STATUS_SUCCESS,"DSP graph computation failed");
    ggml_backend_synchronize(backend);
    ggml_backend_tensor_get(result,output.data(),0,output.size()*4);
    double error=0,norm=0;
    for(int row=0;row<m;++row) for(int col=0;col<n;++col) {
        double ref=0;
        for(int i=0;i<k;++i) ref+=double(decoded(weights[col*k+i]))*input[row*k+i];
        ref=ref*0.125+biases[col];
        double diff=output[row*n+col]-ref; error+=diff*diff; norm+=ref*ref;
    }
    double nrmse=std::sqrt(error/std::fmax(norm,1e-30));
    std::printf("FP8_DIAG matmul k=%d n=%d m=%d segmented=%d nrmse=%.8f elapsed_ms=%lld\n",k,n,m,segmented,nrmse,
        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count());
    ggml_backend_buffer_free(buf); ggml_free(ctx);
    check(std::isfinite(nrmse) && nrmse<=0.01,"FP8 HMX numerical comparison failed");
}
int main() {
    setvbuf(stdout,nullptr,_IONBF,0); setvbuf(stderr,nullptr,_IONBF,0);
    try {
        setenv("GGML_HEXAGON_FP8_COMPAT","1",1);
        setenv("GGML_HEXAGON_VMEM","3072",1);
        std::puts("FP8_DIAG stage=initialization profile=sm8650_fp8_compat");
        auto *reg=ggml_backend_hexagon_reg(); check(reg,"FastRPC initialization failed");
        check(ggml_backend_reg_dev_count(reg)>0,"No Hexagon device available");
        auto *backend=ggml_backend_dev_init(ggml_backend_reg_dev_get(reg,0),nullptr);
        check(backend,"HTP initialization failed");
        std::puts("FP8_DIAG stage=conversion_and_hmx");
        for(bool segmented:{false,true}) {
            run_case(backend,64,32,1,segmented);
            run_case(backend,128,96,33,segmented);
            run_case(backend,3072,3072,32,segmented);
            run_case(backend,3072,96,65,segmented);
            run_case(backend,9216,64,33,segmented);
            run_case(backend,12288,64,33,segmented);
        }
        ggml_backend_free(backend);
        rusage usage{}; getrusage(RUSAGE_SELF,&usage);
        std::printf("FP8_DIAG PASS rss_peak_kib=%ld; system DMA totals unavailable without privileged observability\n",usage.ru_maxrss);
        return 0;
    } catch(const std::exception &e) {
        std::fprintf(stderr,"FP8_DIAG FAIL %s\n",e.what()); return 1;
    }
}
