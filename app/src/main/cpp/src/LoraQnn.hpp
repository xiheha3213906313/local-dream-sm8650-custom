#ifndef LOCAL_DREAM_LORA_QNN_HPP
#define LOCAL_DREAM_LORA_QNN_HPP
#include "LoraPackage.hpp"
#include <QnnInterface.h>

namespace lora {
struct QnnUpdate {
  Qnn_Tensor_t tensor = QNN_TENSOR_INIT;
  std::string name;
  std::vector<uint32_t> dims;
  std::vector<Qnn_ScaleOffset_t> scales;
  std::vector<uint8_t> bytes;
};
inline QnnContext_Buffer_t contextBuffer(void* data, uint64_t size) {
  QnnContext_Buffer_t result{};
  result.version=QNN_CONTEXT_BUFFER_VERSION_1;
  result.v1.memType=QNN_CONTEXTMEMTYPE_RAW;
  result.v1.binaryBuf.data=data; result.v1.binaryBuf.dataSize=size;
  return result;
}
inline void populateQnnUpdate(QnnUpdate& update, const json& target, const fs::path& data) {
  const auto& descriptor=target.at("qnn");
  auto source_shape=target.at("shape").get<std::vector<int>>();
  const size_t count=elements(source_shape);
  std::vector<float> weights(count);
  std::ifstream input(data,std::ios::binary);
  input.read(reinterpret_cast<char*>(weights.data()),count*4);
  if (!input || input.peek()!=std::char_traits<char>::eof()) throw std::runtime_error("LoRA QNN weight cache is incomplete");
  std::vector<int> permutation;
  if (descriptor.contains("permutation")) permutation=descriptor.at("permutation").get<std::vector<int>>();
  else for(size_t i=0;i<source_shape.size();++i) permutation.push_back(i);
  if (permutation.size()!=source_shape.size() || std::set<int>(permutation.begin(),permutation.end()).size()!=source_shape.size()) throw std::runtime_error("Invalid QNN weight permutation");
  for(int axis:permutation) {
    if(axis<0 || axis>=static_cast<int>(source_shape.size())) throw std::runtime_error("Invalid QNN weight permutation axis");
    update.dims.push_back(source_shape[axis]);
  }
  update.name=descriptor.at("name").get<std::string>();
  auto& tensor=update.tensor.v1;
  tensor.id=descriptor.at("id");tensor.name=update.name.c_str();
  tensor.type=QNN_TENSOR_TYPE_UPDATEABLE_STATIC;tensor.dataFormat=QNN_TENSOR_DATA_FORMAT_FLAT_BUFFER;
  tensor.rank=update.dims.size();tensor.dimensions=update.dims.data();tensor.memType=QNN_TENSORMEMTYPE_RAW;
  const std::string dtype=descriptor.at("dtype");
  int bytes=0,minimum=0,maximum=0;
  if(dtype=="F32") {tensor.dataType=QNN_DATATYPE_FLOAT_32;bytes=4;}
  else if(dtype=="F16") {tensor.dataType=QNN_DATATYPE_FLOAT_16;bytes=2;}
  else {
    if(dtype=="UFIXED8") {tensor.dataType=QNN_DATATYPE_UFIXED_POINT_8;bytes=1;maximum=255;}
    else if(dtype=="SFIXED8") {tensor.dataType=QNN_DATATYPE_SFIXED_POINT_8;bytes=1;minimum=-128;maximum=127;}
    else if(dtype=="UFIXED16") {tensor.dataType=QNN_DATATYPE_UFIXED_POINT_16;bytes=2;maximum=65535;}
    else if(dtype=="SFIXED16") {tensor.dataType=QNN_DATATYPE_SFIXED_POINT_16;bytes=2;minimum=-32768;maximum=32767;}
    else throw std::runtime_error("Unsupported QNN LoRA encoding: "+dtype);
    tensor.quantizeParams.encodingDefinition=QNN_DEFINITION_DEFINED;
    if(descriptor.contains("axis")) {
      const int axis=descriptor.at("axis");
      if(axis<0 || axis>=static_cast<int>(update.dims.size())) throw std::runtime_error("Invalid quantization axis");
      for(const auto& entry:descriptor.at("encodings")) update.scales.push_back({entry.at("scale").get<float>(),entry.at("offset").get<int>()});
      if(update.scales.size()!=update.dims[axis]) throw std::runtime_error("Invalid per-axis quantization");
      tensor.quantizeParams.quantizationEncoding=QNN_QUANTIZATION_ENCODING_AXIS_SCALE_OFFSET;
      tensor.quantizeParams.axisScaleOffsetEncoding={axis,static_cast<uint32_t>(update.scales.size()),update.scales.data()};
    } else {
      update.scales.push_back({descriptor.at("scale").get<float>(),descriptor.at("offset").get<int>()});
      tensor.quantizeParams.quantizationEncoding=QNN_QUANTIZATION_ENCODING_SCALE_OFFSET;
      tensor.quantizeParams.scaleOffsetEncoding=update.scales[0];
    }
    for(const auto& scale:update.scales) if(!std::isfinite(scale.scale) || scale.scale<=0) throw std::runtime_error("Invalid quantization scale");
  }
  update.bytes.resize(count*bytes);
  std::vector<size_t> strides(source_shape.size(),1);
  for(int i=static_cast<int>(strides.size())-2;i>=0;--i) strides[i]=strides[i+1]*source_shape[i+1];
  for(size_t i=0;i<count;++i) {
    size_t remainder=i,original=0,channel=0;
    for(int axis=static_cast<int>(update.dims.size())-1;axis>=0;--axis) {
      const size_t coordinate=remainder%update.dims[axis];remainder/=update.dims[axis];
      original+=coordinate*strides[permutation[axis]];
      if(descriptor.contains("axis") && axis==descriptor.at("axis").get<int>()) channel=coordinate;
    }
    const float value=weights[original];
    if(dtype=="F32") std::memcpy(update.bytes.data()+i*4,&value,4);
    else if(dtype=="F16") {const auto half=fp32_to_fp16(value);std::memcpy(update.bytes.data()+i*2,&half,2);}
    else {
      const auto& quant=update.scales[channel];
      const auto integer=static_cast<int32_t>(std::clamp(std::round(static_cast<double>(value)/quant.scale)-quant.offset,static_cast<double>(minimum),static_cast<double>(maximum)));
      std::memcpy(update.bytes.data()+i*bytes,&integer,bytes);
    }
  }
  tensor.clientBuf={update.bytes.data(),static_cast<uint32_t>(update.bytes.size())};
}
template<class Interface> inline void applyQnn(Interface& api,Qnn_ContextHandle_t context,const std::string& binary) {
  if(!manifest.is_object() || !manifest.contains("targets")) return;
  std::vector<QnnUpdate> updates;
  updates.reserve(manifest.at("targets").size());
  size_t index=0;
  for(const auto& target:manifest.at("targets")) {
    const auto data=prepared/tensorFile(index++);
    if(!target.contains("qnn") || target.at("qnn").at("context")!=fs::path(binary).filename().string() || !fs::is_regular_file(data)) continue;
    updates.emplace_back();populateQnnUpdate(updates.back(),target,data);
  }
  if(updates.empty()) return;
  if(!api.contextGetBinarySectionSize || !api.contextGetBinarySection || !api.contextGetBinarySectionUpdate || !api.contextApplyBinarySection || !api.contextFreeBinarySectionUpdate)
    throw std::runtime_error("QNN runtime does not support LoRA binary-section updates");
  Qnn_ContextBinarySize_t size=0,written=0;
  if(api.contextGetBinarySectionSize(context,nullptr,QNN_CONTEXT_SECTION_UPDATABLE,&size)!=QNN_SUCCESS || !size)
    throw std::runtime_error("This QNN model has no updatable weights; install a LoRA-compatible package");
  std::vector<uint8_t> base(size);
  auto original=contextBuffer(base.data(),base.size());
  if(api.contextGetBinarySection(context,nullptr,QNN_CONTEXT_SECTION_UPDATABLE,&original,&written,nullptr,nullptr)!=QNN_SUCCESS || written>size)
    throw std::runtime_error("Cannot extract QNN LoRA base section");
  original.v1.binaryBuf.dataSize=written;
  std::vector<const Qnn_Tensor_t*> pointers;
  for(auto& update:updates) pointers.push_back(&update.tensor);
  QnnContext_Buffer_t section{};
  const auto status=api.contextGetBinarySectionUpdate(&original,nullptr,pointers.data(),pointers.size(),0,nullptr,nullptr,nullptr,&section);
  if(status!=QNN_SUCCESS) throw std::runtime_error("Cannot prepare QNN LoRA update: "+std::to_string(status));
  const auto applied=api.contextApplyBinarySection(context,nullptr,QNN_CONTEXT_SECTION_UPDATABLE,&section,nullptr,nullptr);
  api.contextFreeBinarySectionUpdate(section,nullptr);
  if(applied!=QNN_SUCCESS) throw std::runtime_error("Cannot apply QNN LoRA update: "+std::to_string(applied));
  std::fprintf(stderr,"LORA_QNN applied context=%s tensors=%zu\n",binary.c_str(),updates.size());
}
}  // namespace lora
#endif
