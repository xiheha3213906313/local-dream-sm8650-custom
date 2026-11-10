// Compile and run with run_groupnorm_hvx_test.py after the native build.
// Include production code: unused FastRPC/DMA functions are removed by --gc-sections.
#include <stdio.h>
#include <stdlib.h>
#include "groupnorm-ops.c"

static unsigned check_case(unsigned n, unsigned chunk, unsigned pattern, int half) {
    __fp16 x[8192+64] __attribute__((aligned(128)));
    float y[8192+32] __attribute__((aligned(128)));
    for (unsigned i=0;i<8192+64;++i) x[i]=(__fp16)31; // tail poison must be masked
    double sum=0,square=0;
    for (unsigned i=0;i<n;++i) {
        float v=(float)((int)((i*13)%31)-15)/16.f;
        if(pattern==1)v+=1.f;
        if(pattern==2)v=3.f;
        if(pattern==3)v*=0.015625f;
        x[i]=(__fp16)v; y[i]=v;
        sum+=v;square+=(double)v*v;
    }
    struct group_norm_accum acc;group_norm_accum_init(&acc);
    for(unsigned done=0;done<n;done+=chunk){
        unsigned take=n-done<chunk?n-done:chunk;
        if(half)group_norm_accumulate_f16(&acc,x+done,take);
        else group_norm_accumulate_f32(&acc,y+done,take);
    }
    float mean,ms;group_norm_accum_finish(&acc,n,&mean,&ms);
    double ref=sum/n,ref2=square/n;
    int fail=!isfinite(mean)||!isfinite(ms)||fabs(mean-ref)>2e-5*fmax(1.,fabs(ref))||fabs(ms-ref2)>2e-5*fmax(1.,fabs(ref2));
    if(fail)printf("FAIL half=%d n=%u chunk=%u pattern=%u mean=%g expected=%g mean_square=%g expected=%g\n",half,n,chunk,pattern,mean,ref,ms,ref2);
    return fail;
}
static unsigned check_transform(unsigned spatial, unsigned channels, int silu, int half) {
    __fp16 input16[8192+64] __attribute__((aligned(128))),output16[8192+64] __attribute__((aligned(128)));
    float input[8192+32] __attribute__((aligned(128))),output[8192+32] __attribute__((aligned(128)));
    float weights[4]={0.5f,0.75f,1.f,1.25f},bias[4]={-0.25f,0.125f,0.f,0.25f};
    unsigned n=spatial*channels;
    double sum=0,square=0;
    for(unsigned i=0;i<n;++i){
        input[i]=(float)((int)((i*13)%31)-15)/16.f+1.f;
        input16[i]=(__fp16)input[i];sum+=input[i];square+=(double)input[i]*input[i];
    }
    for(unsigned i=0;i<n+64;++i)output16[i]=(__fp16)31;
    struct group_norm_accum acc;group_norm_accum_init(&acc);
    if(half)group_norm_accumulate_f16(&acc,input16,n);else group_norm_accumulate_f32(&acc,input,n);
    float mean,ms;group_norm_accum_finish(&acc,n,&mean,&ms);
    struct group_norm_state st={.spatial=spatial,.weight=weights,.bias=bias,.flags=HTP_GROUP_NORM_AFFINE|(silu?HTP_GROUP_NORM_SILU:0)};
    float inv=1.f/sqrtf(fmaxf(ms-mean*mean,0.f)+1e-6f);
    if(half)group_norm_transform_f16(&st,0,0,input16,output16,n,hvx_vec_splat_f32(mean),hvx_vec_splat_f32(inv));
    else group_norm_transform_f32(&st,0,0,input,output,n,hvx_vec_splat_f32(mean),hvx_vec_splat_f32(inv));
    double refmean=sum/n,refinv=1./sqrt(square/n-refmean*refmean+1e-6),error=0,norm=0;
    for(unsigned i=0;i<n;++i){
        unsigned c=i/spatial;
        double ref=(input[i]-refmean)*refinv*weights[c]+bias[c];
        if(silu)ref=ref/(1.+exp(-ref));
        double actual=half?(float)output16[i]:output[i];
        if(!isfinite(actual))return 1;
        double diff=actual-ref;error+=diff*diff;norm+=ref*ref;
    }
    double nrmse=sqrt(error/fmax(norm,1e-30));
    unsigned fail=nrmse>0.01;
    for(unsigned i=n;i<n+64;++i)if(output16[i]!=(__fp16)31)fail=1;
    if(fail)printf("FAIL transform spatial=%u channels=%u half=%d silu=%d nrmse=%g\n",spatial,channels,half,silu,nrmse);
    return fail;
}
int main(void) {
    const unsigned sizes[]={1,17,63,64,65,96,127,128,4096,8192};
    unsigned errors=0,cases=0;
    for(unsigned i=0;i<sizeof(sizes)/sizeof(sizes[0]);++i)
        for(unsigned pattern=0;pattern<4;++pattern)
            for(unsigned split=0;split<2;++split){
                errors+=check_case(sizes[i],split?64:8192,pattern,1);++cases;
                if(sizes[i]%32==0){errors+=check_case(sizes[i],split?64:8192,pattern,0);++cases;}
            }
    for(unsigned spatial=64;spatial<=4096;spatial=spatial==64?81:spatial==81?4096:8192)
        for(int half=0;half<2;++half)for(int silu=0;silu<2;++silu){
            if(!half && spatial%32)continue;
            errors+=check_transform(spatial,spatial==4096?1:4,silu,half);++cases;
        }
    printf("GROUP_NORM_HVX_%s cases=%u errors=%u: production F16/F32 statistics, affine/SiLU output, chunk accumulation and poisoned tails\n",errors?"FAIL":"PASS",cases,errors);
    return errors!=0;
}
