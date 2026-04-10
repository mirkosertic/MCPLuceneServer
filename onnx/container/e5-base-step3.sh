#!/bin/bash

# multilingual-e5-base
cd /opt/onnxmodels/e5-base

# Step 3: Quantize to INT8 (ARM64 for Apple Silicon)
# Use --avx2 instead of --arm64 on Intel Mac
# Use --avx512_vnni on modern Intel/AMD Linux servers
echo "Step 3: Quantize to INT8"
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