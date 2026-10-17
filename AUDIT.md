# Аудит: Offline AI Voice Translator
Дата: 2026-10-17. Статичний аудит (збірка не запускалась), v1.0.3 / versionCode 4.
Три напрями: Kotlin/Android-шар, C++/JNI-пайплайн, збірка/CI/безпека.
Значення секретів у звіті не наводяться (заредаговано).

## Статус виправлень (2026-10-17)

Весь топ-10 реалізовано в робочій копії `~/workspace/offline-translator-handover/`, закомічено в git.
**Важливе уточнення:** підозра аудиту про розсинхрон API llama.cpp спростовано — перевіркою заголовка `llama.h` з тега `b3800` підтверджено, що `llama_token_is_eog(model, token)` (двохаргументна) і `llama_sampler_chain_default_params()` присутні з точними сигнатурами. Пін `b3800` залишено, код під нього не адаптувався. Build-breaker'а немає.

Що зроблено за пунктами:
1. Секрети вичищено зі скриптів і досьє → env-змінні (`GITHUB_TOKEN`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) з fail-fast; у досьє — `[REDACTED]`.
2. `app/release-key.jks` видалено з копії; `*.jks`/`*.keystore`/`*.apk`/`*.aab`/`jniLibs/` у `.gitignore`; підпис у `build.gradle.kts` — тільки через `KEYSTORE_*` env (fail-fast); у CI кейстор відновлюється з секрету `KEYSTORE_BASE64`. Написано `ROTATION_CHECKLIST.md` з кроками, які має виконати власник (відкликання PAT і токена бота, новий кейстор, `git filter-repo`, секрети GitHub).
3. Нативний глобальний м'ютекс розбито на 4 (vad/asr/llm/tts) зі строгим порядком захвата — захват мікрофона більше не блокується генерацією LLM (full-duplex повернуто).
4. Переклади серіалізовано Kotlin-`Mutex`; стан сесії (generationId через `AtomicLong`, `isTargetGerman`) — локальний; `StreamingVoicePipeline` тримає буфери по generationId; `ViewModel` ігнорує токени чужих генерацій.
5. Гарантований термінальний `onToken(..., isEnd=true)` на всіх шляхах виходу з `translateStreaming`; промпт усікається спереду під `n_ctx=1024`; батч обмежено `LLM_N_CTX`.
6. Аудіомаршрутизація: `AudioTrack.setPreferredDevice()` за режимом (`AudioRoutingManager.resolveOutputDevice()`); неробоча HYBRID-опція прибрана з UI (enum — `@Deprecated`).
7. Онбординг: справжні `RequestPermission`-лаунчери; скачування лише за реальних грантів; кнопки «Повторити» при помилці мережі та провалі `initEngines`.
8. CI: релізи за `versionName` лише на push тегів `v*`; кеш Gradle/NDK; retry мережевих скачувань у `setup_dependencies.sh`; `c-api.h` завжди з тега `v1.10.42`; SHA256-перевірка тарболу sherpa (хеш захардкожено); Actions запінено на SHA комітів.
9. `releaseAll()` викликається з фонового потоку (без ANR); `ExceptionCheck` після `CallVoidMethod`; NULL-перевірки `NewStringUTF`/`NewFloatArray`; перевірка ємності direct-буфера; `DeleteLocalRef` для `jclass`.
10. `AppLogger`: thread-safety + ring-buffer 2000 + `clear()`; `response.close()` на шляху помилки; `callTimeout`; тротлінг прогресу ~5 Гц; canonical-path check (zip slip); `expectedSha256`-маніфест (плейсхолдери — хеші рахуються з реальних файлів); кнопка «Стоп» сервісу + `stopSelf`; `AudioRecord.release()` при винятку `startRecording()`; `extractNativeLibs="false"`.

Не верифіковано збіркою (немає Android SDK у цьому середовищі) — перед релізом прогнати `./gradlew assembleRelease` у CI.

## Оцінки (0–10)

| Область | Бал |
|---|---|
| Гігієна секретів | 1 |
| Підпис релізу | 1 |
| Надійність CI | 5 |
| Безпека supply chain | 3 |
| Гігієна репозиторію | 4 |
| Якість Kotlin-коду | 6 |
| Архітектура (Android) | 6 |
| Стійкість (robustness) | 5 |
| Коректність під Android 14 | 7 |
| Безпека рівня застосунку | 7 |
| JNI-коректність | 7 |
| Безпека потоків (native) | 5 |
| Управління пам'яттю | 7 |
| Обробка помилок (native) | 5 |
| Конфігурація збірки | 8 |

**Загальний вердикт:** акуратний прототип із правильною JNI-границею, виправленим стартовим крашем і грамотною роботою з Android 14. Але: (1) ланцюжок довіри зламано через секрети у відкритому вигляді та ключ підпису в репо; (2) архітектура реального часу зламана глобальним м'ютексом — мікрофон глохне на час перекладу; (3) гонки при паралельних перекладах; (4) аудіомаршрутизація не працює як задумано.

## Критичні знахідки

### 1. Секрети у відкритому вигляді — CRITICAL
- GitHub PAT (`ghp_…`, classic) захардкожено в 6 скриптах + досьє: `monitor_and_download.ps1:5`, `check_release.ps1:2`, `check_runs.ps1:2`, `check_so_files.ps1:1`, `get_errors.ps1:1`, `inspect_setup.ps1:1`, `PROJECT_HANDOVER_DOSSIER.md` (розд. 3). Blast radius: API GitHub від імені власника; токен уже «подорожував» скриптами, досьє та ZIP через Telegram.
- Токен Telegram-бота захардкожено в 6 скриптах + досьє: `send_tg.py:5`, `send_tg_file.py:4`, `send_tg_zip.py:4`, `send_handover_tg.py:4`, `send_to_telegram.ps1:6`, `get_tg_chat_id.ps1:1`, досьє (розд. 4). Поруч `chat_id`. Blast radius: повний контроль над `@apktransoffline_bot`, читання історії через `getUpdates`.
- Пароль кейстора захардкожено в `app/build.gradle.kts:55,57` (слабкий, storePassword == keyPassword).

### 2. Ключ підпису релізу закомічено в репозиторій — CRITICAL
- `app/release-key.jks` лежить у git (`.gitignore` його не виключає — збігів `jks`/`keystore` немає; CI підписує саме ним: `app/build.gradle.kts:52–57,68`).
- Ризик: Android довіряє парі (package name + підпис). Будь-хто з read-доступом до репо може зібрати шкідливий APK з `applicationId = "com.translator.offline"`, підписати тим самим ключем — телефон прийме його як легітимне оновлення. Повний компроміс ланцюжка доставки.
- Виправлення: згенерувати новий кейстор локально, старий видалити з репо та історії, `*.jks`/`*.keystore` у `.gitignore`; у CI — кейстор як base64 GitHub Secret, паролі через `System.getenv()`. Ціна: встановлені копії v1.0.3 не оновляться «поверх», потрібна переінсталяція.

### 3. Глобальний м'ютекс ламає реальний час — CRITICAL
- `translateStreaming` тримає `g_pipeline.mtx` (`native-pipeline.cpp:251`) весь цикл генерації (до 256 токенів ≈ 10–20 с на телефоні). Потік захвата (`AudioCaptureEngine.runCaptureLoop`, Dispatchers.IO) блокується в `processVadChunk`/`feedAudioToAsr` → `AudioRecord.read` не викликається → переповнення внутрішнього буфера → **звук під час перекладу втрачається**.
- Той самий м'ютекс блокує `synthesizeSpeechChunkByLang` (`:403`): озвучка можлива лише після повного завершення генерації. Конвеєр строго послідовний «слухай → перекладай → говори» — всупереч ідеї simultaneous-перекладача.
- `releaseAll` (`:429–460`) викликається з `Service.onDestroy` на головному потоці: якщо йде генерація — ANR.
- Виправлення: розбити м'ютекс за движками (vad / asr / llm / tts); `releaseAll` викликати з фонового потоку (або `try_lock` з таймаутом).

### 4. Гонка паралельних перекладів — HIGH
- `TranslatorForegroundService.kt:117` мутує `voicePipeline?.isTargetGerman` на кожен сегмент, `:139–140` запускає `translateStreaming` у корутині на кожен сегмент. У full-duplex режимах другий сегмент може стартувати, поки йде перший → спільний `currentSentenceBuffer` (`StreamingVoicePipeline.kt:131–150`) і перемикання `isTargetGerman`: перемішані речення, озвучка не тим голосом; `ViewModel.onTranslationToken` дописує токени «протухлого» перекладу в поточне повідомлення.
- Виправлення: один `Mutex`/актор на конвеєр «сегмент → переклад → TTS»; `isTargetGerman` і буфер речення — локальні для сесії перекладу.

### 5. Фейкові гранти дозволів на онбордингу — HIGH
- `ui/OnboardingSetupScreen.kt:98,107`: кнопки «Увімкнути» для мікрофона/сповіщень просто ставлять `hasAudio = true` / `hasNotif = true` без системного запиту. `LaunchedEffect(allPermsGranted)` (`:45`) після цього стартує скачування 1.6 ГБ. Користувач думає, що дозвіл видано — насправді ні.
- Виправлення: викликати реальні `audioLauncher`/`notifLauncher`, як у `PermissionsGuardScreen`.

### 6. Аудіомаршрутизація не працює як задумано — HIGH
- TTS-трек створено з `USAGE_MEDIA` (`StreamingVoicePipeline.kt:44`), а `AudioRoutingManager` перемикає маршрути через `setCommunicationDevice()` (`AudioRoutingManager.kt:74,90`) — він впливає лише на voice-communication стрім. Наслідки: у режимі «На столі» з підключеним BT A2DP озвучка піде в навушники, а не в динамік; split-режим тримається на системному роутингу за замовчуванням. Режим `HYBRID_SPEAKER_AND_EARBUD` рекламується в UI, але гілка `when` порожня (`AudioRoutingManager.kt:106–107`) — опція нічого не робить.
- Виправлення: `AudioTrack.setPreferredDevice()` під обраний режим (або перевести трек на `USAGE_VOICE_COMMUNICATION`); реалізувати або прибрати HYBRID-гілку.

### 7. Тихий провал генерації — HIGH
- Промпт довший за `n_ctx` (1024) → `llama_decode` повертає помилку → `return` без термінального `onToken(..., isEnd=true)`. Kotlin-сторона ніколи не дізнається про кінець → UI зависає у стані «перекладаю». Валідації/усічення промпта немає.
- Виправлення: валідувати/усікати токени промпта під `n_ctx` до декоду; слати термінальний колбэк при будь-якій помилці генерації.

## Інші знахідки (HIGH / MEDIUM)

- **CI: захардкожено реліз `v1.0.0`** (`.github/workflows/build-apk.yml:57–58`) при `versionName = "1.0.3"`: кожен push у `main` перезаписує один реліз; скрипти доставки посилаються на `releases/download/v1.0.0/…`. Виправлення: тег/ім'я з `versionName`, реліз лише на push тегів `v*`.
- **Підозра на розсинхрон llama.cpp API**: `native-pipeline.cpp:306` використовує `llama_token_is_eog(model, token)` (двохаргументна форма), `:293` — `llama_sampler_chain_default_params()`, а `setup_dependencies.sh:20` пінить `llama.cpp` на гілку `b3800` (жовтень 2024). API сімейства `llama_token_is_eog` з вказівником на vocab з'явилось пізніше (рефакторинг vocab, грудень 2024+) — потенційний build-breaker компіляції C++.
- **AppLogger не потокобезпечний і необмежений** (`ui/DebugConsoleSheet.kt:33–37`): `SimpleDateFormat` з IO/Default/Main потоків; неатомарне читання-модифікація-запис; список росте безмежно з O(n²) копіюванням. Виправлення: синхронізація/`Mutex`, thread-safe форматування, ring-buffer (~2000 записів).
- **Логи містять розпізнану мову** + кнопка «Скопіювати все» — вміст розмов одним тапом у буфер обміну; `TranslatorApp.kt:23` пише `crash_log.txt` у plaintext безстроково.
- **Немає шляхів відновлення після збоїв**: помилка скачування лише текстом, кнопки «Повторити» немає (`OnboardingSetupScreen.kt:175–177`); неуспішний `initEngines` → вічний «ЗАГРУЗКА AI...»; `NativeBridge.loadLibrariesSafely()` кешує помилку — повтор неможливий без рестарту процесу.
- **Витік OkHttp-з'єднання** на шляху помилки (`ModelSetupManager.kt:245–248`): `return@withContext` без закриття `response`.
- **Витік колбэків Activity** у довгоживучому сервісі: `MainActivity.kt:44–51` ставить колбеки, при `unbindService` (`:212–215`) не скидає.
- **Прогрес скачування смикає UI на кожен 64 КБ чанк** (`ModelSetupManager.kt:279`): сотні рекомпозицій/с — тротлити до ~5 Гц.
- **Zip Slip** у розпакуванні (`ModelSetupManager.kt:337,357`): `File(modelsDir, entry.name)` без canonical-path перевірки. Джерело довірене, але додати перевірку.
- **Немає перевірки цілісності моделей** — лише нижня межа розміру (`minExpectedBytes`); для «100% офлайн і приватність» — додати SHA-256 у маніфест задач.
- **Сервіс ніколи сам не зупиняється** (`stopSelf` немає): після згортання висить foreground-нотифікація вічно; кнопки «зупинити сервіс» немає.
- Немає `ExceptionCheck` після `CallVoidMethod` у циклі генерації (`native-pipeline.cpp:308,318`); `NewStringUTF`/`NewFloatArray` без перевірки NULL (`:34,177,180,182,405,413,423`); `batch.logits[batch.n_tokens - 1]` при 0 токенів — латентний OOB (`:286`); батч 2048 при `n_ctx=1024` — теоретичний heap-OOB.
- `-ffast-math` глобально (включно з ggml/llama.cpp) — для Android звичайна практика, ризик лише числового дрейфу.
- `extractNativeLibs="true"` + `jniLibs.useLegacyPackaging = true` — `.so` несжаті в APK і дублюються при встановленні (подвійне місце); причина 52 МБ APK і потреби зіпувати для Telegram (ліміт Bot API 50 МБ).

## Що добре
- Усі 14 JNI-сигнатур збігаються з `NativeBridge.kt`; `GetStringUTFChars`/`ReleaseStringUTFChars` парні; подвійна ініціалізація та `releaseAll` не течуть.
- LLM 1.1 ГБ через mmap, KV-кеш ~30 МБ — на Fold 4 (12 ГБ) прийнятно.
- Android 14: `foregroundServiceType="microphone"` + рантайм-пермішен `FOREGROUND_SERVICE_MICROPHONE`, передача типу в `startForeground` лише за наявності `RECORD_AUDIO`; `POST_NOTIFICATIONS` рантайм; `PendingIntent.FLAG_IMMUTABLE`; `configChanges` гасить перестворення при розкладанні Fold.
- Маніфест акуратний: сервіс і `CrashAndLogActivity` — `exported=false`; `allowBackup=false`; моделі в `getExternalFilesDir("models")` (scoped storage).
- Даунлоадер уміє докачку по `Range`, обробляє 416; crash-екран в ізольованому `:crash_handler` процесі.

## Топ-10 виправлень за пріоритетом
1. Відкликати GitHub PAT і токен Telegram-бота (`@BotFather` → `/revoke`), випустити нові з мінімальними scopes; вичистити секрети з git-історії (`git filter-repo`/BFG); нові значення — лише в env/секрети CI.
2. Перегенерувати ключ підпису, прибрати `.jks` з репо та історії, `*.jks`/`*.keystore` у `.gitignore`; кейстор у CI як base64 Secret, паролі через `System.getenv()`.
3. Розбити глобальний нативний м'ютекс за движками (vad/asr/llm/tts) — повертає full-duplex: мікрофон не глохне, TTS може йти поверх генерації.
4. Сериалізувати переклади на Kotlin-стороні (Mutex/актор, локальний стан сесії).
5. Термінальний `onToken(..., isEnd=true)` при будь-якій помилці генерації + валідація/усічення промпта під `n_ctx`.
6. Починити аудіомаршрутизацію (`setPreferredDevice` або `USAGE_VOICE_COMMUNICATION`); реалізувати/прибрати HYBRID-гілку.
7. Реальні запити дозволів на онбордингу; кнопки «Повторити» при помилках скачування/ініціалізації.
8. CI: релізи за `versionName` на push тегів `v*`; кешування Gradle/NDK/llama.cpp; retry мережевих скачувань; SHA256-перевірка `sherpa-onnx-*.tar.bz2`; `c-api.h` завжди з того ж тега, що пребілд; Actions пінити на SHA; підтвердити пін llama.cpp під використані API.
9. `releaseAll` з фонового потоку (без ANR); `ExceptionCheck` після `CallVoidMethod`; NULL-перевірки `NewStringUTF`/`NewFloatArray`; кламп батча під `n_ctx`.
10. AppLogger: thread-safety + ring-buffer; закриття OkHttp `response`; canonical-path check (zip slip); SHA-256 моделей у маніфесті; тротлінг прогресу; `stopSelf`/кнопка зупинки сервісу; `extractNativeLibs="false"` для економії місця.

Головний висновок: код і CI технічно робочі, але **довіра до ланцюжка доставки зламана** (п.1–2 блокують усе інше), а **головна обіцянка продукту — переклад у реальному часі — зламана архітектурою синхронізації** (п.3).
