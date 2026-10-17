$token = $env:TELEGRAM_BOT_TOKEN
if (-not $env:TELEGRAM_BOT_TOKEN) { Write-Error "ERROR: TELEGRAM_BOT_TOKEN environment variable is not set."; exit 1 }
$response = Invoke-RestMethod -Uri "https://api.telegram.org/bot$token/getUpdates"
if ($response.result.Count -gt 0) {
    foreach ($u in $response.result) {
        $from = $u.message.from
        Write-Host "Found message from user: ID=$($from.id), Name=$($from.first_name), Username=$($from.username)"
    }
} else {
    Write-Host "NO_UPDATES_YET"
}
