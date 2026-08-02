# ADR-002: Comunicación asíncrona de transferencias con RabbitMQ

## Estado

Aprobado

## Fecha

2026-08-01

## Contexto

Después de extraer Accounts Service y Transfers Service, la operación de una
transferencia quedó distribuida entre dos microservicios con bases de datos
independientes.

Transfers Service recibe la solicitud, valida la propiedad de la cuenta,
registra la transferencia y mantiene su estado. Accounts Service administra
las cuentas, los saldos y la operación financiera de débito y crédito.

Una integración exclusivamente síncrona mediante HTTP produciría acoplamiento
temporal y dificultaría la recuperación ante fallos parciales.

## Opciones evaluadas

### Comunicación HTTP síncrona

Ventajas:

- Implementación directa.
- Resultado inmediato.
- Menor infraestructura.

Desventajas:

- Acoplamiento temporal.
- Menor tolerancia a fallos.
- Recuperación compleja ante interrupciones de red.
- Riesgo de inconsistencias entre bases de datos.

### Comunicación asíncrona con RabbitMQ

Ventajas:

- Reduce el acoplamiento temporal.
- Permite retry y Dead Letter Queues.
- Facilita el procesamiento idempotente.
- Permite recuperar operaciones pendientes.

Desventajas:

- Introduce consistencia eventual.
- Aumenta la complejidad operativa.
- Requiere controlar duplicados y mensajes fallidos.
- Requiere observabilidad distribuida.

## Decisión

Se utilizará RabbitMQ para coordinar el procesamiento asíncrono de
transferencias.

Transfers Service almacenará la transferencia con estado `PENDING` y generará:

~~~text
TransferRequested.v1
~~~

Accounts Service procesará el movimiento de saldos y generará uno de estos
resultados:

~~~text
TransferCompleted.v1
TransferFailed.v1
~~~

Transfers Service consumirá el resultado y actualizará el estado:

~~~text
PENDING → COMPLETED
PENDING → FAILED
~~~

## Topología

Exchange principal:

~~~text
bank.transfers.exchange
~~~

Routing keys:

~~~text
transfer.requested.v1
transfer.completed.v1
transfer.failed.v1
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

## Flujo

~~~text
Cliente
  → API Gateway
  → Transfers Service
  → transfers_db y Transactional Outbox
  → TransferRequested.v1
  → RabbitMQ
  → Accounts Service
  → accounts_db y Transactional Outbox
  → TransferCompleted.v1 o TransferFailed.v1
  → RabbitMQ
  → Transfers Service
  → estado final
~~~

## Semántica de entrega e idempotencia

La solución utiliza entrega al menos una vez. RabbitMQ puede volver a entregar
un mensaje cuando ocurre un fallo antes de su confirmación.

Los consumidores registran los eventos procesados en:

~~~text
accounts.processed_events
transfers.processed_events
~~~

El campo `eventId` funciona como clave idempotente y evita aplicar dos veces
el mismo movimiento financiero o el mismo cambio de estado.

## Versionado

Los contratos contienen una versión explícita:

~~~text
TransferRequested.v1
TransferCompleted.v1
TransferFailed.v1
~~~

Un cambio incompatible deberá crear una nueva versión en lugar de modificar
silenciosamente el contrato existente.

## Manejo de errores

Los errores de negocio, como fondos insuficientes, generan
`TransferFailed.v1`.

Los errores técnicos se reintentan automáticamente. Al agotarse los intentos,
el mensaje se rechaza y se dirige a su Dead Letter Queue.

## Seguridad

RabbitMQ utiliza credenciales configurables mediante variables de entorno.

Los mensajes no contienen contraseñas, JWT ni API Keys. La comunicación HTTP
interna continúa protegida mediante `X-Internal-Api-Key`.

## Observabilidad

Los mensajes transportan `X-Correlation-Id`. Este valor permite relacionar la
solicitud HTTP, los eventos, los registros de ambos servicios y la actualización
final de la transferencia.

## Consecuencias positivas

- Menor acoplamiento entre microservicios.
- Mayor tolerancia a fallos temporales.
- Procesamiento recuperable e idempotente.
- Soporte para retry y DLQ.
- Trazabilidad distribuida.

## Consecuencias negativas

- Consistencia eventual visible mediante `PENDING`.
- Mayor complejidad de infraestructura.
- Necesidad de monitorear colas, consumidores y DLQ.
- Posibilidad de mensajes duplicados.

## Reversibilidad

La decisión tiene una reversibilidad media.

Los eventos todavía pendientes permanecen almacenados en los Outbox cuando
RabbitMQ no está disponible y pueden publicarse después de su recuperación.

Volver a una operación completamente síncrona requeriría modificar el contrato,
el manejo de estados y el modelo operativo de Transfers Service.
