$ErrorActionPreference = "Continue"

$checks = @(
    @{Name="Gateway health"; Url="http://localhost:8080/actuator/health"},
    @{Name="Accounts health"; Url="http://localhost:8081/actuator/health"},
    @{Name="Monolith health"; Url="http://localhost:8082/actuator/health"},
    @{Name="Transfers health"; Url="http://localhost:8083/actuator/health"},
    @{Name="Jaeger"; Url="http://localhost:16686"},
    @{Name="Prometheus"; Url="http://localhost:9090/-/ready"},
    @{Name="Grafana"; Url="http://localhost:3000/api/health"},
    @{Name="Loki"; Url="http://localhost:3100/ready"},
    @{Name="RabbitMQ metrics"; Url="http://localhost:15692/metrics"}
)

foreach ($check in $checks) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $check.Url -TimeoutSec 5
        Write-Host ("[OK]   {0} -> HTTP {1}" -f $check.Name, $response.StatusCode)
    } catch {
        Write-Host ("[FAIL] {0} -> {1}" -f $check.Name, $_.Exception.Message)
    }
}
