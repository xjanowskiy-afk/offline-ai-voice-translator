# Чек-лист ротації секретів — Offline AI Voice Translator

> ⚠️ **Чому просто видалити файли недостатньо:** старі секрети вже були у відкритому вигляді в скриптах, у досьє та в ZIP-архівах, які відправлялись через Telegram. Видалення з поточного коду не анулює скомпрометовані значення — їх **обов'язково відкликати**.

Усі кроки нижче виконуються вручну власником акаунтів. Muse нічого з цього не робить автоматично.

---

## 1. Відкликати GitHub PAT

1. Відкрити https://github.com/settings/tokens (Settings → Developer settings → Personal access tokens).
2. Знайти токен, що починається на `ghp_…` (той, що був у скриптах) → **Revoke**.
3. Створити новий токен. Рекомендовано **fine-grained**:
   - Repository access → Only select repositories → `xjanowskiy-afk/offline-ai-voice-translator`
   - Permissions → Repository permissions: `Actions` (read), `Contents` (read), `Metadata` (read)
4. Новий токен **нікуди не вставляти в код**. Скрипти тепер читають його з env-змінної:
   ```powershell
   # Windows PowerShell (на час сесії)
   $env:GITHUB_TOKEN = "<новий токен>"
   # або назавжди для користувача:
   [Environment]::SetEnvironmentVariable("GITHUB_TOKEN", "<новий токен>", "User")
   ```

## 2. Відкликати токен Telegram-бота

1. Відкрити чат з **@BotFather** у Telegram.
2. Надіслати `/revoke`, обрати `@apktransoffline_bot`, підтвердити.
3. BotFather видасть **новий токен** — зберегти його.
4. Встановити env-змінні там, де запускаються скрипти відправки:
   ```powershell
   $env:TELEGRAM_BOT_TOKEN = "<новий токен бота>"
   $env:TELEGRAM_CHAT_ID = "<ваш chat id>"
   ```
   Скрипти `send_tg*.py`, `send_to_telegram.ps1`, `get_tg_chat_id.ps1` тепер читають значення з цих змінних і завершуються з помилкою, якщо вони не задані.

## 3. Замінити ключ підпису релізу

Старий `release-key.jks` скомпрометовано (лежав у git + пароль у коді). Використовувати його далі **не можна**.

### 3.1. Згенерувати новий кейстор локально
```bash
keytool -genkeypair -v \
  -keystore release-key.jks \
  -alias translator \
  -keyalg RSA -keysize 4096 \
  -validity 9125 \
  -storepass "<НОВИЙ_НАДІЙНИЙ_ПАРОЛЬ>" \
  -keypass "<НОВИЙ_НАДІЙНИЙ_ПАРОЛЬ>"
```
Файл `release-key.jks` **не комітити** — він уже в `.gitignore`. Паролі не записувати в код.

### 3.2. Видалити старий кейстор з репозиторію та з історії git
```bash
cd <клон репозиторію>

# Варіант A (рекомендовано): git filter-repo
pip install git-filter-repo
git filter-repo --path app/release-key.jks --invert-paths --force

# Варіант B: BFG Repo-Cleaner
bfg --delete-files release-key.jks
git reflog expire --expire=now --all && git gc --prune=now --aggressive

# Після будь-якого варіанта — примусовий push:
git push origin --force --all
git push origin --force --tags
```
> Увага: після цього всім, хто має клон репозиторію, треба переклонувати його.

### 3.3. Додати секрети в GitHub Actions
Repo → Settings → Secrets and variables → Actions → New repository secret:
- `KEYSTORE_BASE64` — вміст нового кейстора в base64:
  ```bash
  base64 -w0 release-key.jks   # Linux
  # PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("release-key.jks"))
  ```
- `KEYSTORE_PASSWORD` — пароль сховища
- `KEY_ALIAS` — аліас ключа (напр. `translator`)
- `KEY_PASSWORD` — пароль ключа

Workflow `.github/workflows/build-apk.yml` уже налаштовано: відновлює кейстор з `KEYSTORE_BASE64` у `$RUNNER_TEMP` і передає паролі через `System.getenv()`.

### 3.4. ⚠️ Наслідок зміни ключа підпису
Встановлені копії **v1.0.3 не оновляться «поверх»** — Android відмовиться ставити APK з іншим підписом як оновлення. На телефоні треба **видалити стару версію і встановити нову вручну**. Це ціна скомпрометованого ключа — її треба прийняти свідомо.

## 4. Локальна збірка (після ротації)

```bash
export KEYSTORE_PATH=/шлях/до/release-key.jks
export KEYSTORE_PASSWORD="<пароль>"
export KEY_ALIAS="translator"
export KEY_PASSWORD="<пароль>"
./gradlew assembleRelease
```
Без цих змінних збірка свідомо падає з `GradleException` — тихого fallback на інший ключ немає.

## 5. Що ще перевірити

- [ ] Старі токени відкликано (GitHub, BotFather).
- [ ] `git log --all --full-history -- app/release-key.jks` — порожньо після чистки історії.
- [ ] У репозиторії немає файлів зі значеннями секретів: пошук за `ghp_`, токеном бота, `translator123` має давати лише `[REDACTED]`-позначки в `AUDIT.md`/`PROJECT_HANDOVER_DOSSIER.md`.
- [ ] Реліз збирається з тега: `git tag v1.0.3 && git push origin v1.0.3` — workflow створить реліз `v1.0.3` автоматично.
- [ ] Посилання для скачування в `send_tg.py` / `send_to_telegram.ps1` вказують на актуальний тег релізу (зараз `v1.0.3`, синхронізувати з `versionName` в `app/build.gradle.kts`).

## Коротко: що вже виправлено в коді (аудит, топ-10, пункти 1/2/8)

- Секрети винесено з усіх `.ps1`/`.py` скриптів у env-змінні (`GITHUB_TOKEN`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) з fail-fast перевіркою.
- `PROJECT_HANDOVER_DOSSIER.md` — значення секретів замінено на `[REDACTED — відкликати та перевипустити]`, додано попередження про обов'язкову ротацію.
- `app/release-key.jks` видалено з робочої копії; `.gitignore` забороняє `*.jks`, `*.keystore`, `*.apk`, `*.aab`, `app/src/main/jniLibs/`.
- `app/build.gradle.kts` — підпис тільки через `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` з `System.getenv()`, інакше `GradleException`.
- Workflow: тригер на теги `v*` + `workflow_dispatch`; тег релізу береться з `versionName`; кейстор відновлюється з секрету `KEYSTORE_BASE64`; кешування Gradle і NDK; екшени зафіксовано на commit SHA.
- `setup_dependencies.sh`: retry (3 спроби) для мережевих завантажень, `c-api.h` завжди з того ж тега, що пребілд, SHA256-перевірка тарбола sherpa-onnx.
