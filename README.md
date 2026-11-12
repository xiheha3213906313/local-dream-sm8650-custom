<div align="center">

# Local Dream <img src="./assets/icon.png" width="32" alt="Local Dream">

**Android Stable Diffusion with Snapdragon NPU acceleration**  
_Also supports CPU/GPU inference_

<img src="./assets/demo1.jpg" alt="App Demo" width="800">

</div>

## Miuix UI

v0.11 rebuilds the model library, fixed generation workspace and import pages with
[Miuix](https://github.com/compose-miuix-ui/miuix). Compact, single-line actions respect
large system fonts; filenames and expandable guidance live outside controls.
See [v0.15 input and gesture refinements](docs/sm8650-v0.15.md) for reselectable locked ratios, moving ruler marks, prompt expansion and reference-image ordering.
See [v0.14 interaction refinements](docs/sm8650-v0.14.md) for aspect-ratio locking, in-place drag extension, history filters and stable IME dismissal.
The [v0.13 generation controls](docs/sm8650-v0.13.md) notes cover parameter tiles that expand in place, outlined options and inputs, explicit random/fixed seed modes, and labeled image tools.
The [v0.12 fixes](docs/sm8650-v0.12.md) cover list/result scrolling and anchored menus.
The [v0.11 redesign notes](docs/sm8650-v0.11.md) describe the initial migration.

## Snapdragon 8 Gen 3 support

**SM8650 / SM8650P are included in the supported DiT device list.** All existing
model entries are available: Z-Image Turbo, Klein 4B/9B, FLUX.3, Qwen Image 2.1
and Turbo, and Krea 2 Turbo. Safetensors and GGUF import uses the common file
checks, without a Klein-only or FP8-only processor gate.

Xiaomi 14 Klein 4B 512px DSP generation has completed successfully. Other model
families are enabled for loading and use, but have not each been validated on
this phone. v75 still uses bounded FP8-to-FP16 HMX compatibility and segmented
memory management, with DSP VAE by default and an optional CPU fallback.
See [v0.10 device/model changes and validation limits](docs/sm8650-v0.10.md).

See [v0.16 Qwen Image 2.1 segmented loading fix](docs/sm8650-v0.16.md) for the 2 GiB budget failure reported on Xiaomi 14.
See [build instructions and phone test procedure](docs/sm8650-fp8.md).
The [v0.6 VAE isolation and trace notes](docs/sm8650-v0.6.md) describe the earlier CPU VAE isolation stage and numerical tracing.

SM8650 v0.7：保留 CPU 解码，修复 v75 FP16 GroupNorm 累计格式并增加数值对照，见 [修复与复测说明](docs/sm8650-v0.7.md)。

SM8650 v0.8：主页「⋮ → 设置」新增 CPU 解码开关，关闭可试用修复后的 DSP VAE，见 [设置与对照说明](docs/sm8650-v0.8.md)。

SM8650 v0.9：解除当前 Klein 4B 的尺寸、步数、张数与图像编辑测试锁，默认 DSP，见 [功能解锁说明](docs/sm8650-v0.9.md)。
See [v0.5 numerical stability and progress changes](docs/sm8650-v0.5.md).
The [v0.4 repair notes](docs/sm8650-v0.4.md) cover the reproduced v75 conversion failure and the model residency budget.
Local model import is available in the DiT tab. See [Klein 4B download links and import steps](docs/klein-downloads.md).
For diagnostic log export and recovery, see [v0.3 instructions](docs/log-export.md).
The application is named **Local Dream SM8650**, with a separate package ID.
The SM8650 APK reuses the pinned official alpha.7 core/QNN runtime and replaces
the DiT engine with the locally built engine; full core-source compilation is a
separate build path. Original attribution and licenses are preserved.

## About this Repo

This project is **now open sourced and completely free**. Hope you enjoy it!

> [!NOTE]
> Currently focused on SD1.5 and SDXL models. SD2.1 is no longer maintained due to poor quality and limited popularity.
>
> **NPU support:**
>
> - SD1.5 models are supported on Snapdragon NPUs with Hexagon V68 architecture or newer.
> - SDXL models are supported on Snapdragon 8 Gen 3 and newer devices.
>
> CPU/GPU inference supports SD1.5 and imported SDXL MNN packages. Large SDXL
> models require substantially more memory than SD1.5.
>
> You can join our [telegram group](https://t.me/local_dream) for discussion or help with testing.

## User Guide

For certain reasons, all guides and documentation have been moved to [Guide Site](https://ld-guide.chino.icu).

## Credits & Acknowledgments

Local Dream is built on top of many excellent open-source projects. Sincere thanks to all the authors and contributors whose work made this project possible.

### C++ Libraries

- **[Qualcomm QNN SDK](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk)** - NPU model execution
- **[alibaba/MNN](https://github.com/alibaba/MNN/)** - CPU model execution
- **[happyyzy/stable-diffusion.cpp](https://github.com/happyyzy/stable-diffusion.cpp)** - Deep performance optimizations for on-device DiT inference
- **[xtensor-stack](https://github.com/xtensor-stack)** - Tensor operations & scheduling
- **[mlc-ai/tokenizers-cpp](https://github.com/mlc-ai/tokenizers-cpp)** - Text tokenization
- **[yhirose/cpp-httplib](https://github.com/yhirose/cpp-httplib)** - HTTP server
- **[nothings/stb](https://github.com/nothings/stb)** - Image processing
- **[facebook/zstd](https://github.com/facebook/zstd)** - Model compression
- **[nlohmann/json](https://github.com/nlohmann/json)** - JSON processing

### Android Libraries

- **[square/okhttp](https://github.com/square/okhttp)** - HTTP client
- **[coil-kt/coil](https://github.com/coil-kt/coil)** - Image loading & processing
- **[MoyuruAizawa/Cropify](https://github.com/MoyuruAizawa/Cropify)** - Image cropping
- **AOSP, Material Design, Jetpack Compose** - UI framework

### Models

- **[CompVis/stable-diffusion](https://github.com/CompVis/stable-diffusion)** and all other model creators
- **[xinntao/Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)** - Image upscaling
- **[Kim2091/UltraSharpV2](https://huggingface.co/Kim2091/UltraSharpV2)** - Image upscaling
- **[bhky/opennsfw2](https://github.com/bhky/opennsfw2)** - NSFW content filtering

