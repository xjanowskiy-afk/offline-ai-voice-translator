$url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.onnx"
$req = [System.Net.HttpWebRequest]::Create($url)
$req.Method = "HEAD"
$req.UserAgent = "Mozilla/5.0"
$req.Headers.Add("Range", "bytes=0-")
try {
    $resp = $req.GetResponse()
    Write-Host "Status: " ([int]$resp.StatusCode)
    Write-Host "Content-Length: " $resp.ContentLength
    $resp.Close()
} catch {
    Write-Host "Error: " $_.Exception.Message
}
