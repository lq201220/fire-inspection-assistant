$ErrorActionPreference='Stop'
. "$PSScriptRoot/Environment.ps1"
Set-Location $ProjectRoot
& "$PSScriptRoot/Maven.ps1" verify -B -ntp
if ($LASTEXITCODE -ne 0) { throw '后端验证失败。' }
& npm run build
if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' }
Write-Output '后端测试、可执行 JAR 和前端静态构建已完成。'
