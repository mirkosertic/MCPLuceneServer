#!/bin/bash

# multilingual-e5-large
cd /opt/onnxmodels/e5-large

# Step 2: Optimize the graph (fuses SkipLayerNorm, BiasGelu subgraphs)
# Note: Attention fusion is not supported for XLM-RoBERTa based models
echo "Step 2: Optimize graph"
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