#!/bin/bash
# PP-OCRv5 ONNX 模型下载脚本
# 用法: bash download_models.sh
set -e

MODEL_DIR="app/src/main/assets/ocr_models"
HF_BASE="https://huggingface.co/monkt/paddleocr-onnx/resolve/main"
PP_BASE="https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/main"

mkdir -p "$MODEL_DIR"

echo "正在下载 OCR 模型文件（约 130MB）..."

download() {
    local name="$1"
    local url="$2"
    local file="$MODEL_DIR/$name"
    if [ -f "$file" ]; then
        echo " [跳过] $name (已存在)"
        return 0
    fi
    echo " [下载] $name..."
    if command -v curl &> /dev/null; then
        curl -L -s -o "$file" "$url"
    else
        wget -q -O "$file" "$url"
    fi
    echo " [完成] $name"
}

download "det.onnx"       "$HF_BASE/detection/v5/det.onnx"
download "en_rec.onnx"    "$HF_BASE/languages/english/rec.onnx"
download "ko_rec.onnx"    "$HF_BASE/languages/korean/rec.onnx"
download "zh_rec.onnx"    "$PP_BASE/inference.onnx"
download "en_dict.txt"    "$HF_BASE/languages/english/dict.txt"
download "ko_dict.txt"    "$HF_BASE/languages/korean/dict.txt"
download "zh_dict.txt"    "$HF_BASE/languages/chinese/dict.txt"
download "en_config.json" "$HF_BASE/languages/english/config.json"
download "ko_config.json" "$HF_BASE/languages/korean/config.json"
download "zh_dict.yml"    "$PP_BASE/inference.yml"

echo ""
echo "下载完成！模型文件位于: $MODEL_DIR"
echo "重新构建 APK 后模型将打包到 assets 中。"
