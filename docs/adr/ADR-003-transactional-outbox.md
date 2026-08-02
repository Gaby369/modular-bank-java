# ADR-003: Uso de Transactional Outbox para publicar eventos

## Estado

Aprobado

## Fecha

2026-08-01

## Contexto

Transfers Service y Accounts Service deben modificar datos en PostgreSQL y
publicar eventos en RabbitMQ.

Una transferencia requiere que Transfers Service realice estas operaciones:

1. Guardar la transferencia con estado `PENDING`.
2. Crear el evento `TransferRequested.v1`.
3. Publicar el evento en RabbitMQ.

Accounts Service debe:

1. Actualizar los saldos.
2. Registrar el evento recibido como procesado.
3. Crear `TransferCompleted.v1` o `TransferFailed.v1`.
4. Publicar el resultado en RabbitMQ.

PostgreSQL y RabbitMQ son recursos transaccionales independientes. Una
transacción de base de datos no puede confirmar automáticamente una publicación
en RabbitMQ.

Sin un mecanismo adicional podrían ocurrir fallos parciales.

## Problema de consistencia

### Base de datos confirmada y evento no publicado

La transferencia puede quedar almacenada como `PENDING`, pero RabbitMQ podría
estar temporalmente fuera de servicio.

En ese caso, Accounts Service nunca recibiría la solicitud si el evento no
queda registrado para un reintento posterior.

### Evento publicado y base de datos revertida

También podría publicarse un evento antes de confirmar la transacción local.

Si la transacción se revierte después de publicar, otro servicio procesaría un
evento correspondiente a información que no existe en la base de datos.

## Opciones evaluadas

### Publicación directa en RabbitMQ

La aplicación guarda los datos y después publica inmediatamente el evento.

Ventajas:

- Implementación sencilla.
- Menor cantidad de tablas.
- Publicación rápida.

Desventajas:

- Existe una ventana de inconsistencia.
- El evento puede perderse después del commit.
- Puede publicarse un evento de una transacción revertida.
- La recuperación depende de lógica manual.

### Transacción distribuida

PostgreSQL y RabbitMQ participarían en una transacción coordinada.

Ventajas:

- Consistencia fuerte entre recursos.

Desventajas:

- Mayor complejidad operativa.
- Bajo acoplamiento con la infraestructura.
- Menor disponibilidad.
- RabbitMQ y la aplicación requerirían coordinación transaccional adicional.
- No es apropiado para la migración progresiva propuesta.

### Transactional Outbox

Los datos de negocio y el evento se almacenan en PostgreSQL dentro de la misma
transacción.

Un proceso independiente publica posteriormente los eventos pendientes en
RabbitMQ.

Ventajas:

- El dato de negocio y el evento se confirman atómicamente.
- Los eventos permanecen disponibles si RabbitMQ falla.
- Permite reintentos controlados.
- Facilita auditoría y diagnóstico.
- No requiere una transacción distribuida.

Desventajas:

- La publicación es eventual.
- Requiere una tabla adicional.
- Requiere un publicador programado.
- Puede publicar mensajes duplicados si existe un fallo después del envío y
  antes de marcar el evento como publicado.

## Decisión

Se utilizará el patrón Transactional Outbox en Transfers Service y Accounts
Service.

Cada servicio tendrá una tabla `outbox_events` en su propia base de datos.

~~~text
transfers.outbox_events
accounts.outbox_events
~~~

Los datos de negocio y el evento se guardarán dentro de la misma transacción
de PostgreSQL.

## Flujo en Transfers Service

~~~text
POST /transfers
  → validar solicitud
  → guardar Transfer con estado PENDING
  → guardar TransferRequested.v1 en transfers.outbox_events
  → confirmar una única transacción PostgreSQL
~~~

Un publicador programado consultará los registros `PENDING` y los enviará a
RabbitMQ.

## Flujo en Accounts Service

~~~text
Consumir TransferRequested.v1
  → verificar idempotencia
  → actualizar saldos
  → guardar processed_event
  → guardar TransferCompleted.v1 o TransferFailed.v1
     en accounts.outbox_events
  → confirmar una única transacción PostgreSQL
~~~

## Estructura del Outbox

Cada registro contiene:

| Campo | Responsabilidad |
|---|---|
| `id` | Identificador único del evento |
| `aggregate_type` | Tipo del agregado |
| `aggregate_id` | Identificador de la transferencia |
| `event_type` | Nombre y versión del evento |
| `routing_key` | Routing key de RabbitMQ |
| `correlation_id` | Identificador de trazabilidad |
| `payload` | Evento serializado como JSON |
| `status` | `PENDING`, `PUBLISHED` o `FAILED` |
| `attempts` | Cantidad de intentos de publicación |
| `created_at` | Fecha de creación |
| `published_at` | Fecha de publicación |
| `last_error` | Último error registrado |

## Estados

~~~text
PENDING
  → PUBLISHED
  → FAILED
~~~

`PENDING` indica que el evento todavía debe publicarse.

`PUBLISHED` indica que RabbitMQ aceptó el mensaje.

`FAILED` indica que se agotó el máximo de intentos del publicador.

## Publicador

Cada servicio contiene un `OutboxPublisher` ejecutado periódicamente mediante
Spring Scheduling.

El publicador:

1. Consulta hasta 50 eventos `PENDING`.
2. Construye un mensaje persistente.
3. Agrega `eventId`, `aggregateId`, `eventType` y `X-Correlation-Id`.
4. Publica el mensaje en `bank.transfers.exchange`.
5. Marca el registro como `PUBLISHED`.
6. Registra intentos y errores cuando la publicación falla.

## Garantía ofrecida

Transactional Outbox evita perder el evento entre la actualización local y la
publicación.

La solución no garantiza entrega exactamente una vez. La garantía efectiva es
al menos una vez.

Por esa razón, todos los consumidores deben ser idempotentes.

## Manejo de fallos

Cuando RabbitMQ no está disponible:

- La transacción de negocio puede confirmarse.
- El evento permanece en estado `PENDING`.
- El publicador vuelve a intentarlo posteriormente.
- El error se guarda en `last_error`.
- `attempts` aumenta en cada fallo.
- El evento cambia a `FAILED` al alcanzar el máximo permitido.

## Recuperación operativa

Los eventos `FAILED` pueden inspeccionarse en PostgreSQL.

Antes de reintentarlos deben analizarse:

- La causa registrada en `last_error`.
- La disponibilidad de RabbitMQ.
- La vigencia del evento.
- La existencia de un registro idempotente en el consumidor.

Después de corregir la causa, un operador puede devolver controladamente el
evento a `PENDING`.

## Observabilidad

Los eventos conservan `correlation_id`, lo que permite relacionar:

- La solicitud HTTP.
- La transferencia.
- El registro del Outbox.
- El mensaje de RabbitMQ.
- El procesamiento en Accounts Service.
- El evento de resultado.
- La actualización final en Transfers Service.

Actuator y Prometheus exponen métricas operativas de los servicios y RabbitMQ
permite observar mensajes y consumidores.

## Consecuencias positivas

- Consistencia atómica entre datos y eventos locales.
- Recuperación ante indisponibilidad de RabbitMQ.
- Auditoría de publicaciones.
- Reintentos controlados.
- Eliminación de la necesidad de transacciones distribuidas.
- Compatibilidad con bases de datos independientes.

## Consecuencias negativas

- Consistencia eventual.
- Mayor volumen de almacenamiento.
- Necesidad de limpiar o archivar eventos antiguos.
- Posibilidad de publicación duplicada.
- Necesidad de monitorear eventos `PENDING` y `FAILED`.

## Reversibilidad

La decisión tiene una reversibilidad media.

La publicación directa podría restaurarse, pero se perdería la garantía de no
perder eventos cuando RabbitMQ esté fuera de servicio.

Las tablas Outbox pueden conservarse como evidencia histórica incluso después
de cambiar el mecanismo de publicación.
