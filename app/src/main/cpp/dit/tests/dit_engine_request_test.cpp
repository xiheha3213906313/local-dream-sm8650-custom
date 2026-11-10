// Link the production DitEngine.cpp against a fake SD runtime. This verifies
// unlocked requests cross the public engine ABI; it does not run model math.
#include "DitEngine.h"
#include "stable-diffusion.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

struct sd_ctx_t {};
static sd_log_cb_t log_cb;
static sd_progress_cb_t progress_cb;
static void *log_data,*progress_data;
static unsigned calls,steps_seen;
static bool expect_init,expect_mask,expect_refs,expect_cpu,expect_tiling;
static int expected_width,expected_height,expected_steps;
static dit_model_kind expected_kind;
extern "C" {
void sd_ctx_params_init(sd_ctx_params_t *p){*p={};}
void sd_img_gen_params_init(sd_img_gen_params_t *p){*p={};}
sd_ctx_t *new_sd_ctx(const sd_ctx_params_t *p){
    assert(std::string(p->backend)==(expect_cpu?"all=HTP0,vae=CPU":"all=HTP0"));
    assert(p->vae_conv_direct==!expect_cpu);
    assert(std::string(p->max_vram)=="HTP0=2.0");
    assert(p->disable_prefetch && !p->disable_segmented_compute);
    const bool vision=expected_kind==DIT_MODEL_QWEN_IMAGE_2_1 || expected_kind==DIT_MODEL_KREA_2_TURBO;
    assert(bool(p->llm_vision_path)==vision);
    return new sd_ctx_t;
}
void free_sd_ctx(sd_ctx_t *p){delete p;}
void sd_set_log_callback(sd_log_cb_t cb,void *data){log_cb=cb;log_data=data;}
void sd_set_progress_callback(sd_progress_cb_t cb,void *data){progress_cb=cb;progress_data=data;}
void sd_set_preview_callback(sd_preview_cb_t,preview_t,int,bool,bool,void*){}
void sd_cancel_generation(sd_ctx_t*,sd_cancel_mode_t){}
sample_method_t str_to_sample_method(const char*){return EULER_SAMPLE_METHOD;}
void free_sd_images(sd_image_t *images,int count){for(int i=0;i<count;++i)free(images[i].data);free(images);}
bool generate_image(sd_ctx_t*,const sd_img_gen_params_t *p,sd_image_t **images,int *count){
    ++calls;
    assert(p->width==expected_width && p->height==expected_height);
    assert(p->sample_params.sample_steps==expected_steps);
    assert(bool(p->init_image.data)==expect_init && bool(p->mask_image.data)==expect_mask);
    assert((p->ref_images_count>0)==expect_refs);
    if(expect_refs)assert(std::string(p->ref_image_args)==(expected_kind==DIT_MODEL_QWEN_IMAGE_2_1?"preset=qwen":expected_kind==DIT_MODEL_KREA_2_TURBO?"preset=krea2_ostris_edit":"preset=flux2"));
    assert(p->vae_tiling_params.enabled==expect_tiling);
    log_cb(SD_LOG_INFO,"generating image:",log_data);
    for(int i=1;i<=expected_steps;++i)progress_cb(i,expected_steps,.1f,progress_data);
    log_cb(SD_LOG_INFO,"sampling completed",log_data);
    *count=1;*images=(sd_image_t*)calloc(1,sizeof(sd_image_t));
    (*images)[0]={uint32_t(p->width),uint32_t(p->height),3,(uint8_t*)calloc(size_t(p->width)*p->height,3)};
    return true;
}
}
static void log_sink(int,const char*,void*){}
static bool progress_sink(int step,int total,float,void*){
    if(total){assert(total==expected_steps && step==int(++steps_seen));}
    return true;
}
static void run_case(const dit_engine_api *api,dit_ctx *ctx,int w,int h,int steps,int edit){
    expected_width=w;expected_height=h;expected_steps=steps;steps_seen=0;
    expect_init=edit==1 || edit==2;expect_mask=edit==2;expect_refs=edit==3;
    expect_tiling=!expect_cpu || int64_t(w)*h>512LL*512;
    uint8_t rgb[32*32*3]={},mask[32*32]={};const uint8_t *refs[]={rgb};const int dims[]={32};
    dit_gen_params p{};p.prompt="test";p.width=w;p.height=h;p.steps=steps;p.cfg_scale=1;p.seed=42;p.denoise_strength=1;
    if(expect_init){p.init_image_rgb=rgb;p.init_width=p.init_height=32;}
    if(expect_mask){p.mask_image=mask;p.mask_width=p.mask_height=32;}
    if(expect_refs){p.reference_images_rgb=refs;p.reference_widths=p.reference_heights=dims;p.reference_image_count=1;}
    uint8_t *out=nullptr;int width=0,height=0,channels=0;
    assert(api->generate(ctx,&p,progress_sink,nullptr,nullptr,&out,&width,&height,&channels));
    assert(out && width==w && height==h && channels==3 && steps_seen==unsigned(steps));
    api->free_image(out);
}
int main(){
    unsetenv("LOCAL_DREAM_DIT_PROFILE");
    setenv("LOCAL_DREAM_SM8650_PROFILE","sm8650_fp8_compat",1);
    const auto *api=dit_engine_get_api(DIT_ENGINE_ABI_VERSION);assert(api);
    api->set_log_callback(log_sink,nullptr);
    dit_ctx_params p{};p.diffusion_model_path="dit";p.llm_path="llm";p.vae_path="vae";
    auto run_model=[&](dit_model_kind kind,bool cpu){
        expected_kind=kind;p.kind=kind;expect_cpu=cpu;
        p.llm_vision_path=kind==DIT_MODEL_QWEN_IMAGE_2_1 || kind==DIT_MODEL_KREA_2_TURBO?"vision":nullptr;
        if(cpu)setenv("LOCAL_DREAM_SM8650_VAE","CPU",1);else unsetenv("LOCAL_DREAM_SM8650_VAE");
        auto *ctx=api->create(&p);assert(ctx);
        run_case(api,ctx,512,768,8,0);
        run_case(api,ctx,1024,1024,30,0);
        run_case(api,ctx,768,512,12,1);
        run_case(api,ctx,512,512,6,2);
        if(kind!=DIT_MODEL_Z_IMAGE)run_case(api,ctx,512,768,16,3);
        api->destroy(ctx);
    };
    for(auto kind:{DIT_MODEL_Z_IMAGE,DIT_MODEL_FLUX2_KLEIN,DIT_MODEL_QWEN_IMAGE_2_1,DIT_MODEL_KREA_2_TURBO})run_model(kind,false);
    run_model(DIT_MODEL_FLUX2_KLEIN,true);
    assert(calls==24);
    puts("PASS: 24 production ABI requests, all 4 model kinds on SM8650, sizes/steps, images/masks/references, vision paths, DSP default and CPU fallback");
}
