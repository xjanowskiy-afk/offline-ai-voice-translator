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

# Простой retry для сетевых операций: 3 попытки с паузой
retry() {
    local attempts=3
    local delay=10
    local n=1
    while true; do
        if "$@"; then
            return 0
        fi
        if [ "$n" -ge "$attempts" ]; then
            echo "ОШИБКА: команда не удалась после $attempts попыток: $*" >&2
            return 1
        fi
        echo "Попытка $n не удалась, повтор через ${delay}с: $*"
        n=$((n + 1))
        sleep "$delay"
    done
}

# 1. Клонирование llama.cpp (релиз b3800)
if [ ! -d "${CPP_DIR}/llama.cpp" ]; then
    echo ">>> Клонирование репозитория llama.cpp..."
    retry git clone --depth 1 --branch b3800 https://github.com/ggerganov/llama.cpp.git "${CPP_DIR}/llama.cpp"
else
    echo ">>> llama.cpp уже присутствует в ${CPP_DIR}/llama.cpp"
fi

# 2. Загрузка предсобранных JNI библиотек sherpa-onnx (arm64-v8a)
SHERPA_VERSION="v1.10.42"
SHERPA_TAR="sherpa-onnx-${SHERPA_VERSION}-android.tar.bz2"
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${SHERPA_VERSION}/${SHERPA_TAR}"
# SHA256 тарбола, вычислен 2026-10-17 скачиванием релизного артефакта целиком.
# Если версия SHERPA_VERSION меняется — пересчитайте хеш и обновите это значение.
SHERPA_TAR_SHA256="6f5a3581efc7ea3815038b41e9c27e757a67c9eea045349ca66468e8f925fd77"

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

echo ">>> Скачивание sherpa-onnx ${SHERPA_VERSION}..."
retry curl -sSL --retry 3 "${SHERPA_URL}" -o "${TEMP_DIR}/${SHERPA_TAR}"

echo ">>> Проверка SHA256 тарбола..."
echo "${SHERPA_TAR_SHA256}  ${TEMP_DIR}/${SHERPA_TAR}" | sha256sum -c -
echo ">>> Хеш совпал."

echo ">>> Распаковка библиотек sherpa-onnx и onnxruntime..."
tar -xjf "${TEMP_DIR}/${SHERPA_TAR}" -C "${TEMP_DIR}"

# Копирование .so библиотек строго для arm64-v8a в папку jniLibs
if [ -d "${TEMP_DIR}/jniLibs/arm64-v8a" ]; then
    cp -fv "${TEMP_DIR}/jniLibs/arm64-v8a/"*.so "${JNILIBS_DIR}/"
else
    find "${TEMP_DIR}" -path "*/arm64-v8a/*" -name "*.so" -exec cp -fv {} "${JNILIBS_DIR}/" \;
fi

# Заголовок c-api.h всегда берём из того же тега, что и пребилд,
# чтобы версия заголовка не расходилась с версией .so библиотек.
echo ">>> Скачивание c-api.h из тега ${SHERPA_VERSION}..."
retry curl -sSL "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/${SHERPA_VERSION}/sherpa-onnx/c-api/c-api.h" \
    -o "${INCLUDE_DIR}/sherpa-onnx/c-api/c-api.h"

echo ">>> Библиотеки arm64-v8a успешно установлены!"
ls -la "${JNILIBS_DIR}"
