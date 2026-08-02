# ADR-006: Circuit Breaker y timeouts para Accounts Service

## Estado

Aprobado

## Fecha

2026-08-01

## Contexto

Transfers Service necesita consultar Accounts Service mediante HTTP para
verificar que la cuenta de origen pertenece al usuario autenticado.

La comunicación se realiza mediante `RestClient` y la API interna protegida con:

~~~text
X-Internal-Api-Key
~~~

Una dependencia HTTP remota puede fallar por distintas razones:

- Accounts Service está detenido.
- PostgreSQL de Accounts Service no está disponible.
- Existe una interrupción de red.
- La conexión tarda demasiado.
- Accounts Service está saturado.
- Se acumulan solicitudes esperando una respuesta.

Sin protección, Transfers Service podría continuar intentando conectarse a una
dependencia que ya se sabe que está indisponible.

Esto aumentaría la latencia, consumiría threads y podría producir fallos en
cascada.

## Opciones evaluadas

### Sin protección adicional

Transfers Service realiza cada llamada HTTP directamente y espera su resultado.

Ventajas:

- Implementación sencilla.
- Menor cantidad de dependencias.
- No requiere estados adicionales.

Desventajas:

- Cada solicitud intenta conectarse aunque Accounts Service esté caído.
- Puede producir tiempos de espera elevados.
- Consume recursos innecesariamente.
- Facilita fallos en cascada.
- No ofrece información explícita sobre el estado de la dependencia.

### Reintentos HTTP automáticos

Transfers Service vuelve a ejecutar la misma llamada varias veces.

Ventajas:

- Puede recuperar fallos transitorios muy breves.
- No requiere intervención del cliente.

Desventajas:

- Incrementa la carga sobre una dependencia saturada.
- Aumenta la latencia.
- Puede repetir operaciones no idempotentes.
- No evita llamadas continuas a un servicio completamente detenido.

### Circuit Breaker con timeouts

Cada llamada se ejecuta con límites de conexión y lectura. Resilience4j registra
los resultados y abre el circuito cuando la tasa de fallos supera el umbral.

Ventajas:

- Evita llamadas repetidas a una dependencia indisponible.
- Reduce latencia durante una caída.
- Limita el consumo de recursos.
- Permite recuperación controlada.
- Expone estado y métricas.
- Protege frente a fallos en cascada.

Desventajas:

- Introduce estados operativos adicionales.
- Requiere definir umbrales adecuados.
- Una configuración demasiado sensible puede abrir el circuito
  innecesariamente.
- Requiere observabilidad y pruebas.

## Decisión

Se utilizará Resilience4j para proteger las llamadas HTTP de Transfers Service
hacia Accounts Service.

El Circuit Breaker se identifica como:

~~~text
accountsService
~~~

La implementación protege las operaciones ejecutadas por:

~~~text
HttpAccountsClient
~~~

## Cliente HTTP

Transfers Service utiliza:

~~~text
Spring RestClient
~~~

El cliente configura:

~~~text
base URL
X-Internal-Api-Key
connect timeout
read timeout
Circuit Breaker
~~~

## Timeouts

Los valores predeterminados son:

~~~text
connect-timeout-ms: 2000
read-timeout-ms: 3000
~~~

Pueden modificarse mediante configuración externa.

El timeout de conexión limita el tiempo para establecer comunicación con
Accounts Service.

El timeout de lectura limita el tiempo de espera por una respuesta después de
establecer la conexión.

## Configuración del Circuit Breaker

La instancia `accountsService` utiliza:

~~~text
slidingWindowType: COUNT_BASED
slidingWindowSize: 5
minimumNumberOfCalls: 3
failureRateThreshold: 50
permittedNumberOfCallsInHalfOpenState: 2
waitDurationInOpenState: 10 segundos
automaticTransitionFromOpenToHalfOpenEnabled: true
eventConsumerBufferSize: 20
~~~

## Estados

### CLOSED

En estado `CLOSED`, las solicitudes HTTP se ejecutan normalmente.

Resilience4j registra sus resultados para calcular la tasa de fallos.

~~~text
CLOSED
  → llamada permitida
  → resultado registrado
~~~

### OPEN

Cuando se alcanza el mínimo de llamadas y la tasa de fallos es igual o superior
al 50 %, el circuito cambia a `OPEN`.

~~~text
CLOSED → OPEN
~~~

En este estado, las llamadas se rechazan inmediatamente sin intentar conectarse
a Accounts Service.

Transfers Service responde:

~~~text
HTTP 503 Service Unavailable
~~~

### HALF_OPEN

Después de diez segundos, el circuito cambia automáticamente a `HALF_OPEN`.

~~~text
OPEN → HALF_OPEN
~~~

En este estado se permiten dos llamadas de prueba.

Si las llamadas son exitosas:

~~~text
HALF_OPEN → CLOSED
~~~

Si vuelven a fallar:

~~~text
HALF_OPEN → OPEN
~~~

## Fallos registrados

El Circuit Breaker registra como fallo:

~~~text
AccountsServiceUnavailableException
~~~

Esta excepción representa errores técnicos de acceso a Accounts Service, por
ejemplo:

- Conexión rechazada.
- Timeout de conexión.
- Timeout de lectura.
- Error de acceso al recurso remoto.

## Excepciones ignoradas

Se ignoran para el cálculo del Circuit Breaker:

~~~text
ResponseStatusException
~~~

Esto evita abrir el circuito por respuestas HTTP válidas que representan
errores funcionales o de autorización.

Ejemplos:

- HTTP 400.
- HTTP 401.
- HTTP 403.
- HTTP 404.
- Error de negocio devuelto por Accounts Service.

Estas respuestas indican que el servicio remoto está disponible, aunque haya
rechazado la solicitud.

## Respuesta cuando la dependencia no está disponible

Cuando la conexión falla, Transfers Service devuelve:

~~~text
HTTP 503
Accounts Service is unavailable
~~~

Cuando el circuito está abierto, devuelve:

~~~text
HTTP 503
Accounts Service circuit breaker is OPEN
~~~

La transferencia no se registra porque el fallo ocurre durante la validación de
propiedad de la cuenta, antes de guardar:

~~~text
Transfer
OutboxEvent
~~~

## Alcance de la protección

El Circuit Breaker protege las operaciones HTTP del cliente de Accounts
Service.

Actualmente se utiliza principalmente para:

~~~text
GET /internal/accounts/owner/{userId}
~~~

La operación financiera principal de débito y crédito se procesa
asíncronamente mediante RabbitMQ dentro de Accounts Service.

Por ello, el Circuit Breaker protege la validación síncrona previa y no sustituye
el flujo de mensajería asíncrona.

## Observabilidad

Resilience4j integra sus métricas con Actuator y Prometheus.

Los endpoints expuestos incluyen:

~~~text
/actuator/circuitbreakers
/actuator/circuitbreakerevents
/actuator/prometheus
~~~

Las métricas permiten identificar los estados:

~~~text
closed
open
half_open
forced_open
disabled
metrics_only
~~~

También se registran en logs las transiciones:

~~~text
CLOSED_TO_OPEN
OPEN_TO_HALF_OPEN
HALF_OPEN_TO_CLOSED
HALF_OPEN_TO_OPEN
~~~

Los logs conservan `X-Correlation-Id` cuando la transición ocurre durante una
solicitud HTTP.

## Prueba realizada

La validación se ejecutó con Accounts Service inicialmente disponible.

El estado comprobado fue:

~~~text
closed = 1.0
open = 0.0
half_open = 0.0
~~~

Después se detuvo Accounts Service y se realizaron varias solicitudes.

Las respuestas fueron:

~~~text
HTTP 503
~~~

Al alcanzar el umbral se registró:

~~~text
CLOSED_TO_OPEN
~~~

Después del tiempo de espera se registró:

~~~text
OPEN_TO_HALF_OPEN
~~~

Accounts Service fue iniciado nuevamente y se ejecutaron dos solicitudes
correctas.

Finalmente se registró:

~~~text
HALF_OPEN_TO_CLOSED
~~~

La consulta a PostgreSQL confirmó que las solicitudes fallidas no crearon
transferencias.

## Relación con Transactional Outbox

El Circuit Breaker no reemplaza Transactional Outbox.

Cada mecanismo resuelve un problema diferente:

| Mecanismo | Responsabilidad |
|---|---|
| Circuit Breaker | Proteger llamadas HTTP remotas |
| Timeout | Limitar el tiempo de espera |
| Transactional Outbox | Evitar pérdida de eventos |
| RabbitMQ | Desacoplar el procesamiento |
| Idempotencia | Evitar efectos duplicados |
| Retry y DLQ | Manejar mensajes fallidos |

## Seguridad

La respuesta HTTP no expone información interna sensible.

Las API internas continúan protegidas mediante:

~~~text
X-Internal-Api-Key
~~~

Los secretos se configuran mediante variables de entorno y no deben incluirse
en logs ni eventos.

## Consecuencias positivas

- Reduce el impacto de caídas de Accounts Service.
- Evita intentos repetidos cuando el servicio está indisponible.
- Limita tiempos de conexión y lectura.
- Reduce el riesgo de fallos en cascada.
- Devuelve una respuesta HTTP consistente.
- Permite observar el estado de la dependencia.
- Facilita la recuperación automática.

## Consecuencias negativas

- Añade una dependencia de Resilience4j.
- Introduce estados adicionales.
- Requiere ajustar umbrales mediante métricas.
- Puede rechazar temporalmente llamadas durante la recuperación.
- Necesita pruebas específicas de apertura y cierre.

## Reversibilidad

La decisión tiene una reversibilidad media.

El cliente podría volver a ejecutar llamadas sin Circuit Breaker, pero perdería
protección ante fallos en cascada.

Los timeouts deben mantenerse incluso si se reemplaza Resilience4j por otro
mecanismo de resiliencia.
