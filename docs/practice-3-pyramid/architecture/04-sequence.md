# А4. Sequence — успешная операция, отказ и компенсация

**Чего не хватало:** ветвей declined/reversal и точной границы async.
**Что сделано:** отражён порядок вызовов из RouteService/AuthServiceImpl, включая комиссию,
обновление usage до reserve и отдельный rollback. Пунктирные ответы HTTP не означают AMQP:
асинхронные сообщения здесь помечены явно и используют стрелку `-)`.

```mermaid
sequenceDiagram
    actor Client as Терминал / мерчант
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant C as Card Management
    participant B as BIN Lookup
    participant D as PostgreSQL
    participant M as Merchant Acquirer
    participant R as RabbitMQ
    participant L as Transaction Logger
    Client->>G: HTTP POST /api/transactions
    G->>S: HTTP POST /api/internal/route
    S->>S: normalize, BIN routing, issuerId
    S->>A: HTTP POST /api/internal/authorize
    A->>C: HTTP GET /api/cards/{pan}
    C->>D: JDBC read card
    D-->>C: card
    C-->>A: card
    A->>B: HTTP GET /api/bin/{bin}
    B-->>A: issuer / empty при ошибке (только логирование)
    alt declined: карта BLOCKED
        A-->>S: HTTP 403, DECLINED/05, CARD_BLOCKED
        Note over A,D: Нет upsert usage и reserve
    else ACTIVE, срок и balance допустимы
        A->>D: JDBC upsert limit_usage в пределах лимитов
        D-->>A: 1 строка
        A->>D: JDBC выделение блока RRN при необходимости
        A->>C: HTTP POST /api/cards/{pan}/reserve
        C->>D: TX: lock card, reservation, balance, outbox
        D-->>C: commit
        C-->>A: HTTP 200
        A-->>S: HTTP 200, APPROVED/00, RRN и authCode
        Note over A,D: Локальная TX Authorization завершена отдельно от CMS
    end
    S->>M: HTTP POST /api/simulator/merchant/fee
    M-->>S: комиссия / null при ошибке
    alt convertAndSend завершился без AmqpException
        S-)R: async transaction.log (APPROVED или DECLINED)
        par Ответ клиенту
            S-->>G: HTTP 200, исходный бизнес-результат
            G-->>Client: HTTP 200, APPROVED или DECLINED
        and Независимая доставка
            R-)L: async consume transaction-log
            L->>D: JDBC store transactions
            Note over L: Затем WebSocket broadcast в Dashboard
        end
    else AmqpException и исходный результат APPROVED
        Note over S,A: Компенсация, в задании называется reversal (mti 0400)
        S->>A: HTTP POST /api/internal/rollback (rrn, pan, amount)
        A->>C: HTTP POST /api/cards/{pan}/rollback
        C->>D: TX: lock reservation, ROLLED_BACK, возврат balance, outbox
        D-->>C: commit или ошибка
        C-->>A: результат rollback
        A-->>S: APPROVED / DECLINED / нет ответа
        S-->>G: HTTP 200, DECLINED/96
        G-->>Client: HTTP 200, DECLINED/96
    else AmqpException и исходный результат DECLINED
        S-->>G: исходный DECLINED, rollback не вызывается
        G-->>Client: HTTP 200, DECLINED
    end
```

## Что не следует выводить из схемы

- Happy path предполагает допустимый BIN, ACTIVE-карту, достаточные баланс/лимиты и успешные зависимости.
- Сохранение Logger может завершиться до ответа клиенту или после: порядок между ними не гарантирован.
- Настройка correlated confirm не равна ожиданию ACK. Требование «нет confirm за 2s → rollback»
  в этой версии отсутствует: LoggerClient проверяет только исключение отправки.
- `AuthorizationRequest.forReversal(rrn)` создаёт MTI 0400, но не вызывается рабочим путём.
  Реальный rollback DTO содержит только rrn/pan/amount. Нельзя рисовать HTTP authorize(0400)
  как реализованный откат; проверка этого требования включена в новые кейсы.
- Ошибка rollback не меняет итоговый DECLINED/96, но деньги при ней могут остаться зарезервированы.
- `limit_usage` не компенсируется методом rollback. При ошибке reserve тоже нет явной компенсации.
- Запись APPROVED могла уйти в очередь даже при неопределённом исходе отправки;
  отдельное исправляющее сообщение ROLLED_BACK код не публикует.

## Источники

[application.yml](../../../services/gateway/src/main/resources/application.yml), [RouteController.java](../../../services/switch/src/main/java/com/processing/controller/RouteController.java), [RouteService.java](../../../services/switch/src/main/java/com/processing/service/RouteService.java),
[AuthorizationClient.java](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java), [AuthControllerImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java), [AuthServiceImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [DeclineOutcome.java](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java),
[CardManagementClientImpl.java](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java), [CardServiceImpl.java](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [LimitUsageRepository.java](../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java),
[MerchantAcquirerClient.java](../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java), [LoggerClient.java](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [TransactionLogListener.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [TransactionService.java](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java),
[AuthorizationRequest.java](../../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java), [RollbackRequest.java](../../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java), [application.yml](../../../services/switch/src/main/resources/application.yml).
