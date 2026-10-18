param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArguments)
. (Join-Path $PSScriptRoot 'Environment.ps1')
Import-ProjectEnvironment
Initialize-Java
$localMaven = Join-Path $ProjectRoot '.tools/apache-maven-3.9.9/bin/mvn.cmd'
$mavenCommand = if (Test-Path -LiteralPath $localMaven) { $localMaven } else { (Get-Command mvn -ErrorAction Stop).Source }
Push-Location (Join-Path $ProjectRoot 'backend')
try { & $mavenCommand @MavenArguments; exit $LASTEXITCODE } finally { Pop-Location }
