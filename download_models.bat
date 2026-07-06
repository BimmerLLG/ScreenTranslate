@echo off
REM =====================================================
REM  PP-OCRv5 ONNX 模型下载脚本 (Windows)
REM  下载地址: https://huggingface.co/monkt/paddleocr-onnx
REM  国内镜像: 替换 HF_BASE 为 ModelScope 地址
REM =====================================================

setlocal enabledelayedexpansion

set "MODEL_DIR=app\src\main\assets\ocr_models"
set "HF_BASE=https://huggingface.co/monkt/paddleocr-onnx/resolve/main"
set "PP_BASE=https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx/resolve/main"

if not exist "%MODEL_DIR%" mkdir "%MODEL_DIR%"

echo 正在下载 OCR 模型文件（约 130MB）...

call :download "det.onnx"              "%HF_BASE%/detection/v5/det.onnx"              84000000
call :download "en_rec.onnx"           "%HF_BASE%/languages/english/rec.onnx"          7500000
call :download "ko_rec.onnx"           "%HF_BASE%/languages/korean/rec.onnx"           7500000
call :download "zh_rec.onnx"           "%PP_BASE%/inference.onnx"                     16500000
call :download "en_dict.txt"           "%HF_BASE%/languages/english/dict.txt"          1000
call :download "ko_dict.txt"           "%HF_BASE%/languages/korean/dict.txt"           1000
call :download "zh_dict.txt"           "%HF_BASE%/languages/chinese/dict.txt"          30000
call :download "en_config.json"        "%HF_BASE%/languages/english/config.json"       300
call :download "ko_config.json"        "%HF_BASE%/languages/korean/config.json"        300
call :download "zh_dict.yml"           "%PP_BASE%/inference.yml"                       150000

echo.
echo 下载完成！模型文件位于: %MODEL_DIR%
echo 重新构建 APK 后模型将打包到 assets 中。
pause
exit /b 0

:download
set "FILE=%MODEL_DIR%\%~1"
if exist "%FILE%" (
    echo  [跳过] %~1 (已存在)
    exit /b 0
)
echo  [下载] %~1...
powershell -Command "& {Invoke-WebRequest -Uri '%~2' -OutFile '%FILE%' -UseBasicParsing}"
if %errorlevel% equ 0 (
    echo  [完成] %~1
) else (
    echo  [失败] %~1
)
exit /b 0
