# FinBank: migración progresiva a microservicios

FinBank es una aplicación bancaria implementada con Java y Spring Boot.

El proyecto comenzó como un monolito modular y actualmente se encuentra en una
arquitectura de transición basada en el patrón Strangler Fig.

Los dominios Accounts y Transfers fueron extraídos como microservicios
independientes. Los módulos restantes continúan ejecutándose dentro del
monolito remanente.

## Arquitectura actual

La solución está compuesta por:

| Componente | Puerto | Responsabilidad |
|---|---:|---|
| API Gateway | 8080 | Punto de entrada y enrutamiento |
| Accounts Service | 8081 | Cuentas, saldos, débitos y créditos |
| Monolito remanente | 8082 | Autenticación y módulos heredados |
| Transfers Service | 8083 | Creación y seguimiento de transferencias |
| PostgreSQL del monolito | 5432 | Persistencia heredada |
| PostgreSQL de Accounts | 5433 | Persistencia exclusiva de cuentas |
| PostgreSQL de Transfers | 5434 | Persistencia exclusiva de transferencias |
| RabbitMQ | 5672 | Mensajería asíncrona |
| RabbitMQ Management | 15672 | Administración del broker |

## Diagrama general

~~~mermaid
flowchart LR
    Client[Cliente HTTP]

    Gateway[API Gateway<br/>:8080]
    Monolith[Monolito remanente<br/>:8082]
    Accounts[Accounts Service<br/>:8081]
    Transfers[Transfers Service<br/>:8083]
    RabbitMQ[RabbitMQ<br/>:5672]

    MonolithDB[(PostgreSQL<br/>:5432)]
    AccountsDB[(Accounts PostgreSQL<br/>:5433)]
    TransfersDB[(Transfers PostgreSQL<br/>:5434)]

    Client -->|HTTP + JWT| Gateway

    Gateway -->|/auth/**| Monolith
    Gateway -->|/accounts/**| Accounts
    Gateway -->|/transfers/**| Transfers
    Gateway -->|rutas heredadas| Monolith

    Monolith --> MonolithDB
    Accounts --> AccountsDB
    Transfers --> TransfersDB

    Transfers -->|HTTP interno| Accounts

    Transfers -->|TransferRequested.v1| RabbitMQ
    RabbitMQ -->|transfer.requested.queue| Accounts

    Accounts -->|TransferCompleted.v1| RabbitMQ
    Accounts -->|TransferFailed.v1| RabbitMQ

    RabbitMQ -->|completed / failed queues| Transfers
~~~

## Enrutamiento

El API Gateway implementa el patrón Strangler Fig:

~~~text
/auth/**       → Monolito remanente :8082
/accounts/**   → Accounts Service :8081
/transfers/**  → Transfers Service :8083
otras rutas    → Monolito remanente :8082
~~~

## Estructura del repositorio

~~~text
.
├── docs/
│   ├── adr/
│   ├── diagrams/
│   ├── evidence/
│   └── migration/
├── services/
│   ├── accounts-service/
│   ├── api-gateway/
│   └── transfers-service/
├── src/
│   ├── main/
│   └── test/
├── docker-compose.yml
├── pom.xml
└── README.md
~~~

El proyecto raíz y cada carpeta dentro de `services` tienen su propio
`pom.xml`. No están configurados como módulos de un único proyecto Maven
padre, por lo que deben compilarse y ejecutarse individualmente.

## Requisitos

- Java 17 o superior.
- Maven 3.9 o superior.
- Docker.
- Docker Compose.
- Git.

Comprobar las versiones:

~~~bash
java -version
mvn -version
docker --version
docker compose version
git --version
~~~

## Infraestructura

Docker Compose levanta:

~~~text
postgres
accounts-postgres
transfers-postgres
rabbitmq
~~~

Iniciar la infraestructura:

~~~bash
docker compose up -d
~~~

Comprobar los contenedores:

~~~bash
docker compose ps
~~~

Detener la infraestructura:

~~~bash
docker compose down
~~~

Detener y eliminar también los volúmenes:

~~~bash
docker compose down -v
~~~

El último comando elimina los datos locales y debe utilizarse únicamente cuando
se necesite reinicializar completamente el entorno.

## Ejecución local

Cada aplicación debe ejecutarse en una terminal independiente.

### Terminal 1: Accounts Service

Desde la raíz del repositorio:

~~~bash
mvn -f services/accounts-service/pom.xml spring-boot:run
~~~

Puerto esperado:

~~~text
8081
~~~

### Terminal 2: Transfers Service

~~~bash
mvn -f services/transfers-service/pom.xml spring-boot:run
~~~

Puerto esperado:

~~~text
8083
~~~

### Terminal 3: monolito remanente

~~~bash
mvn spring-boot:run
~~~

Puerto esperado:

~~~text
8082
~~~

### Terminal 4: API Gateway

~~~bash
mvn -f services/api-gateway/pom.xml spring-boot:run
~~~

Puerto esperado:

~~~text
8080
~~~

## Orden recomendado de inicio

~~~text
1. Docker Compose
2. Accounts Service
3. Transfers Service
4. Monolito remanente
5. API Gateway
~~~

## Health checks

Accounts Service:

~~~bash
curl http://localhost:8081/actuator/health
~~~

Transfers Service:

~~~bash
curl http://localhost:8083/actuator/health
~~~

Respuesta esperada:

~~~json
{
  "status": "UP"
}
~~~

El endpoint de health es público. Los endpoints de métricas permanecen
protegidos.

## Compilación

### Monolito remanente

~~~bash
mvn clean package
~~~

### Accounts Service

~~~bash
mvn -f services/accounts-service/pom.xml clean package
~~~

### Transfers Service

~~~bash
mvn -f services/transfers-service/pom.xml clean package
~~~

### API Gateway

~~~bash
mvn -f services/api-gateway/pom.xml clean package
~~~

## Pruebas automatizadas

### Monolito remanente

~~~bash
mvn test
~~~

### Accounts Service

~~~bash
mvn -f services/accounts-service/pom.xml test
~~~

Pruebas principales:

~~~text
TransferRequestedProcessorTest
TransferFailureRecorderTest
~~~

Resultado validado:

~~~text
Tests run: 4
Failures: 0
Errors: 0
Skipped: 0
~~~

### Transfers Service

~~~bash
mvn -f services/transfers-service/pom.xml test
~~~

Pruebas principales:

~~~text
TransferResultProcessorTest
TransferUseCaseTest
~~~

Resultado validado:

~~~text
Tests run: 6
Failures: 0
Errors: 0
Skipped: 0
~~~

### API Gateway

~~~bash
mvn -f services/api-gateway/pom.xml test
~~~

## Flujo de transferencias

Una transferencia se procesa mediante consistencia eventual:

~~~text
POST /transfers
  → Transfer PENDING
  → Transactional Outbox
  → TransferRequested.v1
  → RabbitMQ
  → Accounts Service
  → débito y crédito
  → TransferCompleted.v1 o TransferFailed.v1
  → RabbitMQ
  → Transfers Service
  → COMPLETED o FAILED
~~~

## Estados

~~~text
PENDING → COMPLETED
PENDING → FAILED
~~~

`PENDING` indica que la solicitud fue aceptada, pero el movimiento financiero
todavía no tiene un resultado definitivo.

## RabbitMQ

### Exchange principal

~~~text
bank.transfers.exchange
~~~

### Colas principales

~~~text
transfer.requested.queue
transfer.completed.queue
transfer.failed.queue
~~~

### Dead Letter Exchange

~~~text
bank.transfers.dlx
~~~

### Dead Letter Queues

~~~text
transfer.requested.dlq
transfer.completed.dlq
transfer.failed.dlq
~~~

RabbitMQ Management está disponible localmente en:

~~~text
http://localhost:15672
~~~

Las credenciales deben obtenerse de la configuración del entorno y no deben
publicarse en documentación de producción.

## Transactional Outbox

Transfers Service guarda atómicamente:

~~~text
Transfer PENDING
+
TransferRequested.v1 en transfers.outbox_events
~~~

Accounts Service guarda atómicamente:

~~~text
Movimiento de saldos
+
processed_event
+
TransferCompleted.v1 o TransferFailed.v1
en accounts.outbox_events
~~~

Los publicadores Outbox envían posteriormente los eventos pendientes hacia
RabbitMQ.

## Idempotencia

Los consumidores almacenan los eventos procesados en:

~~~text
accounts.processed_events
transfers.processed_events
~~~

El campo `eventId` evita aplicar dos veces el mismo mensaje.

La solución utiliza una semántica efectiva de entrega al menos una vez, por lo
que todos los consumidores deben ser idempotentes.

## Retry y Dead Letter Queues

Los listeners realizan hasta tres intentos ante errores técnicos.

Después de agotar los intentos, el mensaje se rechaza sin requeue y RabbitMQ lo
dirige a la DLQ correspondiente.

Los errores funcionales esperados, como fondos insuficientes, se representan
mediante:

~~~text
TransferFailed.v1
~~~

## Comunicación HTTP interna

Transfers Service consulta Accounts Service para validar la propiedad de la
cuenta origen.

La comunicación interna utiliza:

~~~text
X-Internal-Api-Key
~~~

El cliente HTTP está protegido mediante:

- Timeout de conexión.
- Timeout de lectura.
- Circuit Breaker de Resilience4j.

## Circuit Breaker

La instancia configurada se denomina:

~~~text
accountsService
~~~

Estados principales:

~~~text
CLOSED
OPEN
HALF_OPEN
~~~

Cuando Accounts Service no está disponible, Transfers Service devuelve
`HTTP 503` y evita crear una transferencia durante la validación previa.

## Observabilidad

Accounts Service y Transfers Service integran:

- Spring Boot Actuator.
- Micrometer.
- Prometheus.
- Logs con MDC.
- Métricas de Resilience4j.
- `X-Correlation-Id`.

Endpoints principales:

~~~text
/actuator/health
/actuator/metrics
/actuator/prometheus
~~~

Transfers Service también expone información del Circuit Breaker.

## Correlation ID

El encabezado utilizado es:

~~~text
X-Correlation-Id
~~~

Cuando el cliente no lo proporciona, el servicio genera un UUID.

El mismo identificador se conserva en:

- Solicitud y respuesta HTTP.
- Logs.
- Transfers Outbox.
- Eventos de RabbitMQ.
- Accounts Outbox.
- Consumidores.
- Resultado final.

## Seguridad

Las APIs públicas utilizan JWT.

Las APIs internas utilizan `X-Internal-Api-Key`.

No deben almacenarse en Git:

- Contraseñas reales.
- JWT.
- Refresh tokens.
- API Keys de producción.
- Secretos de firma.
- Credenciales de RabbitMQ de producción.

Los valores locales predeterminados deben reemplazarse antes de desplegar el
sistema en otro ambiente.

## Bases de datos

Cada servicio es propietario exclusivo de su base.

~~~text
Monolito remanente  → PostgreSQL :5432
Accounts Service    → PostgreSQL :5433
Transfers Service   → PostgreSQL :5434
~~~

Ningún servicio debe acceder directamente a las tablas pertenecientes a otro
servicio.

Las migraciones se ejecutan mediante Flyway.

## Documentación

### Architecture Decision Records

- [ADR-001: elección del primer módulo](docs/adr/ADR-001-first-module.md)
- [ADR-002: comunicación asíncrona](docs/adr/ADR-002-asynchronous-transfer-messaging.md)
- [ADR-003: Transactional Outbox](docs/adr/ADR-003-transactional-outbox.md)
- [ADR-004: consumidores idempotentes](docs/adr/ADR-004-idempotent-event-consumers.md)
- [ADR-005: retry y Dead Letter Queues](docs/adr/ADR-005-retry-and-dead-letter-queues.md)
- [ADR-006: Circuit Breaker](docs/adr/ADR-006-accounts-service-circuit-breaker.md)
- [ADR-007: observabilidad y correlación](docs/adr/ADR-007-observability-and-correlation.md)

### Diagramas

- [Arquitectura original](docs/diagrams/current-architecture.md)
- [Arquitectura desplegada](docs/diagrams/deployed-microservices-architecture.md)
- [Secuencia asíncrona](docs/diagrams/async-transfer-sequence.md)

### Migración y rollback

- [Plan de migración y rollback](docs/migration/migration-and-rollback-plan.md)

### Evidencias

- [Extracción de Accounts Service](docs/evidence/step1-accounts-integration.md)
- [Arquitectura asíncrona de transferencias](docs/evidence/step3-async-transfers-evidence.md)

## Estrategia de migración

La migración sigue el patrón Strangler Fig:

~~~text
Monolito modular
  → extracción de Accounts Service
  → extracción de Transfers Service
  → comunicación asíncrona
  → resiliencia y observabilidad
~~~

El monolito remanente continúa disponible durante la transición.

El rollback depende del estado de los datos. Después de aceptar escrituras
nuevas en los microservicios, no es suficiente redirigir el Gateway: se requiere
reconciliación y migración inversa.

## Estado del proyecto

Implementado y validado:

- Accounts Service independiente.
- Transfers Service independiente.
- Base de datos por servicio.
- API Gateway.
- Seguridad JWT.
- API Key interna.
- RabbitMQ.
- Eventos versionados.
- Transactional Outbox.
- Consumidores idempotentes.
- Retry limitado.
- Dead Letter Queues.
- Pruebas automatizadas.
- Health checks.
- Métricas Prometheus.
- Correlation ID de extremo a extremo.
- Circuit Breaker.
- Plan de migración y rollback.
