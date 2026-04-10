#!/bin/bash

# multilingual-e5-large
cd /opt/onnxmodels
mkdir -p ./e5-large && cd ./e5-large

# Step 1: Export float32 ONNX model from HuggingFace
# --task feature-extraction exports last_hidden_state (no pooling layer)
# This is required for Late Chunking in Java
echo "Step 1: Download e5-large model"
optimum-cli export onnx --model intfloat/multilingual-e5-large --task feature-extraction ./model-float32/
