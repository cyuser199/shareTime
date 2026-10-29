$ErrorActionPreference = 'Stop'
$requiredPaths = @('C:\environment\gradle\gradle-9.5.0\bin', 'C:\environment\build-tools\37.0.0')
$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$existingPaths = @($machinePath -split ';' | Where-Object { $_ })
foreach ($toolPath in $requiredPaths) {
    if (!(Test-Path -LiteralPath $toolPath -PathType Container)) { throw "Missing tools: $toolPath" }
    $present = @($existingPaths | Where-Object { $_.TrimEnd('\') -ieq $toolPath.TrimEnd('\') }).Count -gt 0
    if (!$present) { $existingPaths += $toolPath }
}
[Environment]::SetEnvironmentVariable('Path', ($existingPaths -join ';'), 'Machine')
foreach ($toolPath in $requiredPaths) {
    if (!(@($env:Path -split ';' | Where-Object { $_.TrimEnd('\') -ieq $toolPath.TrimEnd('\') }).Count)) { $env:Path += ';' + $toolPath }
}
Write-Output 'Added Gradle 9.5 and Android Build Tools 37 to the machine PATH. Existing entries preserved.'
