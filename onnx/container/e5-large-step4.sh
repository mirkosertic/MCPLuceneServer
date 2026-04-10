#!/bin/bash

# multilingual-e5-base
cd /opt/onnxmodels/e5-large

# Step 4: Copy tokenizer files (required by DJL HuggingFaceTokenizer in Java)
echo "Step 4: Copy tokenizer configuration"
cp ./model-float32/tokenizer.json          ./model-final/
cp ./model-float32/tokenizer_config.json   ./model-final/
cp ./model-float32/special_tokens_map.json ./model-final/

echo "--- Final model files ---"
ls -lh ./model-final/
