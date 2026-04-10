# ONNX Model Export, Optimization & Quantization Guide

This guide explains how to download, optimize, and quantize the `multilingual-e5-large`
and `multilingual-e5-base` embedding models for use with ONNX Runtime in Java.

## Prerequisites

### System Requirements

- macOS 10.15+ (Apple Silicon M1/M2/M3/M4 or Intel)
- Python 3.9+ (system Python or via Homebrew)
- At least 10 GB free disk space (for model downloads and intermediate files)
- Internet connection (for downloading models from HuggingFace)

### Create and Activate a Virtual Environment

```bash
python3 -m venv ~/onnx-export-env
source ~/onnx-export-env/bin/activate
```

### Install Required Libraries

```bash
pip3 install --upgrade pip

# Core libraries for export, optimization and quantization
pip3 install --upgrade --upgrade-strategy eager "optimum[onnx]"
pip3 install sentence-transformers transformers torch
pip3 install accelerate onnx onnxruntime
```

## Directory Structure

Each model gets its own working directory:

```
~/onnx-export/
  e5-base/
    model-float32/        # exported float32 model
    model-final/          # final optimized + quantized model (use this in Java)
  e5-large/
    model-float32/        # exported float32 model
    model-final/          # final optimized + quantized model (use this in Java)
```

## Export, Optimize and Quantize

### multilingual-e5-base (recommended)

Model specs: 12 attention heads, hidden size 768, ~280 MB quantized.

```bash
mkdir -p ~/onnx-export/e5-base && cd ~/onnx-export/e5-base

# Step 1: Export float32 ONNX model from HuggingFace
# --task feature-extraction exports last_hidden_state (no pooling layer)
# This is required for Late Chunking in Java
optimum-cli export onnx \
  --model intfloat/multilingual-e5-base \
  --task feature-extraction \
  ./model-float32/

# Step 2: Optimize the graph (fuses SkipLayerNorm, BiasGelu subgraphs)
# Note: Attention fusion is not supported for XLM-RoBERTa based models
python3 - << 'EOF'
from onnxruntime.transformers import optimizer

opt = optimizer.optimize_model(
    input='./model-float32/model.onnx',
    model_type='bert',
    num_heads=12,       # e5-base: 12 attention heads
    hidden_size=768,    # e5-base: hidden size 768
    use_gpu=False
)

print(f"Applied optimizations: {opt.get_fused_operator_statistics()}")
opt.save_model_to_file('./model-float32/model_optimized.onnx')
print("Optimization done.")
EOF

# Step 3: Quantize to INT8 (ARM64 for Apple Silicon)
# Use --avx2 instead of --arm64 on Intel Mac
# Use --avx512_vnni on modern Intel/AMD Linux servers
python3 - << 'EOF'
from onnxruntime.quantization import quantize_dynamic, QuantType
import onnx
import os

os.makedirs('./model-final', exist_ok=True)

print("Quantizing...")
quantize_dynamic(
    model_input='./model-float32/model_optimized.onnx',
    model_output='./model-final/model_quantized.onnx',
    weight_type=QuantType.QUInt8,
    extra_options={
        'DefaultTensorType': onnx.TensorProto.FLOAT
    }
)

size = os.path.getsize('./model-final/model_quantized.onnx') / (1024**2)
print(f"Done. File size: {size:.0f} MB")
EOF

# Step 4: Copy tokenizer files (required by DJL HuggingFaceTokenizer in Java)
cp ./model-float32/tokenizer.json          ./model-final/
cp ./model-float32/tokenizer_config.json   ./model-final/
cp ./model-float32/special_tokens_map.json ./model-final/

echo "--- Final model files ---"
ls -lh ./model-final/
```

### multilingual-e5-large (higher quality, ~3x slower)

Model specs: 16 attention heads, hidden size 1024, ~580 MB quantized.

> **Note:** This model is ~2.2 GB as float32 which exceeds the ONNX 2 GB protobuf
> limit. The optimizer therefore uses `use_external_data_format=True` which splits
> the model into a `.onnx` graph file and a `.onnx.data` weights file.

```bash
mkdir -p ~/onnx-export/e5-large && cd ~/onnx-export/e5-large

# Step 1: Export float32 ONNX model from HuggingFace
optimum-cli export onnx \
  --model intfloat/multilingual-e5-large \
  --task feature-extraction \
  ./model-float32/

# Step 2: Optimize the graph
# Saves as external data format due to >2 GB model size
python3 - << 'EOF'
from onnxruntime.transformers import optimizer

opt = optimizer.optimize_model(
    input='./model-float32/model.onnx',
    model_type='bert',
    num_heads=16,       # e5-large: 16 attention heads
    hidden_size=1024,   # e5-large: hidden size 1024
    use_gpu=False
)

print(f"Applied optimizations: {opt.get_fused_operator_statistics()}")

# use_external_data_format=True required for models > 2 GB
opt.save_model_to_file(
    './model-float32/model_optimized.onnx',
    use_external_data_format=True
)
print("Optimization done.")
EOF

# Step 3: Quantize to INT8
# The quantizer reads both model_optimized.onnx and model_optimized.onnx.data
# and produces a single model_quantized.onnx (< 2 GB, no external data needed)
python3 - << 'EOF'
from onnxruntime.quantization import quantize_dynamic, QuantType
import onnx
import os

os.makedirs('./model-final', exist_ok=True)

print("Quantizing...")
quantize_dynamic(
    model_input='./model-float32/model_optimized.onnx',
    model_output='./model-final/model_quantized.onnx',
    weight_type=QuantType.QUInt8,
    extra_options={
        'DefaultTensorType': onnx.TensorProto.FLOAT
    }
)

size = os.path.getsize('./model-final/model_quantized.onnx') / (1024**2)
print(f"Done. File size: {size:.0f} MB")
EOF

# Step 4: Copy tokenizer files
cp ./model-float32/tokenizer.json          ./model-final/
cp ./model-float32/tokenizer_config.json   ./model-final/
cp ./model-float32/special_tokens_map.json ./model-final/

echo "--- Final model files ---"
ls -lh ./model-final/
```

## Expected Output

After completing all steps, `model-final/` should contain:

| File                      | Size (approx.)                   |
|---------------------------|----------------------------------|
| `model_quantized.onnx`    | ~280 MB (base) / ~580 MB (large) |
| `tokenizer.json`          | ~2.4 MB                          |
| `tokenizer_config.json`   | ~1 KB                            |
| `special_tokens_map.json` | ~0.3 KB                          |

## Java Integration

Place the contents of `model-final/` in your Java project under
`src/main/resources/onnxmodel/` and load as follows:

```java
// model_quantized.onnx is preferred over model.onnx automatically
// (see resolveModelPath() in ONNXService)
OnnxService service = new OnnxService("/onnxmodel/");
```

### Key parameters per model

| Parameter         | e5-base       | e5-large      |
|-------------------|---------------|---------------|
| `hiddenSize`      | 768           | 1024          |
| `num_heads`       | 12            | 16            |
| Prefix (indexing) | `"passage: "` | `"passage: "` |
| Prefix (search)   | `"query: "`   | `"query: "`   |
| Max tokens        | 512           | 512           |

## Performance Expectations (Apple Silicon M4 Pro)

| Model         | Avg latency (single) | Avg latency (batch 8) |
|---------------|----------------------|-----------------------|
| e5-base INT8  | ~31 ms               | ~5–8 ms per text      |
| e5-large INT8 | ~95 ms               | ~15–20 ms per text    |

## Known Warnings (can be safely ignored)

- `NotOpenSSLWarning` — macOS ships LibreSSL instead of OpenSSL, affects only downloads
- `Context leak detected, CoreAnalytics returned false` — internal Apple CoreML message
  during model initialization, not an error
- `CoreMLExecutionProvider::GetCapability` — CoreML handles ~72% of graph nodes,
  remaining nodes fall back to CPU (expected behavior)
- `maxLength is not explicitly specified` — suppress by passing
  `Map.of("maxLength", "512")` to `HuggingFaceTokenizer.newInstance()`
- Export warning `max diff: 0.0001` — float32 rounding during ONNX serialization,
  irrelevant for cosine similarity search