param(
    [Parameter(Mandatory=$true)]
    [string]$TraceId
)

$ErrorActionPreference = "Continue"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = ".\evidencias\$stamp"
New-Item -ItemType Directory -Force -Path $out | Out-Null

# Evidencia textual de logs locales con el mismo TraceId.
Get-ChildItem ".\logs\*.json" -ErrorAction SilentlyContinue |
    Select-String -Pattern $TraceId |
    Out-File "$out\04-logs-mismo-traceid.txt" -Encoding utf8

# Exporta el trace desde Jaeger.
try {
    Invoke-WebRequest -UseBasicParsing `
        -Uri "http://localhost:16686/api/traces/$TraceId" `
        -OutFile "$out\02-trace-completo.json"
} catch {
    $_ | Out-File "$out\02-trace-error.txt"
}

# Estado de contenedores y colas.
docker compose `
  -f docker-compose.yml `
  -f observability/docker-compose.observability.yml `
  ps | Out-File "$out\00-contenedores.txt"

docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers |
    Out-File "$out\05-rabbitmq-colas.txt"

Write-Host "Evidencia exportada en: $out"
