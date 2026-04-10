#!/bin/bash

# multilingual-e5-base
cd /opt/onnxmodels/e5-base

# Step 2: Optimize the graph (fuses SkipLayerNorm, BiasGelu subgraphs)
# Note: Attention fusion is not supported for XLM-RoBERTa based models
echo "Step 2: Optimize graph"
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
