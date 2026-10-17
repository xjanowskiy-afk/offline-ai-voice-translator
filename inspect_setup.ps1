$token = $env:GITHUB_TOKEN
if (-not $env:GITHUB_TOKEN) { Write-Error "ERROR: GITHUB_TOKEN environment variable is not set."; exit 1 }
$repo = "xjanowskiy-afk/offline-ai-voice-translator"
$headers = @{
    "Authorization" = "token $token"
    "Accept"        = "application/vnd.github.v3+json"
}
$runs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs?per_page=1" -Headers $headers
$runId = $runs.workflow_runs[0].id
$jobs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs/$runId/jobs" -Headers $headers
$failedJob = $jobs.jobs | Where-Object { $_.conclusion -eq "failure" } | Select-Object -First 1
$log = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/jobs/$($failedJob.id)/logs" -Headers $headers
$lines = $log -split "`n"
Write-Host ("Total lines in log: " + $lines.Count)
$lines | Select-String -Pattern "libsherpa" | ForEach-Object { Write-Host $_.Line }
