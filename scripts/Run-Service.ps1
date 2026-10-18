param([ValidateSet('api','web')][string]$Service)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Environment.ps1"
Import-ProjectEnvironment
Set-Location $ProjectRoot
if ($Service -eq 'api') {
    Initialize-Java
    $jar=Join-Path $ProjectRoot 'backend/target/fire-assistant-1.0.0.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw '请先执行 Build.ps1 构建后端。' }
    $javaCommand=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{'java'}
    & $javaCommand -jar $jar
} else {
    & npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
}
