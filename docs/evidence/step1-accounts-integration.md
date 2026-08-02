# Evidencia de integración: Accounts Service

## Objetivo

Comprobar la extracción del módulo Accounts desde el monolito hacia un microservicio independiente mediante el patrón Strangler Fig.

## Componentes

| Componente | Puerto | Responsabilidad |
|---|---:|---|
| API Gateway | 8080 | Punto de entrada y enrutamiento |
| Accounts Service | 8081 | Gestión de cuentas y saldos |
| Monolito modular | 8082 | Autenticación y transferencias |
| PostgreSQL del monolito | 5432 | Datos heredados |
| PostgreSQL de Accounts Service | 5433 | Base independiente de cuentas |

## Enrutamiento

- `/accounts/**` se dirige desde el Gateway hacia Accounts Service.
- `/auth/**` se dirige desde el Gateway hacia el monolito.
- `/transfers/**` se dirige hacia el monolito.
- El monolito utiliza `RemoteAccountsService` para comunicarse con Accounts Service.

## Seguridad

- La API pública de Accounts Service utiliza JWT.
- La API interna utiliza el encabezado `X-Internal-Api-Key`.
- Una solicitud interna sin API Key devuelve HTTP 401.
- Una solicitud interna con API Key válida devuelve HTTP 200.

## Prueba realizada

1. Se obtuvo un JWT mediante `/auth/login` a través del Gateway.
2. Se crearon dos cuentas mediante `POST /accounts`.
3. Se asignó un saldo inicial de 1000 a la cuenta origen.
4. Se realizó una transferencia de 250 mediante `POST /transfers`.
5. Se consultaron los saldos finales.

## Resultado

| Cuenta | Saldo inicial | Saldo final |
|---|---:|---:|
| Origen | 1000.0000 | 750.0000 |
| Destino | 0.0000 | 250.0000 |

## Flujo comprobado

Cliente → API Gateway → Monolito → RemoteAccountsService → Accounts Service → accounts_db

## Conclusión

La extracción del dominio Accounts funciona correctamente. El microservicio posee su propia base de datos y el monolito se comunica con él mediante HTTP, sin acceder directamente a sus tablas.