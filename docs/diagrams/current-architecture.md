# Arquitectura actual de FinBank

## Descripción

FinBank funciona actualmente como un monolito modular desarrollado con
Spring Boot. Todos los módulos se ejecutan dentro del mismo proceso, utilizan
un único datasource PostgreSQL y mantienen sus tablas separadas mediante
schemas.

El módulo `transfers` actúa como orquestador y utiliza directamente las
interfaces internas de `accounts`, `notifications` y `audit`.

## Diagrama de componentes

```mermaid
flowchart LR
    Client[Cliente externo] -->|HTTP + JWT| Monolith[FinBank Modular Monolith<br/>Spring Boot :8080]

    subgraph Monolith
        Auth[auth]
        Accounts[accounts]
        Transfers[transfers]
        Notifications[notifications]
        Audit[audit]

        Transfers -->|AccountsService| Accounts
        Transfers -->|NotificationsService| Notifications
        Transfers -->|AuditService| Audit
    end

    Auth --> AuthSchema[(auth schema)]
    Accounts --> AccountsSchema[(accounts schema)]
    Transfers --> TransfersSchema[(transfers schema)]
    Notifications --> NotificationsSchema[(notifications schema)]
    Audit --> AuditSchema[(audit schema)]

    subgraph PostgreSQL[PostgreSQL modular_bank]
        AuthSchema
        AccountsSchema
        TransfersSchema
        NotificationsSchema
        AuditSchema
    end