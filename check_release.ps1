if (-not $env:GITHUB_TOKEN) { Write-Error "ERROR: GITHUB_TOKEN environment variable is not set."; exit 1 }
$hdr = @{
    "Authorization" = "token $env:GITHUB_TOKEN"
    "Accept" = "application/vnd.github.v3+json"
}
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/xjanowskiy-afk/offline-ai-voice-translator/releases" -Headers $hdr
Write-Host "Tag:" $rel[0].tag_name "Created:" $rel[0].created_at
foreach ($a in $rel[0].assets) {
    Write-Host "Asset:" $a.name "Size:" $a.size "Updated:" $a.updated_at "URL:" $a.browser_download_url
}
