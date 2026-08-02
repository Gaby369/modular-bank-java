<#
    Recolecta la evidencia final del Paso 4: ejecuta una transferencia real
    de extremo a extremo (API Gateway -> Transfers Service -> RabbitMQ ->
    Accounts Service -> RabbitMQ -> Transfers Service, con el monolito
    consumiendo el mismo evento TransferRequested.v1 en paralelo) y
    verifica que el mismo TraceId aparezca en Jaeger y en los 4 logs JSON.

    Requiere que API Gateway, Accounts Service, Transfers Service, el
    monolito, RabbitMQ y Jaeger ya esten corriendo con el codigo mas
    reciente (reinicia manualmente cualquier servicio que hayas modificado
    antes de ejecutar este script).
#>

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$reference = "step4-evidence-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
$evidenceDir = Join-Path $repoRoot "evidence\step4"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

function Write-Step {
    param([string]$Message)
    Write-Host "`n=== $Message ===" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Fail {
    param([string]$Message)
    Write-Host "[FAIL] $Message" -ForegroundColor Red
}

function Test-YesNo {
    param([bool]$Value)
    if ($Value) { return "SI" }
    return "NO"
}

# ---------------------------------------------------------------------
# 1. Puertos
# ---------------------------------------------------------------------
Write-Step "1. Verificando puertos"

$healthChecks = [ordered]@{
    "API Gateway"       = "http://localhost:8080/actuator/health"
    "Accounts Service"  = "http://localhost:8081/actuator/health"
    "Monolito"          = "http://localhost:8082/actuator/health"
    "Transfers Service" = "http://localhost:8083/actuator/health"
    "Jaeger"            = "http://localhost:16686"
}

$allUp = $true
foreach ($name in $healthChecks.Keys) {
    try {
        $resp = Invoke-WebRequest -UseBasicParsing -Uri $healthChecks[$name] -TimeoutSec 5
        Write-Ok "$name -> HTTP $($resp.StatusCode)"
    } catch {
        Write-Fail "$name -> no responde ($($_.Exception.Message))"
        $allUp = $false
    }
}

if (-not $allUp) {
    Write-Fail "Al menos un servicio no esta activo. Revisa y reinicia antes de continuar."
    exit 1
}

# ---------------------------------------------------------------------
# 2. Login (JWT solo en memoria)
# ---------------------------------------------------------------------
Write-Step "2. Iniciando sesion"

$testEmail = $env:STEP4_TEST_EMAIL
$testPassword = $env:STEP4_TEST_PASSWORD

if ([string]::IsNullOrWhiteSpace($testEmail) -or [string]::IsNullOrWhiteSpace($testPassword)) {
    Write-Fail "Define las variables de entorno STEP4_TEST_EMAIL y STEP4_TEST_PASSWORD antes de ejecutar este script"
    exit 1
}

$loginBody = @{
    email    = $testEmail
    password = $testPassword
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod `
    -Uri "http://localhost:8080/auth/login" `
    -Method Post `
    -Body $loginBody `
    -ContentType "application/json" `
    -TimeoutSec 15

$jwt = $loginResponse.accessToken

if ([string]::IsNullOrWhiteSpace($jwt)) {
    Write-Fail "Login fallido: no se recibio accessToken"
    exit 1
}

Write-Ok "Login exitoso (JWT en memoria; no se imprime ni se guarda en disco)"

$authHeaders = @{ Authorization = "Bearer $jwt" }

# ---------------------------------------------------------------------
# 3. Cuentas reales
# ---------------------------------------------------------------------
Write-Step "3. Consultando cuentas reales"

$accounts = Invoke-RestMethod `
    -Uri "http://localhost:8080/accounts" `
    -Headers $authHeaders `
    -TimeoutSec 15

if ($accounts.Count -lt 2) {
    Write-Fail "El usuario necesita al menos 2 cuentas para esta prueba"
    exit 1
}

$sourceAccount = $accounts | Where-Object { $_.balance -gt 1.00 } | Select-Object -First 1

if (-not $sourceAccount) {
    Write-Fail "Ninguna cuenta tiene saldo suficiente (> 1.00)"
    exit 1
}

$targetAccount = $accounts | Where-Object { $_.id -ne $sourceAccount.id } | Select-Object -First 1

if (-not $targetAccount) {
    Write-Fail "No se encontro una segunda cuenta distinta"
    exit 1
}

Write-Ok "Origen: $($sourceAccount.id) (saldo $($sourceAccount.balance))"
Write-Ok "Destino: $($targetAccount.id) (saldo $($targetAccount.balance))"

# ---------------------------------------------------------------------
# 4. Ejecutar transferencia real
# ---------------------------------------------------------------------
Write-Step "4. Ejecutando transferencia de 1.00 (referencia: $reference)"

$transferBody = @{
    sourceAccountId = $sourceAccount.id
    targetAccountId = $targetAccount.id
    amount          = 1.00
    reference       = $reference
} | ConvertTo-Json

$transferHeaders = @{
    Authorization      = "Bearer $jwt"
    "X-Correlation-Id" = $reference
}

$transferResponse = Invoke-RestMethod `
    -Uri "http://localhost:8080/transfers" `
    -Method Post `
    -Body $transferBody `
    -Headers $transferHeaders `
    -ContentType "application/json" `
    -TimeoutSec 30

$transferId = $transferResponse.id
$initialState = $transferResponse.status

Write-Ok "transferId=$transferId  estadoInicial=$initialState"

# ---------------------------------------------------------------------
# 5. Esperar resultado final
# ---------------------------------------------------------------------
Write-Step "5. Esperando estado final (COMPLETED o FAILED)"

$finalState = $initialState
$maxAttempts = 30

for ($i = 0; $i -lt $maxAttempts; $i++) {
    Start-Sleep -Seconds 1

    $history = Invoke-RestMethod `
        -Uri "http://localhost:8080/transfers?accountId=$($sourceAccount.id)" `
        -Headers $authHeaders `
        -TimeoutSec 15

    $match = $history | Where-Object { $_.id -eq $transferId }

    if ($match -and ($match.status -eq "COMPLETED" -or $match.status -eq "FAILED")) {
        $finalState = $match.status
        break
    }
}

if ($finalState -eq "COMPLETED") {
    Write-Ok "Estado final: $finalState"
} else {
    Write-Fail "Estado final: $finalState (no llego a COMPLETED)"
}

# ---------------------------------------------------------------------
# 6. Verificar duplicados por referencia
# ---------------------------------------------------------------------
Write-Step "6. Verificando transferencias duplicadas"

$dupRaw = docker compose exec -T transfers-postgres psql -U transfers -d transfers_db -t -A -c `
    "SELECT count(*) FROM transfers.transfers WHERE reference = '$reference';"

$dupCount = ($dupRaw | Select-String -Pattern '\d+' | Select-Object -First 1).Matches[0].Value
$isDuplicate = [int]$dupCount -gt 1

Write-Ok "Transferencias encontradas con esta referencia: $dupCount"

# ---------------------------------------------------------------------
# 7. correlationId y TraceId desde el outbox
# ---------------------------------------------------------------------
Write-Step "7. Obteniendo correlationId y TraceId"

$outboxRaw = docker compose exec -T transfers-postgres psql -U transfers -d transfers_db -t -A -F "|" -c `
    "SELECT correlation_id, traceparent FROM transfers.outbox_events WHERE aggregate_id = '$transferId' ORDER BY created_at LIMIT 1;"

$outboxLine = ($outboxRaw | Where-Object { $_ -match '\|' } | Select-Object -First 1)
$parts = $outboxLine -split '\|'
$correlationId = $parts[0].Trim()
$traceparent = $parts[1].Trim()
$traceId = ($traceparent -split '-')[1]

Write-Ok "correlationId=$correlationId"
Write-Ok "TraceId=$traceId"

# ---------------------------------------------------------------------
# 8. Consultar Jaeger
# ---------------------------------------------------------------------
Write-Step "8. Consultando Jaeger"

$jaegerTrace = Invoke-RestMethod `
    -Uri "http://localhost:16686/api/traces/$traceId" `
    -TimeoutSec 15

$traceFound = $jaegerTrace.data.Count -gt 0

$services = @()
$opNames = @()

if ($traceFound) {
    $processes = $jaegerTrace.data[0].processes
    foreach ($p in $processes.PSObject.Properties) {
        $services += $p.Value.serviceName
    }
    $opNames = $jaegerTrace.data[0].spans | ForEach-Object { $_.operationName }
}

$hasGatewayInTrace = $services -contains "api-gateway"
$hasTransfersInTrace = $services -contains "transfers-service"
$hasAccountsInTrace = $services -contains "accounts-service"
$hasMonolithInTrace = $services -contains "modular-monolith"
$hasRabbitSend = ($opNames | Where-Object { $_ -match "send" }).Count -gt 0
$hasRabbitReceive = ($opNames | Where-Object { $_ -match "receive" }).Count -gt 0

Write-Ok "Servicios en la traza: $($services -join ', ')"
Write-Ok "RabbitMQ send: $(Test-YesNo $hasRabbitSend)  RabbitMQ receive: $(Test-YesNo $hasRabbitReceive)"

# ---------------------------------------------------------------------
# 9. Buscar TraceId en los 4 logs JSON
# ---------------------------------------------------------------------
Write-Step "9. Buscando el TraceId en los 4 logs JSON"

$logFiles = [ordered]@{
    "api-gateway"       = Join-Path $repoRoot "logs\api-gateway.json"
    "accounts-service"  = Join-Path $repoRoot "logs\accounts-service.json"
    "transfers-service" = Join-Path $repoRoot "logs\transfers-service.json"
    "modular-monolith"  = Join-Path $repoRoot "logs\modular-monolith.json"
}

$logMatches = [ordered]@{}

foreach ($name in $logFiles.Keys) {
    $path = $logFiles[$name]

    if (Test-Path $path) {
        $found = Select-String -Path $path -Pattern $traceId -SimpleMatch
        $logMatches[$name] = $found

        if ($found.Count -gt 0) {
            Write-Ok "$name -> $($found.Count) linea(s) con el TraceId"
        } else {
            Write-Fail "$name -> 0 linea(s) con el TraceId"
        }
    } else {
        $logMatches[$name] = @()
        Write-Fail "$name -> el archivo no existe ($path)"
    }
}

$traceInGateway = $logMatches["api-gateway"].Count -gt 0
$traceInAccounts = $logMatches["accounts-service"].Count -gt 0
$traceInTransfers = $logMatches["transfers-service"].Count -gt 0
$traceInMonolith = $logMatches["modular-monolith"].Count -gt 0
$sameTraceInAllFour = $traceInGateway -and $traceInAccounts -and $traceInTransfers -and $traceInMonolith

# ---------------------------------------------------------------------
# 10. Notificacion TRANSFER_SENT persistida por el monolito
# ---------------------------------------------------------------------
Write-Step "10. Verificando notificacion TRANSFER_SENT"

$notifRaw = docker compose exec -T postgres psql -U bank -d modular_bank -c `
    "SELECT id, user_id, type, payload, created_at FROM notifications.notifications WHERE payload->>'transferId' = '$transferId';"

$notifText = ($notifRaw -join "`n")
$notificationFound = $notifText -match "TRANSFER_SENT"

Write-Ok "Notificacion TRANSFER_SENT persistida: $(Test-YesNo $notificationFound)"

# ---------------------------------------------------------------------
# 11. Estado de las colas
# ---------------------------------------------------------------------
Write-Step "11. Consultando colas de RabbitMQ"

$queuesRaw = docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged consumers

$queuesText = ($queuesRaw -join "`n")
Write-Ok "Colas consultadas"

# ---------------------------------------------------------------------
# 12. Pruebas automatizadas
# ---------------------------------------------------------------------
Write-Step "12. Ejecutando pruebas automatizadas"

$testCommands = [ordered]@{
    "api-gateway"       = @("-f", "services/api-gateway/pom.xml", "test")
    "accounts-service"  = @("-f", "services/accounts-service/pom.xml", "test")
    "transfers-service" = @("-f", "services/transfers-service/pom.xml", "test")
    "monolito"          = @("test")
}

$testResults = [ordered]@{}
$allTestsPassed = $true

foreach ($name in $testCommands.Keys) {
    Write-Host "  Ejecutando: mvn $($testCommands[$name] -join ' ')"

    $logPath = Join-Path $evidenceDir "_test-$name.log"
    $argsLine = $testCommands[$name] -join ' '

    # mvn se invoca via cmd /c para que la redireccion de stderr ocurra
    # dentro de cmd.exe: si PowerShell intercepta directamente el stderr
    # de un ejecutable nativo (2>&1 o *>) con $ErrorActionPreference=Stop,
    # cualquier WARNING de la JVM (p. ej. el aviso de byte-buddy-agent)
    # se convierte en un error terminante y aborta el script sin que
    # los tests hayan fallado realmente.
    cmd /c "mvn $argsLine > `"$logPath`" 2>&1"

    $passed = $LASTEXITCODE -eq 0
    $testResults[$name] = $passed

    if ($passed) {
        Write-Ok "$name -> BUILD SUCCESS"
    } else {
        Write-Fail "$name -> BUILD FAILURE (ver $logPath)"
        $allTestsPassed = $false
    }
}

# ---------------------------------------------------------------------
# 13. Generar archivos de evidencia
# ---------------------------------------------------------------------
Write-Step "13. Generando archivos de evidencia en evidence\step4"

# transfer-proof.txt
$transferProof = @"
EVIDENCIA DE TRANSFERENCIA - PASO 4
Fecha: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

Referencia:        $reference
TransferId:        $transferId
Cuenta origen:      $($sourceAccount.id) (saldo antes: $($sourceAccount.balance))
Cuenta destino:      $($targetAccount.id) (saldo antes: $($targetAccount.balance))
Monto:              1.00
Estado inicial:      $initialState
Estado final:        $finalState
correlationId:       $correlationId
TraceId:             $traceId

Transferencias con esta referencia en la base de datos: $dupCount
Transferencia duplicada: $(Test-YesNo $isDuplicate)
"@
Set-Content -Path (Join-Path $evidenceDir "transfer-proof.txt") -Value $transferProof -Encoding utf8

# notification-proof.txt
$notificationProof = @"
EVIDENCIA DE NOTIFICACION TRANSFER_SENT - PASO 4
Fecha: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

Consulta ejecutada:
SELECT id, user_id, type, payload, created_at
FROM notifications.notifications
WHERE payload->>'transferId' = '$transferId';

Resultado:
$notifText

Notificacion TRANSFER_SENT persistida: $(Test-YesNo $notificationFound)
"@
Set-Content -Path (Join-Path $evidenceDir "notification-proof.txt") -Value $notificationProof -Encoding utf8

# queues-proof.txt
$queuesProof = @"
EVIDENCIA DE COLAS RABBITMQ - PASO 4
Fecha: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')

$queuesText
"@
Set-Content -Path (Join-Path $evidenceDir "queues-proof.txt") -Value $queuesProof -Encoding utf8

# tests-summary.txt
$testsSummaryLines = @()
$testsSummaryLines += "RESUMEN DE PRUEBAS - PASO 4"
$testsSummaryLines += "Fecha: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$testsSummaryLines += ""
foreach ($name in $testResults.Keys) {
    $status = "BUILD SUCCESS"
    if (-not $testResults[$name]) { $status = "BUILD FAILURE" }
    $testsSummaryLines += "$name : $status"
}
$testsSummaryLines += ""
$testsSummaryLines += "Pruebas aprobadas: $(Test-YesNo $allTestsPassed)"
Set-Content -Path (Join-Path $evidenceDir "tests-summary.txt") -Value ($testsSummaryLines -join "`n") -Encoding utf8

# same-traceid-logs.txt
$sameTraceLines = @()
$sameTraceLines += "MISMO TRACEID EN LOGS JSON - EVIDENCIA PASO 4"
$sameTraceLines += "TraceId: $traceId"
$sameTraceLines += "transferId: $transferId"
$sameTraceLines += "correlationId: $correlationId"
$sameTraceLines += ""

foreach ($name in $logFiles.Keys) {
    $sameTraceLines += ("=" * 60)
    $sameTraceLines += $name
    $sameTraceLines += ("=" * 60)

    $found = $logMatches[$name]

    if ($found.Count -eq 0) {
        $sameTraceLines += "Sin lineas disponibles con este TraceId."
    } else {
        $shown = $found | Select-Object -First 5
        $count = 0
        foreach ($line in $shown) {
            $count++
            $sameTraceLines += "$count) $($line.Line)"
        }
    }
    $sameTraceLines += ""
}

$sameTraceLines += "RESUMEN"
$sameTraceLines += "api-gateway:       $(Test-YesNo $traceInGateway)"
$sameTraceLines += "accounts-service:  $(Test-YesNo $traceInAccounts)"
$sameTraceLines += "transfers-service: $(Test-YesNo $traceInTransfers)"
$sameTraceLines += "modular-monolith:  $(Test-YesNo $traceInMonolith)"
$sameTraceLines += "Mismo TraceId en los cuatro logs: $(Test-YesNo $sameTraceInAllFour)"

Set-Content -Path (Join-Path $evidenceDir "same-traceid-logs.txt") -Value ($sameTraceLines -join "`n") -Encoding utf8

# step4-evidence.txt (archivo principal)
$transferCompleted = $finalState -eq "COMPLETED"

$mainLines = @()
$mainLines += "EVIDENCIA FINAL PASO 4 - TRAZA DISTRIBUIDA COMPLETA"
$mainLines += "Fecha: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$mainLines += "Referencia: $reference"
$mainLines += ""
$mainLines += "TransferId: $transferId"
$mainLines += "TraceId: $traceId"
$mainLines += "CorrelationId: $correlationId"
$mainLines += "Estado final: $finalState"
$mainLines += "Transferencia duplicada: $(Test-YesNo $isDuplicate)"
$mainLines += ""
$mainLines += "Cuenta origen: $($sourceAccount.id)"
$mainLines += "Cuenta destino: $($targetAccount.id)"
$mainLines += "Monto: 1.00"
$mainLines += ""
$mainLines += "Servicios en la traza de Jaeger: $($services -join ', ')"
$mainLines += ""
$mainLines += "RESUMEN"
$mainLines += "- Transferencia COMPLETED: $(Test-YesNo $transferCompleted)"
$mainLines += "- Duplicado por timeout o reintento: $(Test-YesNo $isDuplicate)"
$mainLines += "- API Gateway en Jaeger: $(Test-YesNo $hasGatewayInTrace)"
$mainLines += "- Transfers Service en Jaeger: $(Test-YesNo $hasTransfersInTrace)"
$mainLines += "- Accounts Service en Jaeger: $(Test-YesNo $hasAccountsInTrace)"
$mainLines += "- Monolito en Jaeger: $(Test-YesNo $hasMonolithInTrace)"
$mainLines += "- RabbitMQ send/receive en Jaeger: $(Test-YesNo ($hasRabbitSend -and $hasRabbitReceive))"
$mainLines += "- TraceId en api-gateway.json: $(Test-YesNo $traceInGateway)"
$mainLines += "- TraceId en accounts-service.json: $(Test-YesNo $traceInAccounts)"
$mainLines += "- TraceId en transfers-service.json: $(Test-YesNo $traceInTransfers)"
$mainLines += "- TraceId en modular-monolith.json: $(Test-YesNo $traceInMonolith)"
$mainLines += "- Mismo TraceId en los cuatro logs: $(Test-YesNo $sameTraceInAllFour)"
$mainLines += "- Notificacion TRANSFER_SENT persistida: $(Test-YesNo $notificationFound)"
$mainLines += "- Pruebas aprobadas: $(Test-YesNo $allTestsPassed)"

Set-Content -Path (Join-Path $evidenceDir "step4-evidence.txt") -Value ($mainLines -join "`n") -Encoding utf8

# ---------------------------------------------------------------------
# Limpieza de variables sensibles en memoria
# ---------------------------------------------------------------------
$jwt = $null
$loginResponse = $null
$authHeaders = $null
$transferHeaders = $null
$testEmail = $null
$testPassword = $null
[System.GC]::Collect()

Write-Step "Listo"
Write-Host "Archivos generados en: $evidenceDir" -ForegroundColor Cyan
Write-Host ($mainLines -join "`n")

if ($transferCompleted -and $sameTraceInAllFour -and $allTestsPassed) {
    exit 0
} else {
    exit 1
}
