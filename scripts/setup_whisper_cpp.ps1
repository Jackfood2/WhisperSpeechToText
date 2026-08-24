param([string]$CppDir="C:\Peter\WhisperAndroid\app\src\main\cpp")
Write-Host "Cloning whisper.cpp..."
if(Test-Path "$CppDir\whisper.cpp"){ Remove-Item -Recurse -Force "$CppDir\whisper.cpp" }
git clone https://github.com/ggerganov/whisper.cpp "$CppDir\whisper.cpp"
Write-Host "Done. Now open Android Studio and rebuild."
Write-Host "If git not found: download zip from https://github.com/ggerganov/whisper.cpp/archive/refs/heads/master.zip and extract to $CppDir\whisper.cpp"
