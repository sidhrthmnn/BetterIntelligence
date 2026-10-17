param([string]$Destination = 'D:\Codex\bi-models\gemma4')
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$specs = @(
    @('litert-community/gemma-4-E2B-it-litert-lm','b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1','gemma-4-E2B-it.litertlm','181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c'),
    @('unsloth/gemma-4-E2B-it-GGUF','0314792d7f1f7e229411f620751375812bb9faf2','gemma-4-E2B-it-Q4_K_M.gguf','740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8'),
    @('litert-community/gemma-4-E4B-it-litert-lm','2eee7ac325f20eb8c9ac1d0e972f7c84663062da','gemma-4-E4B-it.litertlm','0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0'),
    @('unsloth/gemma-4-E4B-it-GGUF','bfc15c382204943c3a8fff0c750b94ae2364d7a3','gemma-4-E4B-it-Q4_K_M.gguf','85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87')
)
foreach ($spec in $specs) {
    $target = Join-Path $Destination $spec[2]
    if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target).Hash -eq $spec[3]) { Write-Output "Verified cached $($spec[2])"; continue }
    & curl.exe --fail --location --retry 3 --continue-at - --output "$target.partial" "https://huggingface.co/$($spec[0])/resolve/$($spec[1])/$($spec[2])"
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $($spec[2])" }
    if ((Get-FileHash -LiteralPath "$target.partial").Hash -ne $spec[3]) { throw "Checksum mismatch: $($spec[2])" }
    Move-Item -LiteralPath "$target.partial" -Destination $target
    Write-Output "Verified $($spec[2])"
}
