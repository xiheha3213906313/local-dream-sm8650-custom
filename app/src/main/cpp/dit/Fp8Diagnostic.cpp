// Runs in its own Android process; stdout is captured by the app's log exporter.
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-hexagon.h"
#include "ggml-alloc.h"
#include "fp8-compat.h"
#include <cmath>
#include <algorithm>
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
static void run_case(ggml_backend_t backend, int k, int n, int m, bool segmented, bool wide_range = false) {
    std::printf("FP8_DIAG case_start k=%d n=%d m=%d segmented=%d wide_range=%d\n", k, n, m, segmented, wide_range);
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
    if (wide_range) {
        // Coherent sums exceed FP16's 65504 limit, unlike the small-signal test.
        for (size_t i=0; i<weights.size(); ++i) weights[i]=(i/k)%2 ? 0xfe : 0x7e;
        for (size_t i=0; i<input.size(); ++i) input[i]=(i/k)%2 ? -1.f : 1.f;
    }
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
    std::printf("FP8_DIAG matmul k=%d n=%d m=%d segmented=%d wide_range=%d nrmse=%.8f elapsed_ms=%lld\n",k,n,m,segmented,wide_range,nrmse,
        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count());
    ggml_backend_buffer_free(buf); ggml_free(ctx);
    check(std::isfinite(nrmse) && nrmse<=0.01,"FP8 HMX numerical comparison failed");
}
static void run_conv_case(ggml_backend_t backend, int side, int ci, int co, int kernel, int upscale=1) {
    std::printf("HTP_CONV_DIAG case_start side=%d ci=%d co=%d kernel=%d upscale=%d\n",side,ci,co,kernel,upscale);
    const int output_side=side*upscale;
    ggml_init_params params{4*1024*1024, nullptr, true};
    auto *ctx=ggml_init(params); check(ctx,"conv context allocation failed");
    auto *weights_ctx=ggml_init(params); check(weights_ctx,"conv weight context allocation failed");
    auto *w=ggml_new_tensor_4d(weights_ctx,GGML_TYPE_F16,kernel,kernel,ci,co);
    auto *x=ggml_new_tensor_4d(ctx,GGML_TYPE_F32,side,side,ci,1);
    auto *bias=ggml_new_tensor_1d(ctx,GGML_TYPE_F32,co);
    ggml_set_name(w,"vae_conv_diagnostic_weights"); ggml_set_param(w);
    auto *result=upscale==1 ? ggml_conv_2d_direct_bias(ctx,w,x,bias,1,1,kernel/2,kernel/2,1,1)
        : ggml_conv_2d_direct_upscale(ctx,w,x,bias,upscale,1,1,kernel/2,kernel/2,1,1);
    check(ggml_backend_supports_op(backend,result),"HTP direct conv unsupported for this VAE shape");
    auto *graph=ggml_new_graph(ctx); ggml_build_forward_expand(graph,result);
    auto *weights_buffer=ggml_backend_alloc_ctx_tensors(weights_ctx,backend); check(weights_buffer,"conv weight RPC allocation failed");
    // Match production upload: only the weight buffer is marked WEIGHTS, so
    // Hexagon performs the same F16 convolution tile repack as the real VAE.
    ggml_backend_buffer_set_usage(weights_buffer,GGML_BACKEND_BUFFER_USAGE_WEIGHTS);
    auto *buffer=ggml_backend_alloc_ctx_tensors(ctx,backend); check(buffer,"conv RPC allocation failed");
    std::vector<__fp16> weights(kernel*kernel*ci*co);
    std::vector<float> input(side*side*ci),biases(co),output(output_side*output_side*co);
    for(size_t i=0;i<weights.size();++i)weights[i]=(__fp16)(float(int(i*13%17)-8)/64.f);
    for(size_t i=0;i<input.size();++i)input[i]=float(int(i*7%23)-11)/32.f;
    for(int i=0;i<co;++i)biases[i]=float(i%7-3)/32.f;
    ggml_backend_tensor_set(w,weights.data(),0,weights.size()*sizeof(__fp16));
    ggml_backend_tensor_set(x,input.data(),0,input.size()*sizeof(float));
    ggml_backend_tensor_set(bias,biases.data(),0,biases.size()*sizeof(float));
    check(ggml_backend_graph_compute(backend,graph)==GGML_STATUS_SUCCESS,"HTP direct conv compute failed");
    ggml_backend_synchronize(backend);
    ggml_backend_tensor_get(result,output.data(),0,output.size()*sizeof(float));
    double error=0,norm=0;size_t nonfinite=0,compared=0;
    for(float v:output)if(!std::isfinite(v))++nonfinite;
    std::vector<int> positions;
    if(output_side<=32)for(int i=0;i<output_side;++i)positions.push_back(i);
    else for(int i:{0,1,31,32,63,64,127,128,255,256,output_side-2,output_side-1})
        if(i>=0 && i<output_side && std::find(positions.begin(),positions.end(),i)==positions.end())positions.push_back(i);
    for(int oc=0;oc<co;++oc)for(int y:positions)for(int xx:positions){
        double ref=biases[oc];
        for(int c=0;c<ci;++c)for(int ky=0;ky<kernel;++ky)for(int kx=0;kx<kernel;++kx){
            int iy=y+ky-kernel/2,ix=xx+kx-kernel/2;
            if(ix<0||iy<0||ix>=output_side||iy>=output_side)continue;
            ref+=double(float(weights[((oc*ci+c)*kernel+ky)*kernel+kx]))*input[(c*side+iy/upscale)*side+ix/upscale];
        }
        float actual=output[(oc*output_side+y)*output_side+xx];
        double diff=actual-ref;error+=diff*diff;norm+=ref*ref;++compared;
    }
    double nrmse=std::sqrt(error/std::fmax(norm,1e-30));
    std::printf("HTP_CONV_DIAG result side=%d ci=%d co=%d kernel=%d upscale=%d compared=%zu nrmse=%.8f nonfinite=%zu\n",side,ci,co,kernel,upscale,compared,nrmse,nonfinite);
    ggml_backend_buffer_free(buffer);ggml_backend_buffer_free(weights_buffer);
    ggml_free(ctx);ggml_free(weights_ctx);
    check(!nonfinite&&std::isfinite(nrmse)&&nrmse<=0.01,"HTP direct conv disagrees with CPU reference");
}
int main(int argc, char **argv) {
    const bool conv_only=argc>1 && std::strcmp(argv[1],"--conv-only")==0;
    setvbuf(stdout,nullptr,_IONBF,0); setvbuf(stderr,nullptr,_IONBF,0);
    try {
        setenv("GGML_HEXAGON_FP8_COMPAT","1",1);
        setenv("GGML_HEXAGON_VMEM","3072",1);
        std::puts("FP8_DIAG stage=initialization profile=sm8650_fp8_compat");
        auto *reg=ggml_backend_hexagon_reg(); check(reg,"FastRPC initialization failed");
        check(ggml_backend_reg_dev_count(reg)>0,"No Hexagon device available");
        auto *backend=ggml_backend_dev_init(ggml_backend_reg_dev_get(reg,0),nullptr);
        check(backend,"HTP initialization failed");
        if(conv_only){
            std::puts("HTP_CONV_DIAG stage=vae_direct_conv CPU-reference comparison");
            run_conv_case(backend,8,32,3,3);
            run_conv_case(backend,9,32,32,3);
            run_conv_case(backend,8,32,128,1);
            run_conv_case(backend,9,128,3,3);
            run_conv_case(backend,16,32,32,3,2);
            run_conv_case(backend,64,128,128,3);
            run_conv_case(backend,512,128,3,3);
            ggml_backend_free(backend);
            std::puts("HTP_CONV_DIAG PASS (does not validate full VAE)");
            return 0;
        }
        std::puts("FP8_DIAG stage=conversion_and_hmx");
        for(bool segmented:{false,true}) {
            run_case(backend,64,32,1,segmented);
            run_case(backend,128,96,33,segmented);
            run_case(backend,3072,3072,32,segmented);
            run_case(backend,3072,96,65,segmented);
            run_case(backend,9216,64,33,segmented);
            run_case(backend,12288,64,33,segmented);
            run_case(backend,3072,96,65,segmented,true);
            run_case(backend,12288,64,1,segmented,true);
        }
        ggml_backend_free(backend);
        rusage usage{}; getrusage(RUSAGE_SELF,&usage);
        std::printf("FP8_DIAG PASS rss_peak_kib=%ld; system DMA totals unavailable without privileged observability\n",usage.ru_maxrss);
        return 0;
    } catch(const std::exception &e) {
        std::fprintf(stderr,"%s FAIL %s\n",conv_only?"HTP_CONV_DIAG":"FP8_DIAG",e.what()); return 1;
    }
}
