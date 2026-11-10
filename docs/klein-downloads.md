# Klein 4B FP8：下载与导入

用于小米 14／SM8650 实验版的配套文件，总下载量约 **6.68 GB**。以下四个地址已在 2026-11-10 验证可通过匿名分段请求下载（HTTP 206）；无需 Hugging Face 令牌。

| 导入时选择的组件 | 文件大小 | 下载直链 |
|---|---:|---|
| 扩散模型 | 4,070,624,520 字节 | [flux-2-klein-4b-fp8.safetensors](https://huggingface.co/black-forest-labs/FLUX.2-klein-4b-fp8/resolve/main/flux-2-klein-4b-fp8.safetensors) |
| 文本编码器 | 2,262,670,048 字节 | [llm.gguf](https://huggingface.co/zhiyuanasad/flux2_klein_adreno/resolve/main/llm.gguf) |
| VAE | 336,213,556 字节 | [vae.safetensors](https://huggingface.co/zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors) |
| 分词器 | 11,422,654 字节 | [tokenizer.json](https://huggingface.co/Qwen/Qwen3-4B/resolve/main/tokenizer.json) |

## 在手机上导入

1. 下载以上四个文件，保留原始文件名即可。
2. 打开更新后的 **Local Dream SM8650**，进入 **DiT 模型 → 导入 DiT 模型**。
3. 选择 **FLUX.2 Klein 4B**，为模型填写新名称，例如 `Klein4B_Local`。
4. 依次选中扩散模型、文本编码器、VAE、分词器四个文件，点击“添加模型”。应用自动复制、检查并归类到 DiT 列表。
5. 先运行设置中的“适配诊断”，再尝试 512×512、4 步生成。导入成功只说明文件复制和检查通过，完整真机生成仍需验证。

无需 ZIP、手工创建标记文件或把权重转换成 Q4。应用复制文件到所选模型存储位置，因此需要再预留约 6.68 GB 空间。导入过程可以取消；失败不会覆盖同名模型。

SM8650 首版只接受 Klein 4B 的 FP8 E4M3 safetensors 扩散模型。GGUF 文本编码器是配套文件，不要把它放到扩散模型的位置；请选择 `tokenizer.json`，不要选择 `tokenizer_config.json`。

在原本支持的 SM8750+ 设备上，导入界面也提供 Z-Image Turbo、FLUX.2 Klein、Qwen Image 2.1、Krea 2 Turbo。需要视觉投影器的模型会显示第五个文件选择项；它们不因此获得 SM8650 支持。

来源：[Black Forest Labs](https://huggingface.co/black-forest-labs/FLUX.2-klein-4b-fp8)、[zhiyuanasad](https://huggingface.co/zhiyuanasad/flux2_klein_adreno)、[Qwen](https://huggingface.co/Qwen/Qwen3-4B)。权重由原提供方托管，其许可条款仍由各模型仓库规定。本 GitHub 仓库不重新分发这些权重。
