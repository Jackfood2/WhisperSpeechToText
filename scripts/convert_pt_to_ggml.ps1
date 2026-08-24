param([string]$ModelPath="C:\Peter\Python311\App_Whisper\Scripts\small.pt", [string]$OutDir="C:\Peter\WhisperAndroid\app\src\main\assets\models")
# Converts your .pt to ggml .bin using whisper.cpp python script
Write-Host "Converting $ModelPath -> ggml..."
$cpp = "C:\Peter\WhisperAndroid\app\src\main\cpp\whisper.cpp"
if(!(Test-Path $cpp)){ Write-Host "First run setup_whisper_cpp.ps1"; exit 1 }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Copy-Item $ModelPath "$cpp\models\" -Force
Push-Location $cpp
python models\convert-pt-to-ggml.py "$cpp\models\$(Split-Path $ModelPath -Leaf)" . 1
$ggml = Get-ChildItem "$cpp\models\ggml-*.bin" | Sort LastWriteTime -Descending | Select -First 1
if($ggml){ Copy-Item $ggml.FullName "$OutDir\" -Force; Write-Host "Wrote $($ggml.Name) to $OutDir" }
Pop-Location
