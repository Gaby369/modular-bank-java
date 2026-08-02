# Arquitectura desplegada de FinBank

## Objetivo

Representar el estado actual de la migración progresiva desde el monolito
modular hacia microservicios mediante el patrón Strangler Fig.

Actualmente se extrajeron los dominios:

- Accounts.
- Transfers.

Los módulos restantes continúan dentro del monolito.

## Componentes desplegados

| Componente | Puerto | Responsabilidad |
|---|---:|---|
| API Gateway | 8080 | Punto de entrada y enrutamiento |
| Accounts Service | 8081 | Cuentas, saldos, débitos y créditos |
| Monolito remanente | 8082 | Autenticación y módulos heredados |
| Transfers Service | 8083 | Solicitudes e historial de transferencias |
| PostgreSQL del monolito | 5432 | Datos de módulos heredados |
| PostgreSQL de Accounts | 5433 | Base exclusiva de Accounts Service |
| PostgreSQL de Transfers | 5434 | Base exclusiva de Transfers Service |
| RabbitMQ | 5672 | Comunicación asíncrona |
| RabbitMQ Management | 15672 | Administración y observación del broker |

## Diagrama de componentes

~~~mermaid
flowchart LR
    Client[Cliente HTTP]

    Gateway[API Gateway<br/>Spring Cloud Gateway<br/>:8080]

    Monolith[Monolito remanente<br/>Spring Boot<br/>:8082]
    Accounts[Accounts Service<br/>Spring Boot<br/>:8081]
    Transfers[Transfers Service<br/>Spring Boot<br/>:8083]

    MonolithDB[(PostgreSQL<br/>modular_bank<br/>:5432)]
    AccountsDB[(PostgreSQL<br/>accounts_db<br/>:5433)]
    TransfersDB[(PostgreSQL<br/>transfers_db<br/>:5434)]

    RabbitMQ[RabbitMQ<br/>:5672]

    Client -->|HTTP + JWT| Gateway

    Gateway -->|/auth/** y rutas heredadas| Monolith
    Gateway -->|/accounts/**| Accounts
    Gateway -->|/transfers/**| Transfers

    Monolith --> MonolithDB
    Accounts --> AccountsDB
    Transfers --> TransfersDB

    Transfers -->|HTTP interno<br/>X-Internal-Api-Key<br/>validación de propiedad| Accounts

    Transfers -->|TransferRequested.v1| RabbitMQ
    RabbitMQ -->|transfer.requested.queue| Accounts

    Accounts -->|TransferCompleted.v1<br/>TransferFailed.v1| RabbitMQ
    RabbitMQ -->|completed / failed queues| Transfers
~~~

## Enrutamiento del API Gateway

~~~text
/auth/**       → Monolito remanente :8082
/accounts/**   → Accounts Service :8081
/transfers/**  → Transfers Service :8083
otras rutas    → Monolito remanente :8082
~~~

El Gateway implementa el patrón Strangler Fig, desviando únicamente las rutas
de los módulos ya extraídos.

## Comunicación síncrona

Transfers Service utiliza HTTP para validar que la cuenta origen pertenece al
usuario autenticado.

~~~text
Transfers Service
  → GET /internal/accounts/owner/{userId}
  → Accounts Service
~~~

La comunicación interna utiliza:

~~~text
X-Internal-Api-Key
~~~

El cliente HTTP está protegido mediante:

- Timeout de conexión.
- Timeout de lectura.
- Circuit Breaker de Resilience4j.

## Comunicación asíncrona

La ejecución financiera se coordina mediante RabbitMQ.

~~~text
Transfers Service
  → TransferRequested.v1
  → RabbitMQ
  → Accounts Service
  → movimiento de saldos
  → TransferCompleted.v1 o TransferFailed.v1
  → RabbitMQ
  → Transfers Service
  → estado final
~~~

## Topología de RabbitMQ

Exchange principal:

~~~text
bank.transfers.exchange
~~~

Dead Letter Exchange:

~~~text
bank.transfers.dlx
~~~

Colas principales:

~~~text
transfer.requested.queue
transfer.completed.queue
transfer.failed.queue
~~~

Dead Letter Queues:

~~~text
transfer.requested.dlq
transfer.completed.dlq
transfer.failed.dlq
~~~

## Persistencia por servicio

### Monolito remanente

Continúa utilizando la base heredada:

~~~text
modular_bank
~~~

Contiene los datos de autenticación y de los módulos que todavía no fueron
extraídos.

### Accounts Service

Utiliza:

~~~text
accounts_db
schema: accounts
~~~

Tablas relevantes:

~~~text
accounts.accounts
accounts.processed_events
accounts.outbox_events
accounts.flyway_schema_history
~~~

### Transfers Service

Utiliza:

~~~text
transfers_db
schema: transfers
~~~

Tablas relevantes:

~~~text
transfers.transfers
transfers.processed_events
transfers.outbox_events
transfers.flyway_schema_history
~~~

Ningún servicio accede directamente a las tablas de otro servicio.

## Consistencia

No existe una transacción distribuida entre Accounts Service y Transfers
Service.

La consistencia se controla mediante:

- Transactional Outbox.
- Eventos asíncronos.
- Consumidores idempotentes.
- Estados `PENDING`, `COMPLETED` y `FAILED`.
- Retry y Dead Letter Queues.

## Seguridad

### APIs públicas

Las rutas públicas de Accounts Service y Transfers Service requieren JWT.

### APIs internas

Accounts Service protege `/internal/accounts/**` mediante:

~~~text
X-Internal-Api-Key
~~~

### Observabilidad

`/actuator/health` es público.

Los demás endpoints de Actuator requieren autenticación.

## Trazabilidad

El encabezado utilizado es:

~~~text
X-Correlation-Id
~~~

El mismo identificador se conserva en:

- Solicitud HTTP.
- Logs de Transfers Service.
- Transfers Outbox.
- Mensaje `TransferRequested.v1`.
- Logs de Accounts Service.
- Accounts Outbox.
- Evento de resultado.
- Logs del consumidor final.

## Estado de la migración

~~~text
Extraído:
- Accounts Service
- Transfers Service

Permanece en el monolito:
- Auth
- Notifications
- Audit
- Otros componentes heredados
~~~

## Arquitectura de transición

La arquitectura actual es una etapa intermedia.

El monolito no se elimina de forma inmediata. El Gateway permite migrar rutas
gradualmente y también facilita un rollback hacia los componentes heredados
cuando sea necesario.
