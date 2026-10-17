# Offline AI Voice Translator — Project Handover & Architecture Dossier

Этот документ подготовлен для инженера / AI-агента, который продолжит разработку проекта. Здесь собраны все технические детали, архитектура, учетные данные, CI/CD, выявленные проблемы и текущее состояние.

---

## 1. О проекте
- **Название:** Offline AI Voice Translator (100% автономный переводчик речи).
- **Целевая платформа:** Android 14 / One UI 6 (основное целевое устройство — **Samsung Galaxy Z Fold 4**, SM-F936B, Snapdragon 8+ Gen 1, 12GB RAM, ABI `arm64-v8a`).
- **Языковая пара:** Русский $\leftrightarrow$ Немецкий (с возможностью расширения).
- **Главная цель:** 100% оффлайн-работа всех компонентов прямо на процессоре/NPU телефона (без отправки звука на серверы).

---

## 2. Архитектура и стек технологий

```
Микрофон (AudioCaptureEngine)
        │ 16 kHz PCM
        ▼
Silero VAD (C++ JNI / onnxruntime) ──► Детекция начала/конца речи
        │
        ▼
Sherpa-ONNX Zipformer ASR (C++ JNI) ──► Потоковое распознавание речи в текст
        │ Текст
        ▼
Qwen 2.5 1.5B Instruct GGUF (llama.cpp) ──► Перевод текста (Prompt-guided translation)
        │ Токены перевода
        ▼
Piper VITS TTS (C++ JNI / espeak-ng) ──► Синтез речи RU (Irina) / DE (Thorsten)
        │ PCM 22050 Hz
        ▼
StreamingVoicePipeline & AudioTrack ──► Воспроизведение (динамик / раздельные Bluetooth-наушники)
```

### Модули в исходном коде:
- **`app/src/main/cpp/native-pipeline.cpp`**: C++ пайплайн, объединяющий Sherpa-ONNX (VAD + ASR + Piper TTS) и llama.cpp.
- **`app/src/main/java/com/translator/offline/NativeBridge.kt`**: JNI-мост между Kotlin и C++. Библиотеки загружаются динамически (`loadLibrariesSafely()`).
- **`app/src/main/java/com/translator/offline/downloader/ModelSetupManager.kt`**: Менеджер онбординга. Скачивает и распаковывает модели в память телефона (`getExternalFilesDir("models")`), если их нет:
  1. `silero_vad.onnx` (~640 КБ)
  2. `encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt` (~350 МБ)
  3. `qwen2.5-1.5b-instruct-q4_k_m.gguf` (~1.1 ГБ)
  4. `de_DE-thorsten-high.onnx` + `.json` (~114 МБ)
  5. `ru_RU-irina-medium.onnx` + `.json` (~63 МБ)
  6. `espeak-ng-data.tar.bz2` (распаковывается в папку `espeak-ng-data/`)
- **`app/src/main/java/com/translator/offline/service/TranslatorForegroundService.kt`**: Непрерывный сервис захвата аудио в фоне.
- **`app/src/main/java/com/translator/offline/audio/AudioRoutingManager.kt`**: Управление маршрутизацией звука: режим динамика, Solo Prompter, Hybrid или Split Earbuds (L/R каналы).
- **`app/src/main/java/com/translator/offline/ui/FoldableSplitLayout.kt`**: Адаптивный режим под складной экран Samsung Galaxy Z Fold 4 (Tabletop Flex Mode под 90°).
- **`app/src/main/java/com/translator/offline/CrashAndLogActivity.kt`**: Изолированный аварийный экран ошибок в отдельном процессе `:crash_handler` с кнопкой копирования лога в буфер обмена.

---

## 3. Репозиторий и CI/CD (GitHub Actions)

> ⚠️ **Ротація секретів обов'язкова:** значення нижче видалено з документа — старі секрети скомпрометовано (вони були у відкритому вигляді в скриптах і архівах). Відкличіть їх та перевипустіть за чек-листом `ROTATION_CHECKLIST.md`.

- **Репозиторий GitHub:** `https://github.com/xjanowskiy-afk/offline-ai-voice-translator.git`
- **Ветка:** `main`
- **Сборщик APK:** GitHub Actions workflow `.github/workflows/build-apk.yml`.
  - При push тега `v*` (напр. `v1.0.3`) GitHub Actions автоматически собирает релизный APK (`gradlew assembleRelease`), подписывает ключом из секрета `KEYSTORE_BASE64` и выкладывает в Releases.
- **Токен для работы с GitHub:**
  - `[REDACTED — відкликати та перевипустити]` (использовался в локальных скриптах мониторинга).

---

## 4. Telegram-бот для отправки APK клиенту

> ⚠️ **Ротація секретів обов'язкова:** значення нижче видалено з документа — старі секрети скомпрометовано (вони були у відкритому вигляді в скриптах і архівах). Відкличіть їх та перевипустіть за чек-листом `ROTATION_CHECKLIST.md`.

Для прямой отправки обновлений в Telegram настроен бот:
- **Имя бота:** `@apktransoffline_bot`
- **Ссылка:** `https://t.me/apktransoffline_bot`
- **Токен бота:** `[REDACTED — відкликати та перевипустити]`
- **Chat ID пользователя (Roman):** `[REDACTED — відкликати та перевипустити]`
- **Скрипты отправки:**
  - `send_tg.py` — отправка форматированного сообщения с прямой ссылкой на скачивание.
  - `send_tg_zip.py` — отправка zip-архива с APK прямо документом в чат (так как Telegram Bot API не принимает файлы > 50 МБ, исходный APK в 52 МБ сжимается в ZIP 21 МБ и успешно доставляется).

---

## 5. Текущий статус и что было сделано в последних коммитах

1. **Версия приложения:** `versionCode = 4`, `versionName = "1.0.3"`.
2. **Проблема с падением на старте:**
   - Ранее приложение моментально схлопывалось при клике на иконку.
   - Были исправлены:
     - Создана тема приложения `res/values/themes.xml` (`Theme.OfflineTranslator`), так как Jetpack Compose на Android 14 падал без валидной темы.
     - Загрузка JNI-библиотек (`System.loadLibrary`) переведена из статического блока `init` в отложенный `loadLibrariesSafely()`, предотвращая краш процесса ядра Linux при запуске Activity.
     - Добавлен независимый View-fallback в `CrashAndLogActivity` и отдельный процесс `android:process=":crash_handler"` в манифесте.
     - Добавлены проверки разрешений и безопасные обертки в `WindowInfoTracker` и `AudioRoutingManager`.
3. **Последний собранный APK:**
   - Файл `app-release.apk` (v1.0.3) отправлен пользователю в Telegram в архиве `Offline_Translator_v1.0.3.zip`.

---

## 6. Что делать следующему агенту / инженеру:
1. Дождаться обратной связи от пользователя по запуску v1.0.3.
2. Если открылся экран разрешений / онбординга — проверить процесс загрузки моделей через `ModelSetupManager`.
3. Если приложение показало `CrashAndLogActivity`, скопировать лог ошибки от пользователя и устранить конкретный stack trace.
4. Для автоматической доставки новых сборок запускать `python send_tg_zip.py` или отправлять ссылку через `send_tg.py`.
