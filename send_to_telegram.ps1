param(
    [string]$FilePath = "E:\переводчик на телефон\app-release.apk",
    [string]$Caption = "Готовый релиз Offline AI Voice Translator v1.0.3"
)

$token = $env:TELEGRAM_BOT_TOKEN
if (-not $env:TELEGRAM_BOT_TOKEN) { Write-Error "ERROR: TELEGRAM_BOT_TOKEN environment variable is not set."; exit 1 }
$chatId = $env:TELEGRAM_CHAT_ID
if (-not $env:TELEGRAM_CHAT_ID) { Write-Error "ERROR: TELEGRAM_CHAT_ID environment variable is not set."; exit 1 }

if (-not (Test-Path $FilePath)) {
    Write-Host "Файл не найден: $FilePath"
    exit 1
}

$fileInfo = Get-Item $FilePath
Write-Host "Отправка файла: $($fileInfo.Name) ($([math]::Round($fileInfo.Length / 1MB, 2)) МБ) пользователю $chatId..."

$uri = "https://api.telegram.org/bot$token/sendDocument"

# Telegram Bot API для локальных ботов поддерживает до 50MB через стандартный API,
# но если файл чуть больше 50MB (например, 52MB), мы шлем прямую ссылку на скачивание или используем multipart
$sizeMb = [math]::Round($fileInfo.Length / 1MB, 2)

if ($fileInfo.Length -gt 50 * 1024 * 1024) {
    Write-Host "Размер файла ($sizeMb МБ) превышает лимит стандартного бота Telegram (50 МБ)."
    Write-Host "Отправляем сообщение с прямой ссылкой на скачивание на телефон..."
    
    # NB: keep in sync with versionName in app/build.gradle.kts
    $downloadUrl = "https://github.com/xjanowskiy-afk/offline-ai-voice-translator/releases/download/v1.0.3/app-release.apk"
    $msgText = "🎉 <b>$Caption</b>`n`n" +
               "📦 <b>Файл:</b> $($fileInfo.Name)`n" +
               "📊 <b>Размер:</b> $sizeMb МБ`n`n" +
               "⚠️ Из-за лимита Telegram на файлы ботов (до 50 МБ), скачайте APK напрямую на телефон по ссылке:`n" +
               "👉 <a href='$downloadUrl'>Скачать app-release.apk (v1.0.3)</a>"

    $msgBody = @{
        chat_id    = $chatId
        text       = $msgText
        parse_mode = "HTML"
    } | ConvertTo-Json

    Invoke-RestMethod -Uri "https://api.telegram.org/bot$token/sendMessage" -Method Post -ContentType "application/json; charset=utf-8" -Body $msgBody
    Write-Host "Сообщение со ссылкой успешно отправлено в Telegram!"
} else {
    # Отправка напрямую файлом через multipart/form-data
    $bytes = [System.IO.File]::ReadAllBytes($FilePath)
    $boundary = [System.Guid]::NewGuid().ToString()
    $LF = "`r`n"

    $body = (
        "--$boundary$LF" +
        "Content-Disposition: form-data; name=`"chat_id`"$LF$LF" +
        "$chatId$LF" +
        "--$boundary$LF" +
        "Content-Disposition: form-data; name=`"caption`"$LF$LF" +
        "$Caption$LF" +
        "--$boundary$LF" +
        "Content-Disposition: form-data; name=`"document`"; filename=`"$($fileInfo.Name)`"$LF" +
        "Content-Type: application/vnd.android.package-archive$LF$LF"
    )

    $headerBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $footerBytes = [System.Text.Encoding]::UTF8.GetBytes("$LF--$boundary--$LF")

    $totalBytes = [byte[]]::new($headerBytes.Length + $bytes.Length + $footerBytes.Length)
    [System.Buffer]::BlockCopy($headerBytes, 0, $totalBytes, 0, $headerBytes.Length)
    [System.Buffer]::BlockCopy($bytes, 0, $totalBytes, $headerBytes.Length, $bytes.Length)
    [System.Buffer]::BlockCopy($footerBytes, 0, $totalBytes, $headerBytes.Length + $bytes.Length, $footerBytes.Length)

    Invoke-RestMethod -Uri $uri -Method Post -ContentType "multipart/form-data; boundary=$boundary" -Body $totalBytes
    Write-Host "APK успешно отправлен файлом в Telegram!"
}
