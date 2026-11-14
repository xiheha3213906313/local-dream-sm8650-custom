#ifndef LOCAL_DREAM_LORA_PACKAGE_HPP
#define LOCAL_DREAM_LORA_PACKAGE_HPP
#include "LoraSupport.hpp"

namespace lora {
inline std::string tensorFile(size_t index) { return "lora-tensor-" + std::to_string(index) + ".f32"; }
inline bool targetsSource(const json& target, Source& source, bool remember) {
  for (const auto& alias : target.at("aliases")) {
    const auto prefix = alias.get<std::string>();
    for (const auto& suffix : {".lora_down.weight", ".lora_A.weight", ".lora_A.default.weight"}) {
      const auto name = prefix + suffix;
      if (source.reader->has_tensor(name)) {
        if (remember && !source.matched.insert(name).second) throw std::runtime_error("Ambiguous LoRA mapping: " + name);
        return true;
      }
    }
  }
  return false;
}
inline std::vector<uint8_t> encodeWeights(const json& storage, const json& target, const std::vector<float>& weights) {
  const auto dtype = storage.at("dtype").get<std::string>();
  if (dtype == "F32") {
    const auto* begin = reinterpret_cast<const uint8_t*>(weights.data());
    return std::vector<uint8_t>(begin, begin + weights.size()*4);
  }
  if (dtype == "F16" || dtype == "BF16") {
    std::vector<uint8_t> bytes(weights.size()*2);
    for (size_t i = 0; i < weights.size(); ++i) {
      const uint16_t value = dtype == "F16" ? fp32_to_fp16(weights[i]) : fp32_to_bf16(weights[i]);
      std::memcpy(bytes.data()+i*2, &value, 2);
    }
    return bytes;
  }
  if (dtype != "MNN_BLOCK_Q8") throw std::runtime_error("Unsupported LoRA cache encoding: " + dtype);
  // This is the external weight layout emitted by SafeTensor2MNN.hpp.
  const auto dims = target.at("shape").get<std::vector<int>>();
  if (dims.size() != 4) throw std::runtime_error("MNN block quantization requires OIHW");
  const int out = dims[0], in = dims[1], kernel = dims[2]*dims[3];
  const int blocks = (in % 32 == 0 && in >= 32) ? in/32 : 1;
  const int block_size = in*kernel/blocks, rows = out*blocks;
  std::vector<uint8_t> result{2};
  const bool int32_dims = rows > 65535 || block_size > 65535;
  for (int dim : {rows, block_size}) {
    const size_t bytes = int32_dims ? 4 : 2;
    const auto* ptr = reinterpret_cast<const uint8_t*>(&dim);
    result.insert(result.end(), ptr, ptr + bytes);
  }
  result.push_back(0);
  for (int value = -128; value <= 127; ++value) result.push_back(static_cast<uint8_t>(value));
  std::vector<float> scales(rows);
  for (int row = 0; row < rows; ++row) {
    float maximum = 0;
    for (int col = 0; col < block_size; ++col) maximum = std::max(maximum, std::abs(weights[row*block_size+col]));
    const float scale = maximum / 127;
    scales[row] = scale;
    for (int col = 0; col < block_size; ++col) {
      const auto quantized = scale > 1e-6f ? static_cast<int>(std::round(weights[row*block_size+col]/scale)) : 0;
      result.push_back(static_cast<uint8_t>(std::clamp(quantized, -128, 127)+128));
    }
  }
  const auto* ptr = reinterpret_cast<const uint8_t*>(scales.data());
  result.insert(result.end(), ptr, ptr+scales.size()*4);
  return result;
}
inline void linkDirectory(const fs::path& source, const fs::path& destination) {
  for (const auto& entry : fs::directory_iterator(source)) {
    const auto name = entry.path().filename().string();
    if (name == "lora_runtime" || name[0] == '.' || name == "cache") continue;
    const auto target = destination / name;
    if (fs::exists(target) || fs::is_symlink(target)) fs::remove_all(target);
    fs::create_symlink(fs::canonical(entry.path()), target);
  }
}
inline std::string prepare(const std::string& model, bool dit) {
  const fs::path base = fs::canonical(model);
  if (!dit) {
    package = base / "lora_runtime";
    const auto variant = package / "variants" / (std::to_string(width)+"x"+std::to_string(height));
    if (fs::is_regular_file(variant / "lora_manifest.json")) package = variant;
    std::ifstream input(package / "lora_manifest.json");
    if (!input) throw std::runtime_error("This model needs a LoRA-compatible package (lora_runtime/lora_manifest.json)");
    input >> manifest;
    if (manifest.at("format_version") != 1) throw std::runtime_error("Unsupported LoRA model package version");
    const auto& base_files = manifest.at("base_files");
    if (!base_files.is_object() || base_files.empty()) throw std::runtime_error("LoRA package is missing base-model fingerprints");
    for (const auto& item : base_files.items()) {
      if (fileHash(relativeFile(base,item.key())) != item.value().get<std::string>()) throw std::runtime_error("LoRA package belongs to a different base model");
    }
    if (manifest.contains("resolution") && manifest.at("resolution") != json::array({width,height})) throw std::runtime_error("LoRA model package does not support this resolution");
  }
  std::string signature = "lora-v1|" + base.string() + request.dump() + manifest.dump();
  for (const auto& entry : fs::directory_iterator(base)) {
    if (entry.is_regular_file()) signature += entry.path().filename().string() + std::to_string(entry.file_size()) + std::to_string(static_cast<long long>(entry.last_write_time().time_since_epoch().count()));
  }
  prepared = fs::path(cache) / hash(signature);
  const bool cached = fs::is_regular_file(prepared / ".ready");
  if (cached) {
    if (!dit) {
      for (const auto& target : manifest.at("targets")) for (auto& source : sources) targetsSource(target,source,true);
      checkMatches();
    }
    return prepared.string();
  }
  const fs::path temporary = prepared.string()+".pending";
  fs::remove_all(temporary); fs::create_directories(temporary);
  try {
    linkDirectory(base,temporary);
    if (!dit) {
      const auto runtime = relativeFile(package, manifest.value("runtime_dir", "runtime"));
      if (fs::is_directory(runtime)) linkDirectory(runtime,temporary);
      std::set<std::string> copied;
      size_t index=0;
      for (const auto& target : manifest.at("targets")) {
        bool used=false;
        for (auto& source : sources) used = targetsSource(target,source,false) || used;
        if (!used) { ++index; continue; }
        auto weights = merged(target);
        if (target.contains("qnn")) {
          std::ofstream output(temporary/tensorFile(index),std::ios::binary);
          output.write(reinterpret_cast<const char*>(weights.data()), weights.size()*sizeof(float));
          if (!output) throw std::runtime_error("Cannot write LoRA preparation cache");
        } else {
          const auto& storage = target.at("storage");
          const auto name = storage.at("file").get<std::string>();
          const auto path = temporary/fs::path(name);
          // Paths are validated before following or replacing any symlink.
          if(fs::path(name).has_parent_path() || name.empty()) throw std::runtime_error("LoRA weight storage must use a root filename");
          const auto source_path = temporary/name;
          if (copied.insert(name).second) {
            const auto original = fs::canonical(source_path);
            fs::remove(path); fs::copy_file(original,path);
          }
          const auto bytes = encodeWeights(storage,target,weights);
          const auto offset = storage.at("offset").get<uint64_t>();
          const auto length = storage.at("length").get<uint64_t>();
          if (bytes.size()!=length || offset > fs::file_size(path) || length > fs::file_size(path)-offset) throw std::runtime_error("LoRA weight layout mismatch");
          std::fstream output(path,std::ios::in|std::ios::out|std::ios::binary);
          output.seekp(offset); output.write(reinterpret_cast<const char*>(bytes.data()),bytes.size());
          if (!output) throw std::runtime_error("Cannot update LoRA cache");
        }
        ++index;
      }
      checkMatches();
    }
    std::ofstream(temporary/".ready") << "1";
    fs::create_directories(prepared.parent_path()); fs::rename(temporary,prepared);
  } catch (...) { fs::remove_all(temporary); throw; }
  return prepared.string();
}
}  // namespace lora
#endif
