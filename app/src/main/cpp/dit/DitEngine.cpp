#include "core/experimental_trace.h"
// libdit_engine.so: stable-diffusion.cpp behind the narrow DitEngine ABI.
//
// Everything specific to stable-diffusion.cpp and ggml stays on this side of
// the boundary, so the core never includes their headers and never links their
// objects. See include/DitEngine.h for the contract.

#ifdef LOCAL_DREAM_LORA
#include "LoraDitEngine.h"
#else
#include "DitEngine.h"
#endif

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <exception>
#include <sys/resource.h>
#include <cstring>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

namespace {

// stable-diffusion.cpp installs progress, preview and log callbacks globally
// rather than per context, so the engine routes them through the generation
// that is currently running. Generations are serialized by g_gen_mutex, which
// makes "currently running" unambiguous.
std::mutex g_gen_mutex;

struct ActiveGeneration {
  dit_progress_cb progress = nullptr;
  dit_preview_cb preview = nullptr;
  void *user_data = nullptr;
  dit_ctx *ctx = nullptr;
  bool cancelled = false;
  bool sampling = false;
  int sampling_steps = 0;
};

ActiveGeneration g_active;

dit_log_cb g_log_cb = nullptr;
void *g_log_user_data = nullptr;

std::string g_create_error;

bool image_pixel_count(const sd_image_t &image, size_t *pixel_count) {
  if (!pixel_count || image.width == 0 || image.height == 0) return false;
  const size_t width = static_cast<size_t>(image.width);
  const size_t height = static_cast<size_t>(image.height);
  if (height > std::numeric_limits<size_t>::max() / width) return false;
  const size_t pixels = width * height;
  if (pixels > std::numeric_limits<size_t>::max() / 4) return false;
  *pixel_count = pixels;
  return true;
}

// Qwen Image 2.1 has a native RGBA VAE. Per-step preview callbacks still carry
// RGB, so composite straight alpha over white for previews only. The final
// result keeps the original RGBA allocation and channel count.
void image_to_rgb(const sd_image_t &image, size_t pixel_count, uint8_t *rgb) {
  if (image.channel == 3) {
    std::memcpy(rgb, image.data, pixel_count * 3);
    return;
  }

  for (size_t i = 0; i < pixel_count; ++i) {
    const uint8_t *rgba = image.data + i * 4;
    uint8_t *dst = rgb + i * 3;
    const unsigned alpha = rgba[3];
    for (size_t channel = 0; channel < 3; ++channel) {
      dst[channel] = static_cast<uint8_t>(
          (static_cast<unsigned>(rgba[channel]) * alpha +
           255u * (255u - alpha) + 127u) /
          255u);
    }
  }
}

}  // namespace

struct dit_ctx {
  sd_ctx_t *sd = nullptr;
  dit_model_kind kind = DIT_MODEL_Z_IMAGE;
  std::string last_error;
  int preview_interval = 0;
  bool sm8650_cpu_vae = true;
};

namespace {

void diagnostic_stage(const char *stage) {
  if (!std::getenv("LOCAL_DREAM_SM8650_PROFILE") || !g_log_cb) return;
  rusage usage{};
  getrusage(RUSAGE_SELF, &usage);
  char message[256];
  std::snprintf(message, sizeof(message), "FP8_DIAG stage=%s rss_peak_kib=%ld\n", stage, usage.ru_maxrss);
  g_log_cb(static_cast<int>(SD_LOG_INFO), message, g_log_user_data);
}

void forward_log(enum sd_log_level_t level, const char *text, void *) {
  // stable-diffusion.cpp uses the same global progress callback for model
  // loading, tiled VAE work and sampling. Its image pipeline emits these
  // messages immediately around sd->sample(), so use them to preserve the
  // stage across the otherwise phase-less callback ABI.
  if (g_active.ctx && text) {
    if (std::strstr(text, "generating image:")) {
      g_active.sampling = true;
      diagnostic_stage("sampling");
    } else if (std::strstr(text, "sampling completed") ||
               std::strstr(text, "Diffusion model sampling failed")) {
      g_active.sampling = false;
      diagnostic_stage("sampling_end_or_vae");
    }
  }
  if (g_log_cb) g_log_cb(static_cast<int>(level), text ? text : "", g_log_user_data);
}

void forward_progress(int step, int steps, float time, void *) {
  if (!g_active.progress) return;
  // A zero total marks non-sampling work. Still forward it so a disconnected
  // client can cancel a long VAE encode/decode or lazy parameter load, but do
  // not let those unrelated counters drive the UI progress bar.
  const int routed_steps =
      g_active.sampling && steps == g_active.sampling_steps ? steps : 0;
  if (routed_steps > 0 && step > 0 && std::getenv("LOCAL_DREAM_SM8650_PROFILE")) {
    std::fprintf(stderr, "FP8_DIAG sampling_step=%d/%d seconds=%.3f\n", step, routed_steps, time);
  }
  if (!g_active.progress(step, routed_steps, time, g_active.user_data)) {
    // The callback asked to stop. sd_cancel_generation only takes effect at
    // the next step boundary, so record it for the generate() return path.
    g_active.cancelled = true;
    if (g_active.ctx && g_active.ctx->sd) sd_cancel_generation(g_active.ctx->sd, SD_CANCEL_ALL);
  }
}

void forward_preview(int step, int frame_count, sd_image_t *frames, bool, void *) {
  if (!g_active.preview || frame_count < 1 || !frames || !frames[0].data) return;
  const sd_image_t &frame = frames[0];
  size_t pixel_count = 0;
  if (!image_pixel_count(frame, &pixel_count)) return;
  if (frame.channel == 3) {
    g_active.preview(step, frame.data, static_cast<int>(frame.width),
                     static_cast<int>(frame.height), g_active.user_data);
    return;
  }
  if (frame.channel != 4) return;

  try {
    std::vector<uint8_t> rgb(pixel_count * 3);
    image_to_rgb(frame, pixel_count, rgb.data());
    g_active.preview(step, rgb.data(), static_cast<int>(frame.width),
                     static_cast<int>(frame.height), g_active.user_data);
  } catch (...) {
    // A preview is optional; allocation failure must not abort generation.
  }
}

dit_ctx *engine_create(const dit_ctx_params *params) {
  g_create_error.clear();
  if (!params || !params->diffusion_model_path || !params->llm_path || !params->vae_path) {
    g_create_error = "missing model paths";
    return nullptr;
  }

  sd_ctx_params_t sd_params;
  sd_ctx_params_init(&sd_params);
  sd_params.diffusion_model_path = params->diffusion_model_path;
  sd_params.llm_path = params->llm_path;
  sd_params.llm_vision_path = params->llm_vision_path;
  sd_params.vae_path = params->vae_path;
#ifdef LOCAL_DREAM_LORA
  sd_params.lora_apply_mode = LORA_APPLY_AT_RUNTIME;
#endif
  sd_params.n_threads = params->n_threads > 0 ? params->n_threads : 4;
  sd_params.flash_attn = params->flash_attn;
  sd_params.diffusion_flash_attn = params->flash_attn;
  sd_params.vae_conv_direct = params->vae_conv_direct;
  const bool sm8650_compat = std::getenv("LOCAL_DREAM_SM8650_PROFILE") != nullptr;
  sd_params.disable_prefetch = sm8650_compat;
  if (sm8650_compat) {
    // Hexagon reports no live VRAM capacity. Without an explicit model budget,
    // the planner would otherwise retain the entire model's weights together.
    // This limit includes managed weights and graph buffers; the batch VMEM
    // limit is separate. Leave headroom for FastRPC and Android allocations.
    sd_params.max_vram = "HTP0=2.0";
    sd_params.disable_segmented_compute = false;
    std::fprintf(stderr, "FP8_DIAG managed_budget_mib=2048 prefetch=0 segmented_compute=1\n");
  }
  if (params->backend && params->backend[0]) sd_params.backend = params->backend;
  if (params->params_backend && params->params_backend[0])
    sd_params.params_backend = params->params_backend;

  const char *vae_backend = std::getenv("LOCAL_DREAM_SM8650_VAE");
  // New sessions default to DSP; CPU is an explicit, persisted fallback.
  const bool cpu_vae = vae_backend && std::strcmp(vae_backend, "CPU") == 0;
  if (sm8650_compat) {
    sd_params.backend = cpu_vae ? "all=HTP0,vae=CPU" : "all=HTP0";
    sd_params.vae_conv_direct = !cpu_vae;
    sd::experimental_trace::emit("stage=backend_selection diffusion=HTP0 te=HTP0 vae=%s vae_direct=%d",cpu_vae?"CPU":"HTP0",int(!cpu_vae));
  }

  auto *ctx = new dit_ctx();
  ctx->kind = params->kind;
  ctx->sm8650_cpu_vae = cpu_vae;
  diagnostic_stage("model_initialization");
  try {
    ctx->sd = new_sd_ctx(&sd_params);
  } catch (const std::exception &e) {
    g_create_error = e.what();
    delete ctx;
    return nullptr;
  }
  if (!ctx->sd) {
    g_create_error = "new_sd_ctx failed";
    delete ctx;
    return nullptr;
  }
  return ctx;
}

void engine_destroy(dit_ctx *ctx) {
  if (!ctx) return;
  if (ctx->sd) free_sd_ctx(ctx->sd);
  delete ctx;
}

bool engine_generate(dit_ctx *ctx, const dit_gen_params *params, dit_progress_cb progress,
                     dit_preview_cb preview, void *user_data, uint8_t **out_pixels,
                     int *out_width, int *out_height, int *out_channels) {
  if (!ctx || !ctx->sd || !params || !out_pixels || !out_channels) return false;
  ctx->last_error.clear();

  std::lock_guard<std::mutex> lock(g_gen_mutex);

  sd_img_gen_params_t gen;
  sd_img_gen_params_init(&gen);
  gen.prompt = params->prompt ? params->prompt : "";
  gen.negative_prompt = params->negative_prompt ? params->negative_prompt : "";
  gen.width = params->width;
  gen.height = params->height;
  gen.seed = params->seed;
  gen.batch_count = 1;
  gen.sample_params.sample_steps = params->steps;
  gen.sample_params.guidance.txt_cfg = params->cfg_scale;
  gen.sample_params.guidance.distilled_guidance = params->guidance;
  if (params->sample_method && params->sample_method[0])
    gen.sample_params.sample_method = str_to_sample_method(params->sample_method);

  if (params->init_image_rgb && params->init_width > 0 && params->init_height > 0) {
    gen.init_image.width = static_cast<uint32_t>(params->init_width);
    gen.init_image.height = static_cast<uint32_t>(params->init_height);
    gen.init_image.channel = 3;
    gen.init_image.data = const_cast<uint8_t *>(params->init_image_rgb);
    gen.strength = params->denoise_strength;
  }

  if (params->mask_image && params->mask_width > 0 && params->mask_height > 0) {
    if (!gen.init_image.data) {
      ctx->last_error = "inpaint mask requires an init image";
      return false;
    }
    if (params->mask_width != params->init_width ||
        params->mask_height != params->init_height) {
      ctx->last_error = "inpaint mask dimensions do not match init image";
      return false;
    }
    gen.mask_image.width = static_cast<uint32_t>(params->mask_width);
    gen.mask_image.height = static_cast<uint32_t>(params->mask_height);
    gen.mask_image.channel = 1;
    gen.mask_image.data = const_cast<uint8_t *>(params->mask_image);
  }

  std::vector<sd_image_t> reference_images;
  if (params->reference_image_count > 0) {
    if (!params->reference_images_rgb ||
        !params->reference_widths || !params->reference_heights) {
      ctx->last_error = "invalid reference images";
      return false;
    }
    reference_images.reserve(params->reference_image_count);
    for (int i = 0; i < params->reference_image_count; ++i) {
      if (!params->reference_images_rgb[i] || params->reference_widths[i] <= 0 ||
          params->reference_heights[i] <= 0) {
        ctx->last_error = "invalid reference image";
        return false;
      }
      reference_images.push_back(
          {static_cast<uint32_t>(params->reference_widths[i]),
           static_cast<uint32_t>(params->reference_heights[i]), 3,
           const_cast<uint8_t *>(params->reference_images_rgb[i])});
    }
    gen.ref_images = reference_images.data();
    gen.ref_images_count = static_cast<int>(reference_images.size());
    gen.ref_image_args =
        ctx->kind == DIT_MODEL_QWEN_IMAGE_2_1
            ? "preset=qwen"
            : ctx->kind == DIT_MODEL_KREA_2_TURBO
                  ? "preset=krea2_ostris_edit"
                  : "preset=flux2";
  }

  if (std::getenv("LOCAL_DREAM_SM8650_PROFILE")) {
    // Keep the verified 512px CPU path intact; tile larger canvases on CPU
    // as well as DSP to bound VAE encode/decode working memory.
    gen.vae_tiling_params.enabled = !ctx->sm8650_cpu_vae ||
        static_cast<int64_t>(params->width)*params->height > 512LL*512;
    gen.vae_tiling_params.tile_size_x = 64;
    gen.vae_tiling_params.tile_size_y = 64;
    gen.vae_tiling_params.target_overlap = 0.25f;
  } else if (params->vae_tile_size > 0) {
    gen.vae_tiling_params.enabled = true;
    gen.vae_tiling_params.tile_size_x = params->vae_tile_size;
    gen.vae_tiling_params.tile_size_y = params->vae_tile_size;
    gen.vae_tiling_params.target_overlap = params->vae_tile_overlap;
  }

  int sampling_steps = std::max(1, params->steps);
  if (gen.init_image.data && gen.strength < 1.0f) {
    // stable-diffusion.cpp retains t_enc + 1 intervals after trimming.
    const int t_enc = static_cast<int>(params->steps * gen.strength);
    sampling_steps = std::clamp(t_enc + 1, 1, std::max(1, params->steps));
  }
  g_active = ActiveGeneration{progress, preview, user_data, ctx, false, false,
                              sampling_steps};
  sd_set_progress_callback(progress ? forward_progress : nullptr, nullptr);
  if (preview && ctx->preview_interval > 0) {
    sd_set_preview_callback(forward_preview, PREVIEW_PROJ, ctx->preview_interval,
                            /*denoised=*/true, /*noisy=*/false, nullptr);
  } else {
    sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
  }

#ifdef LOCAL_DREAM_LORA
  if (params->lora_count < 1 || params->lora_count > 3 || !params->loras) { ctx->last_error = "LoRA engine requires 1 to 3 LoRAs"; return false; }
  std::vector<sd_lora_t> loras;
  for (int i=0;i<params->lora_count;++i) {
    if (!params->loras[i].path || !std::isfinite(params->loras[i].weight) || std::abs(params->loras[i].weight)>2) { ctx->last_error="Invalid LoRA parameters";return false; }
    loras.push_back({false,params->loras[i].weight,params->loras[i].path});
  }
  gen.loras=loras.data();gen.lora_count=loras.size();
#endif
  sd_image_t *images = nullptr;
  int image_count = 0;
  sd::experimental_trace::reset();
  sd::experimental_trace::emit("stage=generation_begin build=v0.16 seed=%lld cfg=%g width=%d height=%d vae=%s vae_direct=%d tiling=%d", (long long)params->seed, params->cfg_scale, params->width, params->height,ctx->sm8650_cpu_vae?"CPU":"HTP0",int(!ctx->sm8650_cpu_vae),int(gen.vae_tiling_params.enabled));
  diagnostic_stage("text_encoding_and_lazy_loading");
  bool ok = false;
  try {
    ok = generate_image(ctx->sd, &gen, &images, &image_count);
  } catch (const std::exception &e) {
    ctx->last_error = e.what();
  }
  diagnostic_stage(ok ? "generation_complete" : "generation_failed");

  sd_set_progress_callback(nullptr, nullptr);
  sd_set_preview_callback(nullptr, PREVIEW_NONE, 0, false, false, nullptr);
  const bool cancelled = g_active.cancelled;
  g_active = ActiveGeneration{};
  // Clear the sticky cancel flag so the context stays usable afterwards.
  if (cancelled) sd_cancel_generation(ctx->sd, SD_CANCEL_RESET);

  if (!ok || image_count < 1 || !images || !images[0].data) {
    if (ctx->last_error.empty()) ctx->last_error = cancelled ? "cancelled" : "generate_image failed";
    if (images) free_sd_images(images, std::max(0, image_count));
    return false;
  }

  sd_image_t &image = images[0];
  size_t pixel_count = 0;
  if (!image_pixel_count(image, &pixel_count) ||
      (image.channel != 3 && image.channel != 4)) {
    ctx->last_error = "unsupported generated image dimensions/channels: " +
                      std::to_string(image.width) + "x" +
                      std::to_string(image.height) + "x" +
                      std::to_string(image.channel);
    free_sd_images(images, image_count);
    return false;
  }

  if (std::getenv("LOCAL_DREAM_SM8650_PROFILE")) {
    for (unsigned c = 0; c < 3; ++c) {
      unsigned lo=255, hi=0;
      double sum=0;
      for (size_t i=0; i<pixel_count; ++i) {
        unsigned v=image.data[i*image.channel+c];
        lo=std::min(lo,v); hi=std::max(hi,v); sum+=v;
      }
      sd::experimental_trace::emit("stage=rgb channel=%u min=%u max=%u mean=%.4f",c,lo,hi,sum/pixel_count);
    }
  }

  sd::experimental_trace::emit("stage=generation_complete width=%u height=%u channels=%u", image.width,image.height,image.channel);
  // Transfer the original interleaved allocation. In particular, preserve
  // Qwen Image 2.1's native alpha channel for the core/Kotlin receiver.
  *out_pixels = image.data;
  *out_channels = static_cast<int>(image.channel);
  image.data = nullptr;
  if (out_width) *out_width = static_cast<int>(image.width);
  if (out_height) *out_height = static_cast<int>(image.height);
  free_sd_images(images, image_count);
  return true;
}

void engine_free_image(uint8_t *pixels) { free(pixels); }

const char *engine_last_error(const dit_ctx *ctx) {
  if (!ctx) return g_create_error.c_str();
  return ctx->last_error.c_str();
}

void engine_set_log_callback(dit_log_cb cb, void *user_data) {
  g_log_cb = cb;
  g_log_user_data = user_data;
  sd_set_log_callback(cb ? forward_log : nullptr, nullptr);
}

void engine_set_preview_interval(dit_ctx *ctx, int interval) {
  if (ctx) ctx->preview_interval = interval;
}

const dit_engine_api g_api = {
    DIT_ENGINE_ABI_VERSION,
    engine_create,
    engine_destroy,
    engine_generate,
    engine_free_image,
    engine_last_error,
    engine_set_log_callback,
    engine_set_preview_interval,
};

}  // namespace

#ifdef LOCAL_DREAM_LORA
#define LOCAL_DREAM_ENGINE_ENTRY dit_lora_engine_get_api
#else
#define LOCAL_DREAM_ENGINE_ENTRY dit_engine_get_api
#endif
extern "C" __attribute__((visibility("default"))) const dit_engine_api *LOCAL_DREAM_ENGINE_ENTRY(int requested_abi_version) {
  if (requested_abi_version != DIT_ENGINE_ABI_VERSION) return nullptr;
  return &g_api;
}
