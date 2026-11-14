#ifndef LOCAL_DREAM_LORA_SUPPORT_HPP
#define LOCAL_DREAM_LORA_SUPPORT_HPP

// Included only by the separately built LoRA core. The shipped legacy binary
// and its request, model and prompt-cache paths remain unchanged.
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <memory>
#include <regex>
#include <set>
#include <sstream>
#include "SafeTensorReader.hpp"
#include "Sha256.hpp"

namespace lora {
namespace fs = std::filesystem;
using json = nlohmann::json;
struct Source {
  std::string id, path;
  float weight;
  std::unique_ptr<SafeTensorReader> reader;
  std::set<std::string> matched;
};
inline std::vector<Source> sources;
inline json request = json::array(), manifest;
inline fs::path package, prepared;
inline std::string config_path, root, cache;
inline int width = 512, height = 512;

inline fs::path relativeFile(const fs::path& dir, const std::string& name) {
  fs::path part(name);
  if (part.empty() || part.is_absolute()) throw std::runtime_error("Invalid LoRA package path");
  for (const auto& item : part) if (item == "..") throw std::runtime_error("Invalid LoRA package path");
  auto result = fs::weakly_canonical(dir / part);
  const auto base = fs::weakly_canonical(dir).string() + "/";
  if (result.string().compare(0, base.size(), base) != 0) throw std::runtime_error("LoRA package path escapes its directory");
  return result;
}
inline std::string hash(const std::string& text) {
  Sha256 digest; digest.update(text);
  std::ostringstream out;
  for (auto byte : digest.finalize()) out << std::hex << std::setw(2) << std::setfill('0') << int(byte);
  return out.str();
}
inline std::string fileHash(const fs::path& path) {
  std::ifstream input(path, std::ios::binary);
  if (!input) throw std::runtime_error("Cannot read LoRA base: " + path.string());
  Sha256 digest; std::vector<char> block(256 * 1024);
  while (input) { input.read(block.data(), block.size()); digest.update(reinterpret_cast<const uint8_t*>(block.data()), input.gcount()); }
  std::ostringstream out;
  for (auto byte : digest.finalize()) out << std::hex << std::setw(2) << std::setfill('0') << int(byte);
  return out.str();
}
inline void readSources() {
  std::ifstream input(config_path);
  input >> request;
  if (!request.is_array() || request.empty() || request.size() > 3) throw std::runtime_error("LoRA core requires 1 to 3 LoRAs");
  std::set<std::string> seen;
  for (const auto& item : request) {
    Source source;
    source.id = item.at("id").get<std::string>(); source.weight = item.at("weight").get<float>();
    if (!std::regex_match(source.id, std::regex("[0-9a-f]{64}")) || !seen.insert(source.id).second ||
        !std::isfinite(source.weight) || source.weight == 0 || std::abs(source.weight) > 2)
      throw std::runtime_error("Invalid LoRA selection");
    source.path = relativeFile(root, source.id + ".safetensors").string();
    source.reader = std::make_unique<SafeTensorReader>(source.path);
    sources.push_back(std::move(source));
  }
}
inline void stripArguments(int& argc, char** argv) {
  int next = 1;
  for (int i = 1; i < argc; ++i) {
    const std::string key = argv[i];
    if (key == "--lora-config" || key == "--lora-dir" || key == "--lora-cache" || key == "--lora-width" || key == "--lora-height") {
      if (++i == argc) throw std::runtime_error("Missing LoRA argument");
      if (key == "--lora-config") config_path = argv[i];
      else if (key == "--lora-dir") root = argv[i];
      else if(key == "--lora-width") width=std::stoi(argv[i]);
      else if(key == "--lora-height") height=std::stoi(argv[i]);
      else cache = argv[i];
    } else argv[next++] = argv[i];
  }
  argc = next; argv[next] = nullptr;
  if (config_path.empty() || root.empty() || cache.empty()) throw std::runtime_error("Missing LoRA invocation");
  readSources();
}
inline std::string pairUp(const std::string& name) {
  for (auto replacement : {std::pair<std::string,std::string>{".lora_down.weight", ".lora_up.weight"}, {".lora_A.weight", ".lora_B.weight"}, {".lora_A.default.weight", ".lora_B.default.weight"}}) {
    if (name.size() >= replacement.first.size() && name.compare(name.size()-replacement.first.size(), replacement.first.size(), replacement.first) == 0)
      return name.substr(0, name.size()-replacement.first.size()) + replacement.second;
  }
  return "";
}
inline size_t elements(const std::vector<int>& dims) {
  size_t total = 1;
  for (int dim : dims) {
    if (dim <= 0 || total > (1ULL << 30) / static_cast<size_t>(dim)) throw std::runtime_error("Invalid LoRA tensor dimensions");
    total *= dim;
  }
  return total;
}
inline std::vector<float> merged(const json& target) {
  const auto shape = target.at("shape").get<std::vector<int>>();
  if (shape.size() < 2) throw std::runtime_error("LoRA target must be a matrix or convolution");
  const size_t count = elements(shape), out = shape[0], in = count / out;
  SafeTensorReader base(relativeFile(package, target.value("base_file", "base.safetensors")).string());
  base.read(target.at("base_tensor"));
  auto result = std::move(base.data);
  if (result.size() != count) throw std::runtime_error("LoRA base shape mismatch");
  for (auto& source : sources) {
    for (const auto& alias : target.at("aliases")) {
      const std::string prefix = alias.get<std::string>();
      std::string down;
      for (const auto& suffix : {".lora_down.weight", ".lora_A.weight", ".lora_A.default.weight"})
        if (source.reader->has_tensor(prefix + suffix)) { down = prefix + suffix; break; }
      if (down.empty()) continue;
      if (!source.matched.insert(down).second) throw std::runtime_error("Ambiguous LoRA tensor mapping: " + down);
      const auto up = pairUp(down);
      const auto a_shape = source.reader->get_tensor_shape(down), b_shape = source.reader->get_tensor_shape(up);
      if (a_shape.empty() || b_shape.empty() || a_shape[0] <= 0 || b_shape[0] != static_cast<int>(out)) throw std::runtime_error("LoRA target shape mismatch: " + down);
      const size_t rank = a_shape[0];
      // Conv down kernels are flattened in OIHW order; up must be 1x1.
      if (elements(a_shape) != rank*in || elements(b_shape) != out*rank) throw std::runtime_error("Unsupported LoRA convolution or rank: " + down);
      source.reader->read(down); auto a = std::move(source.reader->data);
      source.reader->read(up); auto b = std::move(source.reader->data);
      float alpha = static_cast<float>(rank);
      if (source.reader->has_tensor(prefix + ".alpha")) { source.reader->read(prefix + ".alpha"); if (source.reader->data.size() != 1) throw std::runtime_error("Invalid LoRA alpha"); alpha = source.reader->data[0]; }
      const float scale = source.weight * alpha / rank;
      if (!std::isfinite(scale)) throw std::runtime_error("Invalid LoRA multiplier");
      for (size_t row = 0; row < out; ++row) {
        for (size_t r = 0; r < rank; ++r) {
          const float value = b[row*rank+r] * scale;
          for (size_t col = 0; col < in; ++col) result[row*in+col] += value * a[r*in+col];
        }
      }
      break;
    }
  }
  if (!std::all_of(result.begin(), result.end(), [](float v) { return std::isfinite(v); })) throw std::runtime_error("LoRA produced non-finite weights");
  return result;
}
inline void checkMatches() {
  for (const auto& source : sources) {
    size_t pairs = 0;
    for (const auto& key : source.reader->get_tensor_names()) {
      const auto up = pairUp(key);
      if (up.empty()) continue;
      if (!source.reader->has_tensor(up) || !source.matched.count(key)) throw std::runtime_error("LoRA is incompatible with this model: " + key);
      ++pairs;
    }
    if (!pairs) throw std::runtime_error("File contains no standard LoRA tensors");
  }
}
inline void validateRequest(const json& body) {
  const auto list = body.value("loras", json::array());
  if (!list.is_array() || list.size() != request.size()) throw std::runtime_error("LoRA request does not match the active model");
  for (size_t i = 0; i < list.size(); ++i)
    if (list[i].at("id") != request[i].at("id") || list[i].at("weight").get<float>() != request[i].at("weight").get<float>())
      throw std::runtime_error("LoRA request changed; reload the LoRA backend");
}
}  // namespace lora
#endif
