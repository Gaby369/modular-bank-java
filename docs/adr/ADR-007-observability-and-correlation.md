# ADR-007: Observabilidad y trazabilidad mediante Correlation ID

## Estado

Aprobado

## Fecha

2026-08-01

## Contexto

La migración de FinBank introdujo varios procesos independientes:

- API Gateway.
- Monolito remanente.
- Accounts Service.
- Transfers Service.
- PostgreSQL independiente por servicio.
- RabbitMQ.
- Publicadores Transactional Outbox.
- Consumidores asíncronos.

Una transferencia ya no se procesa dentro de una única aplicación ni de una
única transacción.

La operación atraviesa solicitudes HTTP, eventos persistidos, mensajes de
RabbitMQ y transacciones en bases de datos diferentes.

Sin observabilidad distribuida sería difícil responder preguntas como:

- ¿Qué servicio recibió una solicitud?
- ¿Qué evento corresponde a una transferencia?
- ¿El evento fue publicado?
- ¿Accounts Service procesó el movimiento?
- ¿El resultado fue recibido por Transfers Service?
- ¿El mensaje terminó en una DLQ?
- ¿El Circuit Breaker estaba abierto?
- ¿Qué registros pertenecen a la misma operación?

## Opciones evaluadas

### Registros independientes sin identificador común

Cada servicio registra sus propios mensajes utilizando fecha, nivel y clase.

Ventajas:

- No requiere cambios en las solicitudes ni eventos.
- Implementación mínima.

Desventajas:

- No permite seguir una operación completa.
- Es difícil relacionar logs entre servicios.
- Las marcas de tiempo no garantizan una asociación precisa.
- Complica el diagnóstico de eventos duplicados o retrasados.

### Utilizar únicamente transferId

El identificador de la transferencia se agrega a todos los registros.

Ventajas:

- Relaciona mensajes con el agregado de negocio.
- Ya existe dentro de los eventos.

Desventajas:

- No está disponible antes de crear la transferencia.
- Una misma transferencia genera varios eventos.
- No distingue solicitudes repetidas.
- No permite seguir health checks u otras llamadas HTTP.
- No sustituye un identificador de ejecución.

### Correlation ID propagado entre HTTP y RabbitMQ

Cada solicitud recibe un identificador de correlación. El mismo valor se
conserva durante todo el procesamiento distribuido.

Ventajas:

- Relaciona HTTP, Outbox, RabbitMQ y consumidores.
- Permite buscar una operación en múltiples servicios.
- Funciona antes de crear el agregado.
- Facilita diagnóstico y auditoría.
- Puede ser proporcionado por el cliente o generado automáticamente.

Desventajas:

- Requiere propagación explícita.
- Debe validarse para evitar valores inseguros.
- Debe limpiarse del contexto después de cada ejecución.
- No sustituye un sistema de tracing distribuido completo.

## Decisión

Se implementará observabilidad básica mediante:

- Spring Boot Actuator.
- Micrometer.
- Prometheus.
- Logs estructurados con MDC.
- Encabezado `X-Correlation-Id`.
- Métricas de Resilience4j.
- Inspección operativa de RabbitMQ y PostgreSQL.

## Actuator

Accounts Service y Transfers Service exponen:

~~~text
/actuator/health
/actuator/info
/actuator/metrics
/actuator/prometheus
~~~

Transfers Service también expone:

~~~text
/actuator/circuitbreakers
/actuator/circuitbreakerevents
~~~

## Seguridad de los endpoints

El health check es público:

~~~text
/actuator/health
/actuator/health/**
~~~

Esto permite que herramientas de infraestructura comprueben la disponibilidad
sin utilizar JWT.

Los demás endpoints de Actuator requieren autenticación:

~~~text
/actuator/metrics
/actuator/prometheus
/actuator/circuitbreakers
/actuator/circuitbreakerevents
~~~

Las métricas no deben exponerse públicamente porque pueden revelar información
operativa sensible.

## Etiquetas de métricas

Cada servicio utiliza una etiqueta de aplicación:

~~~text
application=accounts-service
application=transfers-service
~~~

Esto permite diferenciar métricas cuando se recopilan en un mismo sistema de
monitorización.

## Health checks

El health check responde con una estructura mínima:

~~~json
{
  "status": "UP"
}
~~~

Los detalles internos no se muestran públicamente.

La configuración utiliza:

~~~text
show-details: never
~~~

## Correlation ID HTTP

El encabezado utilizado es:

~~~text
X-Correlation-Id
~~~

Cuando el cliente proporciona un valor válido, el servicio lo conserva y lo
devuelve en la respuesta.

Ejemplo:

~~~text
Solicitud:
X-Correlation-Id: reto5-e2e-001

Respuesta:
X-Correlation-Id: reto5-e2e-001
~~~

Cuando el cliente no envía el encabezado, el servicio genera un UUID.

## Validación del identificador

El valor aceptado debe cumplir:

~~~text
[A-Za-z0-9._-]{1,100}
~~~

Esta validación evita:

- Valores excesivamente largos.
- Saltos de línea.
- Inyección de contenido en logs.
- Caracteres no controlados.

Si el valor no es válido, se genera un nuevo UUID.

## Uso de MDC

El identificador se almacena temporalmente en:

~~~text
MDC["correlationId"]
~~~

El patrón de logs incluye:

~~~text
[correlationId=valor]
~~~

Ejemplo:

~~~text
[correlationId=reto5-e2e-001]
HTTP request completed method=POST path=/transfers status=202
~~~

El valor se elimina mediante un bloque `finally` después de cada solicitud o
mensaje.

Esto evita que un thread reutilizado conserve el identificador de una operación
anterior.

## Propagación por Transactional Outbox

Las tablas Outbox contienen:

~~~text
correlation_id VARCHAR(100)
~~~

El identificador se guarda junto con el evento dentro de la misma transacción.

Tablas:

~~~text
transfers.outbox_events
accounts.outbox_events
~~~

Esto conserva la trazabilidad aunque RabbitMQ esté temporalmente fuera de
servicio y el mensaje se publique posteriormente.

## Propagación por RabbitMQ

Los publicadores agregan al mensaje:

~~~text
X-Correlation-Id
~~~

También incluyen:

~~~text
eventId
aggregateId
eventType
~~~

El encabezado de correlación no reemplaza a esos identificadores.

Cada valor tiene una responsabilidad diferente:

| Identificador | Responsabilidad |
|---|---|
| `correlationId` | Relacionar una ejecución distribuida |
| `eventId` | Identificar un evento único |
| `transferId` | Identificar la transferencia |
| `aggregateId` | Identificar el agregado asociado |

## Consumers

Accounts Service recupera `X-Correlation-Id` al consumir
`TransferRequested.v1`.

El valor se agrega al MDC y se conserva al crear:

~~~text
TransferCompleted.v1
TransferFailed.v1
~~~

Transfers Service recupera nuevamente el mismo encabezado al consumir el evento
de resultado.

De esta forma, el mismo identificador atraviesa:

~~~text
Solicitud HTTP
  → Transfers Service
  → Transfers Outbox
  → RabbitMQ
  → Accounts Service
  → Accounts Outbox
  → RabbitMQ
  → Transfers Service
~~~

## Identificador ausente en un mensaje

Un mensaje heredado o publicado manualmente puede no contener
`X-Correlation-Id`.

En ese caso, el consumidor genera un UUID nuevo.

Esto garantiza que los logs de procesamiento siempre tengan un identificador,
aunque no sea posible relacionarlo con una solicitud HTTP anterior.

## Métricas de RabbitMQ

RabbitMQ permite revisar:

- Cantidad de mensajes listos.
- Mensajes no confirmados.
- Consumidores activos.
- Bindings.
- Exchanges.
- Mensajes en DLQ.
- Encabezados `x-death`.

La disponibilidad de consumidores se comprobó mediante:

~~~text
rabbitmqctl list_queues name messages consumers
~~~

## Métricas del Circuit Breaker

Resilience4j publica estados como:

~~~text
closed
open
half_open
forced_open
disabled
metrics_only
~~~

Las transiciones también se registran:

~~~text
CLOSED_TO_OPEN
OPEN_TO_HALF_OPEN
HALF_OPEN_TO_CLOSED
~~~

Esto permite diagnosticar fallos en la comunicación HTTP entre Transfers
Service y Accounts Service.

## Evidencia realizada

Se ejecutó una transferencia con:

~~~text
X-Correlation-Id: reto5-e2e-001
~~~

La transferencia terminó en:

~~~text
COMPLETED
~~~

El mismo identificador quedó almacenado en:

~~~text
transfers.outbox_events.correlation_id
accounts.outbox_events.correlation_id
~~~

Los eventos correspondientes quedaron en estado:

~~~text
PUBLISHED
~~~

Las colas principales y las DLQ finalizaron con cero mensajes pendientes.

## Información que no debe registrarse

Los logs, eventos y métricas no deben contener:

- Contraseñas.
- JWT completos.
- Refresh tokens.
- API Keys.
- Secretos de RabbitMQ.
- Secretos de firma.
- Datos sensibles que no sean necesarios para el diagnóstico.

## Limitaciones

La implementación actual proporciona correlación y métricas básicas, pero no
incluye todavía:

- OpenTelemetry.
- Trazas distribuidas completas.
- Span ID.
- Trace ID estándar W3C.
- Exportación hacia Jaeger o Zipkin.
- Dashboards de Grafana.
- Alertas automáticas.

`X-Correlation-Id` puede migrarse posteriormente hacia estándares como
`traceparent`.

## Consecuencias positivas

- Permite seguir una transferencia de extremo a extremo.
- Mejora el diagnóstico de fallos.
- Relaciona registros de distintos servicios.
- Conserva la trazabilidad en el Outbox.
- Facilita la observación del Circuit Breaker.
- Permite integrar Prometheus.
- Reduce el tiempo de investigación operativa.

## Consecuencias negativas

- Añade lógica de propagación.
- Requiere disciplina para conservar el identificador.
- Incrementa ligeramente el tamaño de mensajes y tablas.
- No proporciona tracing distribuido completo.
- Las métricas protegidas requieren autenticación para consultarse.

## Reversibilidad

La decisión tiene una reversibilidad media.

Actuator, Micrometer y el formato de logs pueden sustituirse por otras
herramientas.

Sin embargo, eliminar la correlación reduciría significativamente la capacidad
de investigar operaciones distribuidas.

La columna `correlation_id` puede mantenerse como dato histórico aunque se
adopte posteriormente OpenTelemetry.
