# Secuencia de transferencia asíncrona

## Objetivo

Representar la coordinación entre API Gateway, Transfers Service, Accounts
Service, PostgreSQL y RabbitMQ durante una transferencia.

El flujo utiliza:

- Validación síncrona de propiedad de la cuenta.
- Transactional Outbox.
- Mensajería asíncrona.
- Consumidores idempotentes.
- Correlation ID.
- Retry y Dead Letter Queues.

## Participantes

| Participante | Responsabilidad |
|---|---|
| Cliente | Solicitar y consultar la transferencia |
| API Gateway | Enrutar las solicitudes |
| Transfers Service | Crear y administrar la transferencia |
| Transfers DB | Persistir transferencias, Outbox y eventos procesados |
| RabbitMQ | Transportar los eventos |
| Accounts Service | Validar y modificar saldos |
| Accounts DB | Persistir cuentas, Outbox y eventos procesados |

## Flujo exitoso

~~~mermaid
sequenceDiagram
    autonumber

    actor Client as Cliente
    participant Gateway as API Gateway
    participant Transfers as Transfers Service
    participant TransfersDB as Transfers PostgreSQL
    participant Broker as RabbitMQ
    participant Accounts as Accounts Service
    participant AccountsDB as Accounts PostgreSQL

    Client->>Gateway: POST /transfers<br/>JWT + X-Correlation-Id
    Gateway->>Transfers: Enrutar /transfers/**

    Transfers->>Accounts: Validar propiedad de cuenta<br/>X-Internal-Api-Key
    Accounts->>AccountsDB: Consultar cuenta y propietario
    AccountsDB-->>Accounts: Cuenta encontrada
    Accounts-->>Transfers: Cuenta válida para el usuario

    Transfers->>TransfersDB: BEGIN
    Transfers->>TransfersDB: INSERT transfer status=PENDING
    Transfers->>TransfersDB: INSERT TransferRequested.v1<br/>en outbox_events
    Transfers->>TransfersDB: COMMIT
    Transfers-->>Gateway: Transferencia aceptada<br/>status=PENDING
    Gateway-->>Client: Respuesta con identificador

    loop Publicador de Transfers Outbox
        Transfers->>TransfersDB: Consultar eventos PENDING
        TransfersDB-->>Transfers: TransferRequested.v1
        Transfers->>Broker: Publicar transfer.requested.v1<br/>X-Correlation-Id
        Broker-->>Transfers: Confirmación de publicación
        Transfers->>TransfersDB: Marcar Outbox PUBLISHED
    end

    Broker->>Accounts: Consumir TransferRequested.v1

    Accounts->>AccountsDB: BEGIN
    Accounts->>AccountsDB: Consultar processed_events por eventId
    AccountsDB-->>Accounts: Evento no procesado
    Accounts->>AccountsDB: Validar cuentas y fondos
    Accounts->>AccountsDB: Debitar cuenta origen
    Accounts->>AccountsDB: Acreditar cuenta destino
    Accounts->>AccountsDB: INSERT processed_event
    Accounts->>AccountsDB: INSERT TransferCompleted.v1<br/>en outbox_events
    Accounts->>AccountsDB: COMMIT
    Accounts-->>Broker: ACK TransferRequested.v1

    loop Publicador de Accounts Outbox
        Accounts->>AccountsDB: Consultar eventos PENDING
        AccountsDB-->>Accounts: TransferCompleted.v1
        Accounts->>Broker: Publicar transfer.completed.v1<br/>X-Correlation-Id
        Broker-->>Accounts: Confirmación de publicación
        Accounts->>AccountsDB: Marcar Outbox PUBLISHED
    end

    Broker->>Transfers: Consumir TransferCompleted.v1

    Transfers->>TransfersDB: BEGIN
    Transfers->>TransfersDB: Consultar processed_events por eventId
    TransfersDB-->>Transfers: Evento no procesado
    Transfers->>TransfersDB: UPDATE transfer status=COMPLETED
    Transfers->>TransfersDB: INSERT processed_event
    Transfers->>TransfersDB: COMMIT
    Transfers-->>Broker: ACK TransferCompleted.v1

    Client->>Gateway: Consultar transferencia
    Gateway->>Transfers: Enrutar consulta
    Transfers->>TransfersDB: Buscar transferencia
    TransfersDB-->>Transfers: status=COMPLETED
    Transfers-->>Gateway: Transferencia completada
    Gateway-->>Client: status=COMPLETED
~~~

## Transacción de Transfers Service

La creación inicial confirma atómicamente:

~~~text
Transfer con estado PENDING
+
TransferRequested.v1 en transfers.outbox_events
~~~

Si la transacción falla, ninguno de los dos registros queda almacenado.

## Transacción de Accounts Service

El procesamiento exitoso confirma conjuntamente:

~~~text
Débito de la cuenta origen
+
Crédito de la cuenta destino
+
TransferRequested.v1 registrado en processed_events
+
TransferCompleted.v1 en accounts.outbox_events
~~~

Esto evita que el movimiento financiero quede confirmado sin un evento de
resultado.

## Transacción final de Transfers Service

El consumidor de resultados confirma:

~~~text
Transfer status=COMPLETED
+
TransferCompleted.v1 registrado en processed_events
~~~

## Flujo de error de negocio

Cuando no existen fondos suficientes, Accounts Service no modifica los saldos.

~~~mermaid
sequenceDiagram
    autonumber

    participant Broker as RabbitMQ
    participant Accounts as Accounts Service
    participant AccountsDB as Accounts PostgreSQL
    participant Transfers as Transfers Service
    participant TransfersDB as Transfers PostgreSQL

    Broker->>Accounts: TransferRequested.v1
    Accounts->>AccountsDB: BEGIN
    Accounts->>AccountsDB: Verificar processed_events
    Accounts->>AccountsDB: Consultar cuentas y saldo
    AccountsDB-->>Accounts: Fondos insuficientes
    Accounts->>AccountsDB: INSERT processed_event
    Accounts->>AccountsDB: INSERT TransferFailed.v1 en Outbox
    Accounts->>AccountsDB: COMMIT
    Accounts-->>Broker: ACK TransferRequested.v1

    Accounts->>Broker: Publicar TransferFailed.v1
    Broker->>Transfers: Consumir TransferFailed.v1

    Transfers->>TransfersDB: BEGIN
    Transfers->>TransfersDB: Verificar processed_events
    Transfers->>TransfersDB: UPDATE transfer status=FAILED
    Transfers->>TransfersDB: Guardar motivo del fallo
    Transfers->>TransfersDB: INSERT processed_event
    Transfers->>TransfersDB: COMMIT
    Transfers-->>Broker: ACK TransferFailed.v1
~~~

El resultado de negocio es:

~~~text
PENDING → FAILED
~~~

Un error funcional no se envía a una DLQ cuando puede expresarse correctamente
mediante `TransferFailed.v1`.

## Mensaje duplicado

Cuando un consumidor recibe nuevamente el mismo `eventId`:

~~~mermaid
sequenceDiagram
    participant Broker as RabbitMQ
    participant Consumer as Servicio consumidor
    participant DB as PostgreSQL

    Broker->>Consumer: Evento con eventId repetido
    Consumer->>DB: Buscar eventId en processed_events
    DB-->>Consumer: Evento ya procesado
    Consumer-->>Broker: ACK sin aplicar cambios
~~~

El consumidor no vuelve a:

- Modificar saldos.
- Cambiar el estado de la transferencia.
- Crear otro evento de resultado.

## Error técnico y DLQ

Cuando el consumidor genera un error técnico:

~~~text
Intento 1
  → fallo
  → retry

Intento 2
  → fallo
  → retry

Intento 3
  → fallo
  → rechazo sin requeue
  → bank.transfers.dlx
  → Dead Letter Queue correspondiente
~~~

Las colas de error son:

~~~text
transfer.requested.dlq
transfer.completed.dlq
transfer.failed.dlq
~~~

## Correlation ID

El valor de `X-Correlation-Id` se conserva durante toda la secuencia:

~~~text
Solicitud HTTP
  → Transfers Outbox
  → TransferRequested.v1
  → Accounts Service
  → Accounts Outbox
  → TransferCompleted.v1 o TransferFailed.v1
  → Transfers Service
~~~

También se incorpora al MDC para relacionar los logs de ambos servicios.

## Consistencia

La operación no utiliza una transacción distribuida entre bases de datos.

La consistencia se obtiene mediante:

- Transacciones locales.
- Transactional Outbox.
- Estados intermedios.
- Mensajes persistentes.
- Consumidores idempotentes.
- Eventos de resultado.
- Reintentos limitados.
- Dead Letter Queues.

## Resultado final

Una transferencia aceptada inicialmente permanece en `PENDING` hasta recibir
un resultado.

Los estados posibles son:

~~~text
PENDING → COMPLETED
PENDING → FAILED
~~~

No existe una transición directa entre `COMPLETED` y `FAILED`.
