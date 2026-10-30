param([string]$BuildRoot = 'D:\codex-build\shareTime')
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\environment\temurin-17\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\environment'
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
$env:GRADLE_USER_HOME = Join-Path $BuildRoot 'gradle-home'
$env:TEMP = Join-Path $BuildRoot 'temp'
$env:TMP = $env:TEMP
$runRoot = Join-Path $BuildRoot ('runs\' + [Guid]::NewGuid().ToString('N'))
$androidRoot = Join-Path $runRoot 'android'
$outputRoot = Join-Path $BuildRoot 'deliverables'
New-Item -ItemType Directory -Path $env:TEMP,$env:GRADLE_USER_HOME,$androidRoot,$outputRoot -Force | Out-Null
# Isolate generated outputs on D:, while retaining the source checkout on C:.
& robocopy "$workspaceRoot\android" $androidRoot /E /XD build .gradle .kotlin /R:1 /W:1 /NFL /NDL /NJH /NJS /NP
if ($LASTEXITCODE -ge 8) { throw "Source copy failed: $LASTEXITCODE" }
$gradle = 'C:\environment\gradle\gradle-9.5.0\bin\gradle.bat'
if (!(Test-Path $gradle)) { throw 'Run tools/prepare_android_env.py first.' }
& $gradle -p $androidRoot assembleDebug testDebugUnitTest lintDebug --console=plain --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$($env:TEMP)" 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'build.log')
if ($LASTEXITCODE -ne 0) { throw "Android checks failed: $LASTEXITCODE" }
$appVersion = [regex]::Match((Get-Content -LiteralPath "$androidRoot\app\build.gradle.kts" -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (!$appVersion) { throw 'Missing application version.' }
Copy-Item -LiteralPath "$androidRoot\app\build\outputs\apk\debug\app-debug.apk" -Destination "$outputRoot\ShareTime-$appVersion-debug.apk" -Force
Write-Host "APK: $outputRoot\ShareTime-$appVersion-debug.apk"
Write-Host "Build reports and log: $runRoot"
