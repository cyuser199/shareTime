$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = 'C:\environment\temurin-17\jdk-17.0.20.1+1'
$env:ANDROID_HOME = 'C:\environment'
$env:GRADLE_USER_HOME = 'C:\environment\cache\sharetime-gradle'
$gradle = 'C:\environment\gradle\gradle-9.5.0\bin\gradle.bat'
if (!(Test-Path $gradle)) { throw 'Run tools/prepare_android_env.py first.' }
& $gradle -p "$workspaceRoot\android" assembleDebug testDebugUnitTest lintDebug --console=plain --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Android checks failed: $LASTEXITCODE" }
$appVersion = [regex]::Match((Get-Content -LiteralPath "$workspaceRoot\android\app\build.gradle.kts" -Raw), 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (!$appVersion) { throw 'Missing application version.' }
Copy-Item -LiteralPath "$workspaceRoot\android\app\build\outputs\apk\debug\app-debug.apk" -Destination "$workspaceRoot\deliverables\ShareTime-$appVersion-debug.apk" -Force
