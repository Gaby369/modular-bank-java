# ADR-001: Elección del primer módulo a extraer

## Estado

Aprobado

## Contexto

FinBank funciona actualmente como un monolito modular compuesto por los
módulos auth, accounts, transfers, notifications y audit.

El reto requiere iniciar una migración progresiva hacia microservicios
aplicando el patrón Strangler Fig. El primer módulo extraído debe funcionar
de manera autónoma, conservar su contrato HTTP y contar con una base de
datos exclusiva.

En el sistema actual, el módulo accounts administra las cuentas bancarias,
los saldos, los débitos y los créditos. El módulo transfers depende de la
interfaz AccountsService para realizar las operaciones sobre las cuentas.

## Opciones evaluadas

### Opción 1: Notifications

Ventajas:

- Bajo riesgo de negocio.
- Puede adaptarse fácilmente a comunicación asíncrona.
- No modifica directamente los saldos.

Desventajas:

- No permite demostrar suficientemente los requisitos de consistencia
  bancaria.
- Tiene menor impacto arquitectónico para los objetivos del reto.

### Opción 2: Audit

Ventajas:

- Tiene una responsabilidad claramente definida.
- Puede evolucionar como consumidor de eventos.

Desventajas:

- Es transversal a todos los módulos.
- Una migración incorrecta puede afectar la trazabilidad regulatoria.

### Opción 3: Accounts

Ventajas:

- Posee una frontera de dominio claramente definida.
- Ya expone la interfaz interna AccountsService.
- Permite demostrar base de datos por servicio.
- Permite abordar consistencia, concurrencia y migración de datos.
- El módulo transfers puede adaptarse posteriormente para consumirlo
  remotamente.

Desventajas:

- Tiene un riesgo de migración mayor.
- Administra datos financieros críticos.
- Requiere una estrategia rigurosa de migración y rollback.

## Decisión

Se extraerá primero el módulo accounts.

El nuevo Accounts Service será responsable de administrar cuentas, saldos,
débitos y créditos. Tendrá una base de datos PostgreSQL exclusiva y expondrá
el mismo contrato HTTP que ofrece actualmente el monolito.

El API Gateway dirigirá las rutas /accounts/** hacia el nuevo microservicio,
mientras las demás solicitudes continuarán hacia el monolito remanente.

## Consecuencias positivas

- Se obtiene autonomía sobre el dominio de cuentas.
- Se establece una base de datos exclusiva por servicio.
- Se mantiene una frontera clara entre cuentas y transferencias.
- Se prepara el sistema para extraer transfers como segundo microservicio.
- Se cubren los principales requisitos académicos del reto.

## Consecuencias negativas

- Aumenta la complejidad operativa.
- Se introduce comunicación remota entre transfers y accounts.
- Se deben manejar fallos de red y timeouts.
- La migración de saldos requiere validaciones de integridad.
- Se necesitarán mecanismos de rollback y observabilidad.

## Reversibilidad

La decisión tiene una reversibilidad media. Mientras el esquema original
permanezca disponible y el Gateway pueda devolver el tráfico al monolito,
será posible realizar rollback.

Después de retirar definitivamente el esquema accounts del monolito, revertir
la decisión será más costoso.