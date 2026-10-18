param([switch]$SkipDownloads)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Environment.ps1"
Set-Location $ProjectRoot
New-Item -ItemType Directory -Path '.tools','.runtime' -Force | Out-Null
if (-not (Test-Path -LiteralPath '.env')) {
    $random=[byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($random)
    $lines=@('DB_PASSWORD='+[Guid]::NewGuid().ToString('N'))
    $lines+=('MYSQL_ROOT_PASSWORD='+[Guid]::NewGuid().ToString('N'))
    $lines+=('JWT_SECRET='+[Convert]::ToBase64String($random))
    $lines+=@('DEMO_ENABLED=true','DEMO_PASSWORD=123456','MYSQL_PORT=3307','API_PORT=8081','VITE_API_BASE_URL=http://localhost:8081/api')
    Set-Content -LiteralPath '.env' -Value $lines -Encoding utf8
    Write-Output '已生成本地配置，演示账号初始密码：123456'
}
if (-not $SkipDownloads) {
    if (-not (Test-Path -LiteralPath '.tools/jdk17.0.20_12/bin/java.exe') -and -not $env:JAVA_HOME) {
        $jdkZip=Join-Path $ProjectRoot '.tools/corretto.zip'
        if (-not (Test-Path -LiteralPath $jdkZip)) { Invoke-WebRequest 'https://corretto.aws/downloads/resources/17.0.20.12.1/amazon-corretto-17.0.20.12.1-windows-x64-jdk.zip' -OutFile $jdkZip -TimeoutSec 600 }
        if ((Get-FileHash $jdkZip -Algorithm SHA256).Hash -ne '1AC8D9DC48B170684AC6223113F524325BD842D14ED74DAC28927F8165CFFD3A') { throw 'Java 安装包校验失败。' }
        Expand-Archive -LiteralPath $jdkZip -DestinationPath '.tools' -Force
    }
    if (-not (Test-Path -LiteralPath '.tools/apache-maven-3.9.9/bin/mvn.cmd') -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        $mavenZip=Join-Path $ProjectRoot '.tools/maven.zip'
        if (-not (Test-Path -LiteralPath $mavenZip)) { Invoke-WebRequest 'https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip' -OutFile $mavenZip -TimeoutSec 600 }
        if ((Get-FileHash $mavenZip -Algorithm SHA512).Hash -ne '8BEAC8D11EF208F1E2A8DF0682B9448A9A363D2AD13CA74AF43705549E72E74C9378823BF689287801CBBFC2F6EA9596201D19CCACFDFB682EE8A2FF4C4418BA') { throw 'Maven 安装包校验失败。' }
        Expand-Archive -LiteralPath $mavenZip -DestinationPath '.tools' -Force
    }
}
Initialize-Java
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw '请先安装 Node.js 22.12 或更高版本。' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '请先安装并启动 Docker Desktop。' }
& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker 未启动，请启动 Docker Desktop 后重试。' }
& npm ci
if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
& docker compose up -d --wait db
if ($LASTEXITCODE -ne 0) { throw '数据库启动失败。' }
Write-Output '环境准备完成。执行 scripts/Build.ps1 测试并构建，再执行 scripts/Start.ps1 启动。'
