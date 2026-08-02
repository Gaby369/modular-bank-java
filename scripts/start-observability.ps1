$ErrorActionPreference = "Stop"

if (-not (Test-Path ".\docker-compose.yml")) {
    throw "Ejecuta este script desde la raíz de modular-bank-java."
}

New-Item -ItemType Directory -Force -Path ".\logs" | Out-Null

# Habilita el plugin Prometheus del RabbitMQ ya definido por el proyecto.
docker compose up -d rabbitmq
docker compose exec rabbitmq rabbitmq-plugins enable rabbitmq_prometheus

# Levanta infraestructura del proyecto + observabilidad.
docker compose `
  -f docker-compose.yml `
  -f observability/docker-compose.observability.yml `
  up -d

Write-Host "Jaeger:    http://localhost:16686"
Write-Host "Prometheus:http://localhost:9090/targets"
Write-Host "Grafana:   http://localhost:3000  (admin/admin)"
Write-Host "Loki:      http://localhost:3100/ready"
Write-Host "RabbitMQ:  http://localhost:15672"
Write-Host "RabbitMQ metrics: http://localhost:15692/metrics"
