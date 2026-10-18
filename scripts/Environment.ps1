$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
function Import-ProjectEnvironment {
    $configFile = Join-Path $ProjectRoot '.env'
    if (-not (Test-Path -LiteralPath $configFile)) { throw '请先复制 .env.example 为 .env 并填写配置。' }
    foreach ($line in (Get-Content -LiteralPath $configFile -Encoding utf8)) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            if ($Matches[1] -in @('DB_PASSWORD','MYSQL_ROOT_PASSWORD','JWT_SECRET','DEMO_ENABLED','DEMO_PASSWORD','MYSQL_PORT','API_PORT','VITE_API_BASE_URL','DB_URL','DB_USER','UPLOAD_DIR','CORS_ORIGINS')) {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            }
        }
    }
    if (-not $env:DB_URL) { $env:DB_URL = 'jdbc:mysql://localhost:' + $env:MYSQL_PORT + '/fire_assistant?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai' }
    if (-not $env:UPLOAD_DIR) { $env:UPLOAD_DIR = Join-Path $ProjectRoot '.runtime/uploads' }
}
function Initialize-Java {
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $localJdk = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot '.tools') -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
        if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
    }
    if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) { throw '未找到 Java 17+；请运行开发环境准备脚本或设置 JAVA_HOME。' }
    if ($env:JAVA_HOME) { $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH }
}
