$tasks = @(
    @{ name="silero_vad.onnx"; url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx" },
    @{ name="encoder.onnx"; url="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/encoder-epoch-99-avg-1.onnx" },
    @{ name="decoder.onnx"; url="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/decoder-epoch-99-avg-1.onnx" },
    @{ name="joiner.onnx"; url="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/joiner-epoch-99-avg-1.onnx" },
    @{ name="tokens.txt"; url="https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/tokens.txt" },
    @{ name="qwen2.5-1.5b-instruct-q4_k_m.gguf"; url="https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf" },
    @{ name="de_DE-thorsten-high.onnx"; url="https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/high/de_DE-thorsten-high.onnx" },
    @{ name="de_DE-thorsten-high.onnx.json"; url="https://huggingface.co/rhasspy/piper-voices/resolve/main/de/de_DE/thorsten/high/de_DE-thorsten-high.onnx.json" },
    @{ name="ru_RU-irina-medium.onnx"; url="https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx" },
    @{ name="ru_RU-irina-medium.onnx.json"; url="https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU/irina/medium/ru_RU-irina-medium.onnx.json" },
    @{ name="espeak-ng-data.tar.bz2"; url="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2" }
)

foreach ($t in $tasks) {
    try {
        $req = [System.Net.HttpWebRequest]::Create($t.url)
        $req.Method = "HEAD"
        $req.UserAgent = "Mozilla/5.0"
        $resp = $req.GetResponse()
        Write-Host ($t.name + " -> Status: " + [int]$resp.StatusCode + " | Length: " + $resp.ContentLength)
        $resp.Close()
    } catch {
        Write-Host ($t.name + " -> ERROR: " + $_.Exception.Message)
    }
}
