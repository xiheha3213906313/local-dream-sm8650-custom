# LoRA 开发预览版

生成页右上角菜单 → **LoRA**，导入标准 `.safetensors` LoRA，最多为一个基模配置 3 个。支持启用、关闭、替换和独立强度（−2～2）。文件保存在应用私有目录，同一文件只保存一份。LoRA 必须匹配基模架构和版本；DoRA、LyCORIS、LoHa、LoKr 不在此版本支持范围。

启用后，生成页普通参数下方显示 **LoRA 强度**。展开其中一个面板会收起另一个；长按左右滑动或点按展开编辑卡片。强度改动在下一次生成时生效。历史记录、参数分享与导入保留组合快照；在另一台设备恢复前须先导入相同 LoRA 文件。

全部关闭或强度全部为 0 时使用原有推理核心、原模型和原缓存。启用时使用独立核心 `libstable_diffusion_lora_core.so`，DiT 再加载 `libdit_lora_engine.so`。准备中的合并文件写入专用缓存，不覆盖基模。组合、强度或分辨率改变会重新准备模型；第一次可能较慢并额外占用存储空间。

## 模型支持

- **DiT**：独立 stable-diffusion.cpp LoRA 路径。实际支持的命名、层和架构以该引擎为准；不匹配的适配器报错。无需导入下面的兼容包。
- **CPU/GPU（MNN）**：需要与基模对应的外部权重映射。可以直接映射已有模型可修改的外部权重；否则需提供重新导出的兼容模型与外部权重。
- **NPU（QNN）**：需要使用 QNN 2.51 SDK 导出的可更新上下文，目标权重类型为 `QNN_TENSOR_TYPE_UPDATEABLE_STATIC`。普通已编译 `.bin` 无法自动还原或获得可更新权重。通过 QNN binary-section update API 更新权重，推理继续使用 NPU。

后两类在 LoRA 页面通过 **导入 LoRA 兼容包** 安装 ZIP。未安装时会明确提示，不会假装生效，也不会自动切换 CPU。此仓库提供打包工具和格式；具体模型的 SDK 导出文件、原始权重及映射需由模型包制作者提供。

## 制作兼容包

`export_package.py` 使用 Python 3.9+ 标准库，把 SDK 已导出的兼容运行文件、明确的权重映射和原始 safetensors 打包。它不反编译 QNN/MNN，也不推测量化偏移或张量 ID。

```sh
python3 tools/lora/export_package.py \
  --model-dir /path/to/installed-original-model \
  --base-weights /path/to/original-model.safetensors \
  --mapping /path/to/mapping.json \
  --runtime-dir /path/to/exported-runtime-files \
  --output model-lora-runtime.zip
```

`--runtime-dir` 可以省略，沿用原模型根目录文件。提供时其中的根目录文件仅在 LoRA 路径覆盖原运行文件。

MNN 映射示例（文件偏移、长度必须来自实际导出，不能照抄示例）：

```json
{
  "base_files": ["unet.mnn", "unet.mnn.weight"],
  "resolution": [512, 512],
  "targets": [{
    "aliases": ["lora_unet_down_blocks_0_attentions_0_proj_in"],
    "base_tensor": "unet.down_blocks.0.attentions.0.proj_in.weight",
    "shape": [320, 320, 1, 1],
    "storage": {"file": "unet.mnn.weight", "offset": 0, "length": 409600, "dtype": "F32"}
  }]
}
```

`aliases` 是去掉 `.lora_down.weight` / `.lora_up.weight` 或 `.lora_A.weight` / `.lora_B.weight` 后缀的 LoRA 名称前缀，也支持 `.lora_A.default.weight` / `.lora_B.default.weight`。`base_tensor` 指向原始 F32/F16/BF16 safetensors 张量；打包时仅拷贝映射需要的基模张量。支持二维线性层及 down 含卷积、up 为 1×1 的常见卷积 LoRA。MNN 存储编码还可选 `F16`、`BF16`、本项目 SafeTensor2MNN 输出的 `MNN_BLOCK_Q8`。

QNN 使用 `qnn` 替代 `storage`，例如：

```json
{
  "context": "unet.bin",
  "id": 42,
  "name": "actual_exported_tensor_name",
  "dtype": "UFIXED8",
  "permutation": [2, 3, 1, 0],
  "scale": 0.01,
  "offset": -128
}
```

ID、名称、布局及量化参数必须与对应 SDK 导出上下文一致。`permutation` 将原 OIHW 等基模布局变成 QNN 张量布局；省略时顺序不变。支持 F32/F16/UFIXED8/SFIXED8/UFIXED16/SFIXED16。逐轴量化使用 `axis` 和 `encodings: [{"scale": ..., "offset": ...}, ...]` 替代全局 scale/offset，axis 为变换后布局的轴。

生成包根目录包含 `lora_manifest.json`、`base.safetensors`、可选的 `runtime/`。清单包含 `format_version: 1`、`base_files` 文件名到 SHA-256 的映射、`targets`、`runtime_dir: "runtime"`，可选 `resolution`。安装会检查基模指纹。多分辨率包可手动组织为 `variants/512x512/`、`variants/512x768/` 等，每个目录放完整清单和对应文件，再打包这些目录；不要套多余的顶层文件夹。

## 构建

安装本项目原有 Android/Hexagon/QNN 2.51 工具链和 Rust Android 目标后，在仓库根目录运行 `bash tools/lora/build.sh`。脚本只构建、部署新增的两个核心，不覆盖原核心和 DSP 库。APK 构建仍需原有运行库已部署。
