param(
    [string]$TargetDir = (Get-Location).Path
)

$token = $env:GITHUB_TOKEN
if (-not $env:GITHUB_TOKEN) { Write-Error "ERROR: GITHUB_TOKEN environment variable is not set."; exit 1 }
$repo = "xjanowskiy-afk/offline-ai-voice-translator"
$headers = @{
    "Authorization" = "token $token"
    "Accept"        = "application/vnd.github.v3+json"
}

Write-Host "Checking workflow runs..."
$run = $null

while ($true) {
    try {
        $runs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs?per_page=3" -Headers $headers
        $latest = $runs.workflow_runs[0]
        $msg = $latest.head_commit.message -replace "[`r`n]", " "
        Write-Host ("Run ID: {0} | Status: {1} | Conclusion: {2} | Commit: {3}" -f $latest.id, $latest.status, $latest.conclusion, $msg)
        
        if ($latest.status -eq "completed") {
            $run = $latest
            break
        }
    } catch {
        Write-Host ("Error querying runs: " + $_.Exception.Message)
    }
    Start-Sleep -Seconds 12
}

Write-Host ("Run finished with conclusion: " + $run.conclusion)

if ($run.conclusion -eq "success") {
    Write-Host "Success! Fetching release asset..."
    $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Headers $headers
    $downloadUrl = $null
    if ($releases.Count -gt 0 -and $releases[0].assets.Count -gt 0) {
        $apkAsset = $releases[0].assets | Where-Object { $_.name -like "*.apk" } | Select-Object -First 1
        if ($apkAsset) {
            $downloadUrl = $apkAsset.browser_download_url
            $outApk = Join-Path $TargetDir "app-release.apk"
            Write-Host ("Downloading APK from: " + $downloadUrl)
            Invoke-WebRequest -Uri $downloadUrl -OutFile $outApk
            Write-Host ("APK successfully saved to: " + $outApk)
            exit 0
        }
    }

    $artifacts = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs/$($run.id)/artifacts" -Headers $headers
    if ($artifacts.artifacts.Count -gt 0) {
        $art = $artifacts.artifacts[0]
        $zipPath = Join-Path $TargetDir "artifact.zip"
        Write-Host ("Downloading artifact zip: " + $art.name)
        Invoke-WebRequest -Uri $art.archive_download_url -OutFile $zipPath -Headers $headers
        Expand-Archive -Path $zipPath -DestinationPath $TargetDir -Force
        Remove-Item $zipPath -Force
        Write-Host "Artifact successfully unpacked."
        exit 0
    }
} else {
    Write-Host "Build failed! Getting failure log..."
    $jobs = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/runs/$($run.id)/jobs" -Headers $headers
    $failedJob = $jobs.jobs | Where-Object { $_.conclusion -eq "failure" } | Select-Object -First 1
    if ($failedJob) {
        Write-Host ("Failed Job ID: " + $failedJob.id)
        $log = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/actions/jobs/$($failedJob.id)/logs" -Headers $headers
        $logLines = $log -split "`n"
        Write-Host "=== RECENT ERROR LOG LINES ==="
        $logLines | Select-Object -Last 100 | ForEach-Object { Write-Host $_ }
    }
    exit 1
}
