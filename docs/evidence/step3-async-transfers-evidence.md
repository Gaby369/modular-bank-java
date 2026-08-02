# Evidencia consolidada: arquitectura distribuida de transferencias

## Objetivo

Documentar las pruebas realizadas sobre la extracción de Accounts Service y
Transfers Service, la comunicación asíncrona mediante RabbitMQ, Transactional
Outbox, consumidores idempotentes, retry, Dead Letter Queues, observabilidad y
Circuit Breaker.

## Arquitectura validada

| Componente | Puerto | Responsabilidad |
|---|---:|---|
| API Gateway | 8080 | Enrutamiento mediante Strangler Fig |
| Accounts Service | 8081 | Cuentas, saldos, débitos y créditos |
| Monolito remanente | 8082 | Autenticación y módulos heredados |
| Transfers Service | 8083 | Creación y seguimiento de transferencias |
| PostgreSQL del monolito | 5432 | Datos heredados |
| PostgreSQL de Accounts | 5433 | Persistencia exclusiva de Accounts |
| PostgreSQL de Transfers | 5434 | Persistencia exclusiva de Transfers |
| RabbitMQ | 5672 | Transporte de eventos |
| RabbitMQ Management | 15672 | Administración del broker |

## Servicios de infraestructura

La solución utiliza los siguientes servicios de Docker Compose:

~~~text
postgres
accounts-postgres
transfers-postgres
rabbitmq
~~~

## Enrutamiento validado

~~~text
/auth/**       → Monolito remanente
/accounts/**   → Accounts Service
/transfers/**  → Transfers Service
~~~

Las solicitudes públicas utilizan JWT.

Las solicitudes internas hacia Accounts Service utilizan:

~~~text
X-Internal-Api-Key
~~~

## Contratos de eventos

Se definieron los siguientes contratos versionados:

~~~text
TransferRequested.v1
TransferCompleted.v1
TransferFailed.v1
~~~

Todos contienen identificadores independientes para:

~~~text
eventId
transferId
correlationId
~~~

## Topología de RabbitMQ

Exchange principal:

~~~text
bank.transfers.exchange
~~~

Colas principales:

~~~text
transfer.requested.queue
transfer.completed.queue
transfer.failed.queue
~~~

Dead Letter Exchange:

~~~text
bank.transfers.dlx
~~~

Dead Letter Queues:

~~~text
transfer.requested.dlq
transfer.completed.dlq
transfer.failed.dlq
~~~

## Transactional Outbox

Transfers Service almacena en una misma transacción:

~~~text
Transfer status=PENDING
+
TransferRequested.v1 en transfers.outbox_events
~~~

Accounts Service almacena conjuntamente:

~~~text
Movimiento de saldos
+
processed_event
+
TransferCompleted.v1 o TransferFailed.v1
en accounts.outbox_events
~~~

Los publicadores envían posteriormente los eventos pendientes hacia RabbitMQ.

## Consumidores idempotentes

Los servicios utilizan:

~~~text
accounts.processed_events
transfers.processed_events
~~~

El campo `eventId` evita que un mismo mensaje produzca efectos duplicados.

La validación comprobó que un evento repetido:

- No vuelve a modificar los saldos.
- No vuelve a cambiar el estado de la transferencia.
- No crea un segundo evento de resultado.
- Se reconoce como procesado previamente.

## Pruebas automatizadas

### Accounts Service

Clases cubiertas:

~~~text
TransferRequestedProcessorTest
TransferFailureRecorderTest
~~~

Resultado:

~~~text
Tests run: 4
Failures: 0
Errors: 0
Skipped: 0
~~~

Los casos verifican:

- Transferencia exitosa.
- Débito y crédito.
- Fondos insuficientes.
- Evento de resultado.
- Idempotencia.
- Ausencia de efectos duplicados.

### Transfers Service

Clases cubiertas:

~~~text
TransferResultProcessorTest
TransferUseCaseTest
~~~

Resultado:

~~~text
Tests run: 6
Failures: 0
Errors: 0
Skipped: 0
~~~

Los casos verifican:

- Creación de transferencias.
- Estado inicial `PENDING`.
- Creación atómica del evento Outbox.
- Resultado `COMPLETED`.
- Resultado `FAILED`.
- Consumo idempotente.

## Flujo exitoso de extremo a extremo

Se ejecutó una transferencia utilizando:

~~~text
X-Correlation-Id: reto5-e2e-001
reference: correlation-e2e-test-001
amount: 10
~~~

Flujo comprobado:

~~~text
POST /transfers
  → transferencia PENDING
  → TransferRequested.v1
  → RabbitMQ
  → Accounts Service
  → débito y crédito
  → TransferCompleted.v1
  → RabbitMQ
  → Transfers Service
  → transferencia COMPLETED
~~~

Resultado final:

~~~text
status = COMPLETED
~~~

Saldos observados después de la prueba:

| Cuenta | Saldo final |
|---|---:|
| Cuenta origen | 815.0000 |
| Cuenta destino | 435.0000 |

## Correlation ID

El identificador:

~~~text
reto5-e2e-001
~~~

se propagó a través de:

- Solicitud HTTP.
- Respuesta HTTP.
- Logs de Transfers Service.
- Transfers Outbox.
- Evento `TransferRequested.v1`.
- Accounts Service.
- Accounts Outbox.
- Evento `TransferCompleted.v1`.
- Consumidor final de Transfers Service.

También quedó persistido en:

~~~text
transfers.outbox_events.correlation_id
accounts.outbox_events.correlation_id
~~~

## Estado final de los Outbox

Los eventos generados durante la prueba terminaron en:

~~~text
PUBLISHED
~~~

Esto comprobó que los publicadores enviaron correctamente los eventos
pendientes hacia RabbitMQ.

## Estado final de RabbitMQ

Después de completar el flujo, las colas principales quedaron sin mensajes
pendientes:

~~~text
transfer.requested.queue = 0
transfer.completed.queue = 0
transfer.failed.queue = 0
~~~

Las Dead Letter Queues también quedaron vacías después de las pruebas:

~~~text
transfer.requested.dlq = 0
transfer.completed.dlq = 0
transfer.failed.dlq = 0
~~~

## Retry y Dead Letter Queue

Se publicó deliberadamente un mensaje inválido:

~~~text
{invalid-json
~~~

Routing key utilizada:

~~~text
transfer.requested.v1
~~~

El consumidor no pudo deserializarlo, agotó los intentos configurados y
RabbitMQ lo dirigió a:

~~~text
transfer.requested.dlq
~~~

La inspección confirmó la existencia del encabezado:

~~~text
x-death
~~~

También se verificó:

~~~text
exchange de destino = bank.transfers.dlx
cola original = transfer.requested.queue
reason = rejected
~~~

Después de registrar la evidencia, la DLQ fue purgada.

## Observabilidad

Accounts Service y Transfers Service exponen:

~~~text
/actuator/health
/actuator/metrics
/actuator/prometheus
~~~

Transfers Service también expone información del Circuit Breaker.

El endpoint público:

~~~text
/actuator/health
~~~

respondió con estado:

~~~text
UP
~~~

Los endpoints de métricas permanecen protegidos.

## Circuit Breaker

Transfers Service utiliza una instancia de Resilience4j llamada:

~~~text
accountsService
~~~

La prueba se realizó deteniendo temporalmente Accounts Service.

Las solicitudes fallidas devolvieron:

~~~text
HTTP 503
~~~

Se observaron las transiciones:

~~~text
CLOSED_TO_OPEN
OPEN_TO_HALF_OPEN
HALF_OPEN_TO_CLOSED
~~~

Mientras el circuito estaba abierto, Transfers Service rechazó rápidamente
nuevas llamadas sin continuar intentando conectarse.

Después de restaurar Accounts Service, las llamadas de prueba fueron exitosas y
el Circuit Breaker regresó a:

~~~text
CLOSED
~~~

Las solicitudes fallidas durante esta prueba no crearon transferencias ni
eventos Outbox.

## Timeouts HTTP

El cliente HTTP hacia Accounts Service utiliza límites de conexión y lectura.

Valores configurados:

~~~text
connect-timeout-ms = 2000
read-timeout-ms = 3000
~~~

Esto evita mantener indefinidamente solicitudes esperando una dependencia
indisponible.

## Consistencia comprobada

La solución evita utilizar una transacción distribuida.

La consistencia se obtiene mediante:

- Transacciones locales de PostgreSQL.
- Transactional Outbox.
- Estados `PENDING`, `COMPLETED` y `FAILED`.
- Eventos versionados.
- Consumidores idempotentes.
- Retry limitado.
- Dead Letter Queues.
- Correlation ID.

## Matriz de aceptación

| Criterio | Resultado |
|---|---|
| Accounts Service con base independiente | Aprobado |
| Transfers Service con base independiente | Aprobado |
| Enrutamiento mediante API Gateway | Aprobado |
| Seguridad JWT | Aprobado |
| Seguridad mediante API Key interna | Aprobado |
| Transferencia creada como `PENDING` | Aprobado |
| Publicación mediante Transactional Outbox | Aprobado |
| Consumo de `TransferRequested.v1` | Aprobado |
| Actualización atómica de saldos | Aprobado |
| Publicación de evento de resultado | Aprobado |
| Actualización a `COMPLETED` | Aprobado |
| Actualización a `FAILED` | Aprobado |
| Procesamiento idempotente | Aprobado |
| Retry limitado | Aprobado |
| Envío de mensaje inválido a DLQ | Aprobado |
| Health checks | Aprobado |
| Métricas Prometheus | Aprobado |
| Correlation ID de extremo a extremo | Aprobado |
| Circuit Breaker | Aprobado |
| Recuperación del Circuit Breaker | Aprobado |

## Conclusión

La arquitectura distribuida de transferencias fue validada correctamente.

Accounts Service y Transfers Service operan con bases de datos independientes.
RabbitMQ coordina el procesamiento asíncrono y Transactional Outbox evita perder
eventos ante fallos de publicación.

Los consumidores idempotentes protegen los saldos y estados frente a mensajes
duplicados. Retry y las Dead Letter Queues aíslan mensajes que no pueden
procesarse.

Actuator, Prometheus, los logs con Correlation ID y el Circuit Breaker
proporcionan visibilidad y resiliencia para el flujo distribuido.
