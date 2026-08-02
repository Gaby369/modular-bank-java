# Plan de migración de datos, despliegue y rollback

## Estado

Vigente

## Fecha

2026-08-01

## Objetivo

Definir una estrategia controlada para migrar FinBank desde el monolito modular
hacia Accounts Service y Transfers Service, minimizando pérdida de datos,
inconsistencias y tiempo de indisponibilidad.

El plan también establece las condiciones y procedimientos de rollback.

## Alcance

Este documento cubre:

- Extracción de Accounts Service.
- Extracción de Transfers Service.
- Bases de datos independientes.
- Enrutamiento mediante API Gateway.
- Comunicación HTTP interna.
- Comunicación asíncrona mediante RabbitMQ.
- Transactional Outbox.
- Consumidores idempotentes.
- Reintentos y Dead Letter Queues.
- Validaciones posteriores al despliegue.
- Reversión técnica y de datos.

## Componentes involucrados

| Componente | Puerto | Función |
|---|---:|---|
| API Gateway | 8080 | Enrutamiento y patrón Strangler Fig |
| Accounts Service | 8081 | Cuentas y saldos |
| Monolito remanente | 8082 | Auth y módulos heredados |
| Transfers Service | 8083 | Transferencias |
| PostgreSQL heredado | 5432 | Datos del monolito |
| Accounts PostgreSQL | 5433 | Datos exclusivos de Accounts |
| Transfers PostgreSQL | 5434 | Datos exclusivos de Transfers |
| RabbitMQ | 5672 | Eventos de transferencias |
| RabbitMQ Management | 15672 | Operación del broker |

## Principios

1. Migrar un dominio por vez.
2. Mantener identificadores y relaciones.
3. No eliminar datos heredados antes de la aceptación.
4. Realizar backups antes de cada cutover.
5. Evitar escrituras simultáneas en dos fuentes de verdad.
6. Validar datos antes y después del cambio de tráfico.
7. No compartir tablas entre microservicios.
8. Ejecutar rollback solamente con un estado de datos conocido.
9. Conservar trazabilidad mediante `X-Correlation-Id`.
10. Documentar cada decisión y resultado.

## Fuente de verdad

Durante cada etapa debe existir una sola fuente de verdad para cada dominio.

| Etapa | Accounts | Transfers |
|---|---|---|
| Antes de la extracción | Monolito | Monolito |
| Después de Accounts | Accounts Service | Monolito |
| Después de Transfers | Accounts Service | Transfers Service |

No se considera seguro permitir escrituras normales simultáneas en el esquema
heredado y en la base nueva sin un mecanismo explícito de sincronización.

## Estrategia Strangler Fig

El API Gateway permite cambiar gradualmente el destino de las rutas.

~~~text
/auth/**       → Monolito remanente
/accounts/**   → Accounts Service
/transfers/**  → Transfers Service
otras rutas    → Monolito remanente
~~~

El monolito continúa disponible mientras los módulos se extraen progresivamente.

## Fase 0: preparación

Antes de migrar se debe comprobar:

- Código versionado en Git.
- Pruebas automatizadas aprobadas.
- Imágenes o ejecutables reproducibles.
- Docker Compose validado.
- Variables de entorno configuradas.
- Secretos fuera del repositorio.
- Bases de datos disponibles.
- Flyway ejecutado correctamente.
- RabbitMQ disponible.
- Colas y exchanges creados.
- Endpoints de health en estado `UP`.
- Procedimiento de rollback aprobado.

## Fase 1: respaldo

Antes del cutover se debe generar un respaldo consistente de:

~~~text
PostgreSQL del monolito
Accounts PostgreSQL
Transfers PostgreSQL
~~~

También debe conservarse:

- Versión del código.
- Commit desplegado.
- Variables de configuración no sensibles.
- Estado de las migraciones Flyway.
- Cantidad de registros por tabla.
- Estado de las colas RabbitMQ.
- Eventos pendientes en los Outbox.

El respaldo debe probarse mediante una restauración en un entorno aislado.

## Fase 2: aprovisionamiento

Se crean las bases independientes:

~~~text
accounts_db
transfers_db
~~~

Cada servicio ejecuta sus propias migraciones Flyway.

Accounts Service crea, entre otras:

~~~text
accounts.accounts
accounts.processed_events
accounts.outbox_events
~~~

Transfers Service crea:

~~~text
transfers.transfers
transfers.processed_events
transfers.outbox_events
~~~

Ningún servicio debe utilizar credenciales con acceso de escritura a la base de
otro servicio.

## Fase 3: migración de Accounts

### Preparación

1. Activar una ventana controlada de migración.
2. Detener temporalmente escrituras sobre cuentas heredadas.
3. Confirmar que no existen transacciones pendientes.
4. Generar un backup del esquema heredado.
5. Registrar el conteo y los saldos existentes.

### Copia de datos

Se deben migrar preservando:

- Identificador de cuenta.
- Identificador del propietario.
- Número o referencia de cuenta.
- Saldo.
- Moneda, cuando aplique.
- Estado.
- Fechas de creación y modificación.

### Validaciones

Se debe comparar:

- Cantidad total de cuentas.
- Cantidad por estado.
- Identificadores mínimos y máximos.
- Suma total de saldos.
- Registros sin propietario.
- Saldos negativos no esperados.
- Duplicados.
- Muestra de cuentas individuales.

La migración no debe aprobarse si existen diferencias no justificadas.

### Cutover de Accounts

Después de validar los datos:

1. Iniciar Accounts Service.
2. Confirmar `/actuator/health`.
3. Probar autenticación JWT.
4. Probar API interna con `X-Internal-Api-Key`.
5. Cambiar `/accounts/**` en el Gateway.
6. Activar el cliente remoto del monolito.
7. Ejecutar pruebas de lectura y escritura.
8. Mantener el esquema heredado sin eliminarlo.

## Fase 4: migración de Transfers

### Preparación

1. Confirmar estabilidad de Accounts Service.
2. Detener escrituras sobre transferencias heredadas.
3. Esperar operaciones en ejecución.
4. Respaldar el esquema de transferencias.
5. Registrar conteos y estados.

### Copia de datos

Se deben conservar:

- Identificador de transferencia.
- Cuenta origen.
- Cuenta destino.
- Monto.
- Estado.
- Motivo del fallo.
- Referencia.
- Fechas de creación y actualización.

### Validaciones

Se debe comparar:

- Total de transferencias.
- Cantidad por estado.
- Montos agregados.
- Referencias duplicadas.
- Transferencias sin cuenta relacionada.
- Identificadores.
- Fechas.
- Muestra de operaciones individuales.

### Cutover de Transfers

1. Iniciar Transfers Service.
2. Confirmar `/actuator/health`.
3. Confirmar conexión con Transfers PostgreSQL.
4. Validar acceso HTTP hacia Accounts Service.
5. Confirmar estado `CLOSED` del Circuit Breaker.
6. Cambiar `/transfers/**` en el Gateway.
7. Ejecutar pruebas de creación y consulta.
8. Mantener temporalmente el módulo heredado.

## Fase 5: activación del flujo asíncrono

Antes de aceptar transferencias se debe comprobar:

- RabbitMQ disponible.
- Exchange principal creado.
- Dead Letter Exchange creado.
- Colas principales creadas.
- DLQ creadas.
- Bindings correctos.
- Consumidores activos.
- Publicadores Outbox activos.
- Tablas `processed_events` disponibles.
- Colas sin mensajes inesperados.

La prueba mínima debe comprobar:

~~~text
PENDING
  → TransferRequested.v1
  → movimiento de saldos
  → TransferCompleted.v1
  → COMPLETED
~~~

También se debe probar:

~~~text
PENDING
  → TransferRequested.v1
  → error de negocio
  → TransferFailed.v1
  → FAILED
~~~

## Fase 6: validación posterior

Después del cutover se verifica:

### API Gateway

- `/auth/**` llega al monolito.
- `/accounts/**` llega a Accounts Service.
- `/transfers/**` llega a Transfers Service.

### Datos

- Los saldos finales son correctos.
- Las transferencias cambian de estado.
- No existen movimientos duplicados.
- Los Outbox llegan a `PUBLISHED`.
- Los eventos se registran una sola vez.
- No existen registros huérfanos.

### RabbitMQ

- Consumidores activos.
- Colas principales sin acumulación anormal.
- DLQ vacías después de las pruebas.
- Encabezados disponibles.
- `X-Correlation-Id` propagado.

### Observabilidad

- Health checks en `UP`.
- Métricas accesibles con autenticación.
- Logs con `correlationId`.
- Circuit Breaker en estado esperado.
- Sin secretos en logs.

## Criterios de aceptación

La migración se considera aceptada cuando:

- Las pruebas automatizadas finalizan correctamente.
- Los conteos de datos coinciden.
- Los saldos están reconciliados.
- Las rutas del Gateway funcionan.
- El flujo exitoso termina en `COMPLETED`.
- El flujo funcional fallido termina en `FAILED`.
- Un evento duplicado no produce un segundo movimiento.
- Un mensaje inválido termina en una DLQ.
- RabbitMQ puede recuperarse sin perder eventos del Outbox.
- El Circuit Breaker abre y se recupera correctamente.
- La correlación se conserva de extremo a extremo.

## Rollback

El procedimiento depende del momento en que se detecte el problema.

## Escenario 1: fallo antes del cutover

Cuando todavía no se cambió el tráfico:

1. Detener el nuevo servicio.
2. Conservar logs y evidencia.
3. Restaurar la base nueva si es necesario.
4. Corregir la causa.
5. Repetir la migración.

No se requiere modificar el Gateway porque el monolito continúa atendiendo las
solicitudes.

## Escenario 2: fallo inmediatamente después del cutover

Solo puede realizarse un rollback directo cuando no existen escrituras nuevas
exclusivas en el microservicio.

Procedimiento:

1. Detener nuevas solicitudes.
2. Confirmar que no hay mensajes pendientes.
3. Registrar el estado de bases y colas.
4. Cambiar temporalmente la ruta del Gateway.
5. Reactivar el componente heredado.
6. Ejecutar pruebas de humo.
7. Investigar el fallo fuera de producción.

## Escenario 3: rollback después de nuevas escrituras

No se debe redirigir tráfico al monolito inmediatamente porque los datos
heredados pueden estar desactualizados.

Procedimiento:

1. Activar una ventana de mantenimiento.
2. Detener nuevas escrituras.
3. Esperar o detener consumidores de forma controlada.
4. Registrar mensajes listos y no confirmados.
5. Consultar eventos `PENDING` y `FAILED`.
6. Reconciliar las bases nuevas.
7. Exportar los registros creados después del cutover.
8. Aplicar una migración inversa validada.
9. Comparar conteos, saldos y estados.
10. Restaurar la ruta heredada.
11. Ejecutar pruebas.
12. Mantener los servicios nuevos disponibles para investigación.

Un rollback tardío es una migración de datos inversa, no solamente un cambio de
ruta.

## Escenario 4: RabbitMQ indisponible

Una caída temporal de RabbitMQ no requiere rollback inmediato.

Los eventos permanecen en:

~~~text
transfers.outbox_events
accounts.outbox_events
~~~

Procedimiento:

1. Confirmar que los eventos permanecen `PENDING`.
2. Restaurar RabbitMQ.
3. Confirmar exchanges, colas y bindings.
4. Reiniciar los publicadores si es necesario.
5. Verificar publicación.
6. Confirmar procesamiento idempotente.
7. Revisar las DLQ.

## Escenario 5: Accounts Service indisponible

Transfers Service debe devolver HTTP 503 durante la validación síncrona.

El Circuit Breaker evita llamadas repetitivas.

Procedimiento:

1. Verificar health y logs de Accounts Service.
2. Verificar Accounts PostgreSQL.
3. Corregir la dependencia.
4. Esperar el estado `HALF_OPEN`.
5. Ejecutar llamadas de prueba.
6. Confirmar retorno a `CLOSED`.

No deben crearse transferencias cuando falla la validación previa.

## Escenario 6: mensaje en DLQ

Procedimiento:

1. No purgar la DLQ inmediatamente.
2. Inspeccionar payload y encabezados.
3. Revisar `x-death`.
4. Consultar `processed_events`.
5. Consultar el estado de la transferencia.
6. Corregir la causa.
7. Republicar de manera controlada.
8. Confirmar el resultado.
9. Eliminar el mensaje original después de validar.

## Criterios para ejecutar rollback

Se considera rollback cuando existe:

- Pérdida o corrupción de datos.
- Diferencia no explicada en saldos.
- Movimientos duplicados.
- Imposibilidad sostenida de procesar transferencias.
- Errores críticos de seguridad.
- Incompatibilidad de contratos.
- Acumulación no controlada de mensajes.
- Fallo de migraciones Flyway.
- Incapacidad de observar o auditar operaciones.

## Condiciones que impiden un rollback inmediato

No debe ejecutarse un cambio de ruta directo cuando:

- Existen escrituras nuevas sin copiar al esquema heredado.
- Hay eventos `PENDING`.
- Hay mensajes no confirmados.
- Existen mensajes en DLQ sin analizar.
- Las bases presentan saldos diferentes.
- No existe un backup válido.
- No se conoce la última fuente de verdad.

## Retiro de componentes heredados

El esquema y código heredados solo deben retirarse después de:

- Periodo de estabilización completado.
- Validación funcional aprobada.
- Reconciliación de datos aprobada.
- Backups verificados.
- Ausencia de rollback reciente.
- Procedimientos operativos establecidos.
- Aprobación formal del responsable técnico.

Después del retiro definitivo, el rollback requiere restaurar software y datos
desde backups.

## Evidencia requerida

Cada migración debe conservar:

- Fecha y responsable.
- Commit desplegado.
- Resultados de pruebas.
- Conteos antes y después.
- Reconciliación de saldos.
- Estado de las colas.
- Capturas o salidas de health.
- Estado del Circuit Breaker.
- Correlation ID de una prueba completa.
- Decisión final de aceptación o rollback.

## Conclusión

La migración progresiva permite extraer Accounts y Transfers sin reemplazar
todo el monolito en una sola operación.

El API Gateway controla el tráfico, las bases independientes aíslan los datos y
RabbitMQ coordina el procesamiento asíncrono.

El rollback debe ejecutarse según el estado real de los datos. Después de
aceptar nuevas escrituras, revertir requiere reconciliación y migración inversa,
no solamente redireccionar las rutas.
