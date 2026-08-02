# ADR-005: Reintentos y Dead Letter Queues para mensajes fallidos

## Estado

Aprobado

## Fecha

2026-08-01

## Contexto

Los consumidores de RabbitMQ pueden encontrar errores temporales o permanentes
durante el procesamiento de eventos.

Ejemplos de errores temporales:

- Interrupciones breves de PostgreSQL.
- Bloqueos o timeouts de base de datos.
- Saturación temporal del servicio.
- Errores transitorios de infraestructura.

Ejemplos de errores permanentes:

- Payload JSON inválido.
- Contrato de evento incompatible.
- Campos obligatorios ausentes.
- Identificadores con formato incorrecto.
- Evento que no puede deserializarse.

Sin una política controlada, un mensaje fallido podría volver a entregarse
indefinidamente, bloquear el consumidor y consumir recursos continuamente.

## Opciones evaluadas

### Requeue indefinido

Cuando ocurre un error, el mensaje vuelve inmediatamente a la cola original.

Ventajas:

- Implementación sencilla.
- No requiere colas adicionales.
- Puede resolver fallos transitorios muy breves.

Desventajas:

- Puede producir ciclos infinitos.
- Consume CPU y conexiones continuamente.
- Un mensaje inválido nunca podrá procesarse.
- Dificulta identificar mensajes problemáticos.
- Puede impedir el procesamiento normal de otros mensajes.

### Descartar el mensaje después del primer error

El consumidor rechaza y elimina el mensaje inmediatamente.

Ventajas:

- Evita ciclos de reentrega.
- Reduce la carga del consumidor.

Desventajas:

- Puede perder eventos ante fallos transitorios.
- No permite inspección posterior.
- No ofrece recuperación operativa.
- No es apropiado para operaciones financieras.

### Reintentos limitados y Dead Letter Queue

El consumidor realiza una cantidad limitada de reintentos. Si todos fallan,
rechaza el mensaje y RabbitMQ lo dirige a una Dead Letter Queue.

Ventajas:

- Recupera errores técnicos transitorios.
- Evita ciclos infinitos.
- Aísla mensajes que requieren revisión.
- Mantiene evidencia para diagnóstico.
- Permite reprocesamiento controlado.

Desventajas:

- Requiere exchanges, colas y bindings adicionales.
- Requiere monitoreo de las DLQ.
- Necesita un procedimiento operativo de recuperación.
- Los mensajes pueden permanecer sin procesar hasta la intervención humana.

## Decisión

Se utilizarán reintentos limitados en los listeners de Spring AMQP y Dead
Letter Queues en RabbitMQ.

La configuración aplicada es:

~~~text
enabled: true
initial-interval: 1 segundo
max-attempts: 3
multiplier: 2
max-interval: 5 segundos
default-requeue-rejected: false
~~~

Después de agotar los intentos, el mensaje se rechaza sin requeue y RabbitMQ lo
envía al Dead Letter Exchange.

## Topología

Dead Letter Exchange:

~~~text
bank.transfers.dlx
~~~

Colas principales y sus DLQ:

| Cola principal | Dead Letter Queue |
|---|---|
| `transfer.requested.queue` | `transfer.requested.dlq` |
| `transfer.completed.queue` | `transfer.completed.dlq` |
| `transfer.failed.queue` | `transfer.failed.dlq` |

Cada cola principal contiene estos argumentos:

~~~text
x-dead-letter-exchange
x-dead-letter-routing-key
~~~

## Routing keys de error

~~~text
transfer.requested.dlq
transfer.completed.dlq
transfer.failed.dlq
~~~

El Dead Letter Exchange es de tipo `direct`, por lo que cada mensaje se dirige
a la DLQ correspondiente mediante su routing key.

## Flujo de un mensaje fallido

~~~text
Mensaje recibido
  → intento 1
  → fallo
  → espera 1 segundo
  → intento 2
  → fallo
  → espera con backoff
  → intento 3
  → fallo
  → rechazo sin requeue
  → bank.transfers.dlx
  → Dead Letter Queue
~~~

## Clasificación de errores

### Errores de negocio

Los errores de negocio esperados no deben enviarse a una DLQ cuando pueden
representarse mediante un evento de resultado.

Ejemplo:

~~~text
Fondos insuficientes
  → TransferFailed.v1
~~~

El mensaje `TransferRequested.v1` queda procesado correctamente, pero el
resultado de negocio es una transferencia fallida.

### Errores técnicos

Los errores técnicos transitorios deben reintentarse.

Ejemplos:

- PostgreSQL temporalmente indisponible.
- Timeout de conexión.
- Error transitorio de infraestructura.

Si el problema desaparece durante los reintentos, el mensaje continúa su flujo
normal.

### Mensajes inválidos

Un JSON inválido o un contrato incompatible no podrá corregirse mediante
reintentos.

Después de agotar los intentos, el mensaje se envía a la DLQ para análisis.

## Encabezados de Dead Lettering

RabbitMQ agrega metadatos al mensaje enviado a una DLQ.

Entre ellos se encuentra:

~~~text
x-death
~~~

Este encabezado permite identificar:

- Cola original.
- Exchange original.
- Motivo del rechazo.
- Routing key original.
- Cantidad de veces que el mensaje fue enviado a una DLQ.
- Fecha aproximada del evento de dead lettering.

También pueden aparecer:

~~~text
x-first-death-exchange
x-first-death-queue
x-first-death-reason
x-last-death-exchange
x-last-death-queue
x-last-death-reason
~~~

## Prueba realizada

Se publicó deliberadamente este payload inválido:

~~~text
{invalid-json
~~~

El mensaje se envió con:

~~~text
routing_key = transfer.requested.v1
~~~

El consumidor no pudo deserializarlo, agotó los reintentos y RabbitMQ lo dirigió
a:

~~~text
transfer.requested.dlq
~~~

La inspección confirmó:

~~~text
exchange = bank.transfers.dlx
queue original = transfer.requested.queue
reason = rejected
payload = {invalid-json
~~~

Después de registrar la evidencia, la DLQ fue purgada y todas las colas quedaron
con cero mensajes.

## Operación de las DLQ

Las Dead Letter Queues no tienen consumidores automáticos en la implementación
actual.

Esto evita reprocesar indefinidamente un mensaje que requiere revisión.

Un operador debe:

1. Inspeccionar el payload y los encabezados.
2. Identificar la causa del fallo.
3. Corregir el contrato, dato o dependencia.
4. Verificar que el evento no fue procesado previamente.
5. Republicar el mensaje de forma controlada.
6. Confirmar el resultado.
7. Eliminar el mensaje original de la DLQ.

## Restricciones para el reprocesamiento

Antes de republicar un mensaje debe verificarse:

- El `eventId`.
- El `transferId`.
- El tipo y versión del evento.
- El contenido de `x-death`.
- Los registros de `processed_events`.
- El estado de la transferencia.
- Los eventos existentes en los Outbox.
- La disponibilidad de las dependencias.

La idempotencia protege frente a duplicados, pero no sustituye la revisión
operativa.

## Monitoreo

Se deben monitorear como mínimo:

- Cantidad de mensajes en cada cola principal.
- Cantidad de consumidores activos.
- Mensajes en cada DLQ.
- Mensajes no confirmados.
- Tasa de publicación y consumo.
- Errores de deserialización.
- Reintentos de listeners.
- Antigüedad del mensaje más antiguo.
- Eventos `FAILED` en los Outbox.

Una DLQ con mensajes debe generar una alerta operativa.

## Seguridad

Los mensajes de una DLQ pueden contener información de negocio.

El acceso a RabbitMQ Management y a las DLQ debe limitarse a personal
autorizado.

No deben registrarse en mensajes:

- Contraseñas.
- JWT.
- API Keys.
- Secretos de configuración.

## Consecuencias positivas

- Evita ciclos infinitos de reentrega.
- Recupera fallos transitorios.
- Aísla mensajes permanentes o incompatibles.
- Conserva evidencia para diagnóstico.
- Permite recuperación manual controlada.
- Evita bloquear las colas principales.

## Consecuencias negativas

- Requiere monitoreo adicional.
- Puede acumular mensajes pendientes de intervención.
- Incrementa la complejidad operativa.
- El reprocesamiento incorrecto puede generar efectos no deseados.
- Se necesita un procedimiento formal de atención de DLQ.

## Reversibilidad

La decisión tiene una reversibilidad baja.

Eliminar las DLQ implicaría descartar mensajes o permitir reintentos
indefinidos, ambas opciones menos seguras para un sistema financiero.

La política de cantidad de intentos, intervalos y backoff sí puede ajustarse
según métricas reales sin modificar los contratos de eventos.
