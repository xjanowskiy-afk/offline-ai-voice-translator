if (-not $env:GITHUB_TOKEN) { Write-Error "ERROR: GITHUB_TOKEN environment variable is not set."; exit 1 }
$hdr = @{
    "Authorization" = "token $env:GITHUB_TOKEN"
    "Accept" = "application/vnd.github.v3+json"
}
$runs = Invoke-RestMethod -Uri "https://api.github.com/repos/xjanowskiy-afk/offline-ai-voice-translator/actions/runs?per_page=3" -Headers $hdr
foreach ($r in $runs.workflow_runs) {
    Write-Host "ID:" $r.id "Status:" $r.status "Conclusion:" $r.conclusion "Commit:" $r.head_commit.id $r.head_commit.message
}
