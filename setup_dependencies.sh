#!/usr/bin/env bash
set -euo pipefail

echo "=========================================================="
echo ">>> Настройка нативных зависимостей и библиотек NDK arm64"
echo "=========================================================="

PROJECT_ROOT="$(pwd)"
CPP_DIR="${PROJECT_ROOT}/app/src/main/cpp"
JNILIBS_DIR="${PROJECT_ROOT}/app/src/main/jniLibs/arm64-v8a"
INCLUDE_DIR="${PROJECT_ROOT}/app/src/main/cpp/include"

mkdir -p "${CPP_DIR}"
mkdir -p "${JNILIBS_DIR}"
mkdir -p "${INCLUDE_DIR}/sherpa-onnx/c-api"

# 1. Клонирование llama.cpp (релиз b3800)
if [ ! -d "${CPP_DIR}/llama.cpp" ]; then
    echo ">>> Клонирование репозитория llama.cpp..."
    git clone --depth 1 --branch b3800 https://github.com/ggerganov/llama.cpp.git "${CPP_DIR}/llama.cpp"
else
    echo ">>> llama.cpp уже присутствует в ${CPP_DIR}/llama.cpp"
fi

# 2. Загрузка предсобранных JNI библиотек sherpa-onnx (arm64-v8a)
SHERPA_VERSION="v1.10.30"
SHERPA_TAR="sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_VERSION}/${SHERPA_TAR}"

TEMP_DIR=$(mktemp -d)
echo ">>> Скачивание sherpa-onnx ${SHERPA_VERSION}..."
curl -sSL "${SHERPA_URL}" -o "${TEMP_DIR}/${SHERPA_TAR}"

echo ">>> Распаковка библиотек sherpa-onnx и onnxruntime..."
tar -xjf "${TEMP_DIR}/${SHERPA_TAR}" -C "${TEMP_DIR}"

# Копирование .so библиотек строго для arm64-v8a в папку jniLibs
if [ -d "${TEMP_DIR}/jniLibs/arm64-v8a" ]; then
    cp -fv "${TEMP_DIR}/jniLibs/arm64-v8a/"*.so "${JNILIBS_DIR}/"
else
    find "${TEMP_DIR}" -path "*/arm64-v8a/*" -name "*.so" -exec cp -fv {} "${JNILIBS_DIR}/" \;
fi

# Скачивание c-api.h если нет
if [ ! -f "${INCLUDE_DIR}/sherpa-onnx/c-api/c-api.h" ]; then
    curl -sSL "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/${SHERPA_VERSION}/sherpa-onnx/c-api/c-api.h" \
        -o "${INCLUDE_DIR}/sherpa-onnx/c-api/c-api.h"
fi

rm -rf "${TEMP_DIR}"

echo ">>> Библиотеки arm64-v8a успешно установлены!"
ls -la "${JNILIBS_DIR}"
