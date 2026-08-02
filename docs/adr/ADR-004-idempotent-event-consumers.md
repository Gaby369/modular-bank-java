# ADR-004: Consumidores idempotentes para eventos de transferencias

## Estado

Aprobado

## Fecha

2026-08-01

## Contexto

La comunicación asíncrona con RabbitMQ utiliza una semántica de entrega al
menos una vez.

Un mensaje puede volver a entregarse cuando:

- El consumidor procesa el mensaje, pero falla antes de confirmarlo.
- Se pierde la conexión con RabbitMQ durante la confirmación.
- El servicio se reinicia durante el procesamiento.
- Un evento del Outbox se vuelve a publicar.
- Un operador reenvía un mensaje desde una Dead Letter Queue.

Por esta razón, los consumidores no pueden asumir que cada evento llegará una
sola vez.

En un sistema bancario, procesar dos veces el mismo evento puede producir
consecuencias graves.

Ejemplos:

- Descontar dos veces el saldo de la cuenta origen.
- Acreditar dos veces la cuenta destino.
- Cambiar repetidamente el estado de una transferencia.
- Crear eventos de resultado duplicados.
- Generar información de auditoría inconsistente.

## Opciones evaluadas

### Confiar únicamente en RabbitMQ

El consumidor procesa todos los mensajes recibidos suponiendo que RabbitMQ no
los duplicará.

Ventajas:

- Menor cantidad de código.
- No requiere tablas adicionales.

Desventajas:

- No cubre la semántica de entrega al menos una vez.
- No protege frente a reintentos o reconexiones.
- Puede duplicar movimientos financieros.
- No permite comprobar qué eventos fueron procesados.

### Consultar solamente el estado de la transferencia

Antes de procesar un resultado, el servicio consulta si la transferencia ya
está `COMPLETED` o `FAILED`.

Ventajas:

- Aprovecha el estado existente.
- No necesita una tabla de eventos procesados.

Desventajas:

- El estado del agregado no identifica el evento exacto.
- No protege todos los tipos de eventos.
- Puede producir condiciones de carrera.
- No conserva evidencia del mensaje procesado.
- Dificulta la evolución y el versionado de contratos.

### Registrar cada evento procesado

Cada consumidor guarda el `eventId` recibido en una tabla local dentro de la
misma transacción que modifica los datos de negocio.

Ventajas:

- Detecta eventos duplicados explícitamente.
- Mantiene evidencia del procesamiento.
- Protege las modificaciones financieras.
- Permite entrega al menos una vez de manera segura.
- Funciona con bases de datos independientes.

Desventajas:

- Requiere tablas adicionales.
- Los registros crecen con el tiempo.
- Se necesita una política de retención o archivado.
- Requiere una restricción única sobre `eventId`.

## Decisión

Cada microservicio consumidor implementará idempotencia persistente mediante
una tabla local `processed_events`.

Las tablas utilizadas son:

~~~text
accounts.processed_events
transfers.processed_events
~~~

El identificador `eventId` del contrato será la clave primaria.

## Estructura de los registros

Cada evento procesado contiene:

| Campo | Responsabilidad |
|---|---|
| `event_id` | Identificador único del evento |
| `transfer_id` | Transferencia relacionada |
| `event_type` | Tipo y versión del evento |
| `processed_at` | Fecha del procesamiento |

La clave primaria sobre `event_id` impide registrar dos veces el mismo evento.

## Consumidor de Accounts Service

Accounts Service consume:

~~~text
TransferRequested.v1
~~~

El procesamiento se realiza dentro de una transacción PostgreSQL:

~~~text
1. Consultar accounts.processed_events por eventId.
2. Si existe, finalizar sin modificar saldos.
3. Validar cuentas y fondos.
4. Ejecutar débito y crédito.
5. Crear TransferCompleted.v1 o TransferFailed.v1 en el Outbox.
6. Guardar eventId en accounts.processed_events.
7. Confirmar la transacción.
~~~

El registro idempotente, la actualización de saldos y el evento de resultado
se confirman de manera conjunta.

Si la transacción falla, ninguna de esas operaciones queda confirmada.

## Consumidor de Transfers Service

Transfers Service consume:

~~~text
TransferCompleted.v1
TransferFailed.v1
~~~

Su flujo es:

~~~text
1. Consultar transfers.processed_events por eventId.
2. Si existe, finalizar sin modificar la transferencia.
3. Buscar la transferencia relacionada.
4. Cambiar el estado a COMPLETED o FAILED.
5. Guardar eventId en transfers.processed_events.
6. Confirmar la transacción.
~~~

Un evento repetido no vuelve a aplicar el cambio de estado.

## Identidad del evento

Cada evento contiene un UUID propio:

~~~text
eventId
~~~

Este identificador no debe confundirse con:

~~~text
transferId
~~~

`transferId` identifica el agregado de negocio.

`eventId` identifica una ocurrencia específica dentro del flujo de eventos.

Una misma transferencia puede generar varios eventos distintos:

~~~text
TransferRequested.v1
TransferCompleted.v1
TransferFailed.v1
~~~

Cada evento tendrá su propio `eventId`, pero compartirá el mismo `transferId`.

## Orden transaccional

La comprobación y el registro del evento deben ejecutarse en la misma
transacción que las modificaciones de negocio.

No se considera seguro este orden:

~~~text
1. Marcar evento como procesado.
2. Confirmar.
3. Actualizar saldos.
~~~

Si el paso 3 falla, el evento quedaría marcado como procesado sin haber aplicado
el movimiento.

Tampoco es seguro:

~~~text
1. Actualizar saldos.
2. Confirmar.
3. Registrar evento procesado.
~~~

Si falla el paso 3, RabbitMQ podría entregar nuevamente el mensaje y duplicar el
movimiento.

El orden seleccionado confirma conjuntamente:

~~~text
Datos de negocio + processed_event + evento Outbox
~~~

## Concurrencia

Dos consumidores podrían intentar procesar simultáneamente el mismo evento.

La clave primaria sobre `event_id` actúa como última protección en la base de
datos.

La primera transacción que confirme el registro será válida.

La segunda transacción recibirá una violación de unicidad y deberá revertirse,
evitando que ambos procesamientos queden confirmados.

## Eventos duplicados

Cuando el evento ya existe en `processed_events`, el consumidor:

- No modifica cuentas.
- No modifica la transferencia.
- No crea un nuevo evento de resultado.
- No incrementa saldos ni débitos.
- Finaliza el procesamiento sin error de negocio.

Esto permite confirmar el mensaje duplicado y evita que RabbitMQ lo entregue
indefinidamente.

## Pruebas realizadas

La implementación incluye pruebas automatizadas para validar:

- Procesamiento de una solicitud nueva.
- Creación de `TransferCompleted.v1`.
- Creación de `TransferFailed.v1`.
- Actualización a `COMPLETED`.
- Actualización a `FAILED`.
- Omisión de eventos ya procesados.
- Ausencia de una segunda modificación de saldo.
- Ausencia de eventos de resultado duplicados.

También se realizó una prueba manual republicando el mismo evento del Outbox.

El resultado comprobado fue:

~~~text
processed_count = 1
result_events = 1
saldos sin una segunda modificación
~~~

## Retención

Los registros de `processed_events` no deben eliminarse mientras exista la
posibilidad de volver a recibir el evento correspondiente.

Una política futura de retención deberá considerar:

- Tiempo máximo de conservación de mensajes.
- Backups de RabbitMQ.
- Retención del Outbox.
- Periodo de auditoría financiera.
- Posibilidad de reprocesamiento manual.

La eliminación debe realizarse mediante archivado controlado y no mediante una
limpieza arbitraria.

## Observabilidad

Cada procesamiento conserva:

- `eventId`.
- `transferId`.
- `eventType`.
- `processedAt`.
- `X-Correlation-Id` en los registros de ejecución.

Esto permite investigar si un mensaje:

- Fue recibido.
- Fue procesado previamente.
- Fue ignorado por duplicidad.
- Produjo un evento de resultado.
- Modificó el estado de la transferencia.

## Consecuencias positivas

- Evita movimientos financieros duplicados.
- Permite entrega al menos una vez de forma segura.
- Mantiene evidencia persistente.
- Protege frente a reintentos y republicaciones.
- Facilita auditoría y diagnóstico.
- Reduce el impacto de reinicios de consumidores.

## Consecuencias negativas

- Aumenta el almacenamiento utilizado.
- Requiere políticas de retención.
- Añade consultas antes de cada procesamiento.
- Requiere controlar condiciones de carrera.
- No sustituye el monitoreo de mensajes fallidos.

## Reversibilidad

La decisión tiene una reversibilidad baja.

Eliminar la idempotencia persistente dejaría el sistema expuesto a mensajes
duplicados y a movimientos financieros repetidos.

Podría reemplazarse por otro mecanismo que ofrezca garantías equivalentes,
como una bandeja de entrada transaccional o una solución de deduplicación
externa, pero no debe eliminarse sin una alternativa comprobada.
