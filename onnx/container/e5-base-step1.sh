#!/bin/bash

mkdir /opt/onnxmodels

# multilingual-e5-base
cd /opt/onnxmodels
mkdir -p ./e5-base && cd ./e5-base

# Step 1: Export float32 ONNX model from HuggingFace
# --task feature-extraction exports last_hidden_state (no pooling layer)
# This is required for Late Chunking in Java
echo "Step 1: Download e5-base model"
optimum-cli export onnx --model intfloat/multilingual-e5-base --task feature-extraction ./model-float32/
