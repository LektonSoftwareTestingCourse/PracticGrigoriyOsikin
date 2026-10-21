# Практика 3 — пирамида тестирования

Осикин Григорий. Сверка выполнена по исходному коду коммита `b985005` (13.10.2026).
Это проект проверок и архитектурный анализ; тест-кейсы не запускались, результаты PASS/FAIL не присваивались.

## Состав

| Часть | Артефакт |
|---|---|
| А1 | [Контекст](architecture/01-context.md) |
| А2 | [Контейнеры](architecture/02-container.md) |
| А3 | [Компоненты двух сервисов](architecture/03-component.md) |
| А4 | [Три сценария транзакции](architecture/04-sequence.md) |
| А5 | [RabbitMQ](architecture/05-rabbitmq.md) |
| А6 | [Состояния](architecture/06-state.md) |
| Б–Г | [Новые кейсы, классификация 47 старых и пропорции](test-cases.md) |

Схемы записаны на Mermaid. В каждом из шести файлов есть пояснение изменений и ссылки на реализацию.
Путь сдачи взят из задания `Lectures/materials/module-03-pyramid/practice/practice-03.md`:
таблица артефактов в [submission-guide.md](../submission-guide.md) ещё называет практику 3 unit-тестами,
хотя раздел контрольных точек того же документа уже указывает пирамиду тестирования.

## Расхождения задания и реализации

| ID | Утверждение из материалов | Что показывает код и как это учтено |
|---|---|---|
| D1 | 11 сервисов | В [docker-compose.yaml](../../docker-compose.yaml) 10 прикладных контейнеров, включая два симулятора, BIN и Dashboard; отдельно 2 инфраструктурных и 5 в профиле observability. Несуществующий 11-й сервис не добавлен. |
| D2 | confirm за 2 секунды | `publisher-confirm-type: correlated` включён, но [LoggerClient.java](../../services/switch/src/main/java/com/processing/service/LoggerClient.java) вызывает `convertAndSend` без ожидания confirm. [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java) запускает откат при `false` от LoggerClient, то есть пойманном AmqpException. Сценарий потери confirm выделен как проверка требования. |
| D3 | reversal через mti 0400 | [AuthorizationRequest.java](../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java) содержит `forReversal(rrn)`, но рабочий путь [AuthorizationClient.java](../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) создаёт [RollbackRequest.java](../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java) и вызывает `/api/internal/rollback`; DTO не содержит MTI. На Sequence показан реальный путь и отдельно требуемая семантика 0400. |
| D4 | Transaction.ROLLED_BACK | [TransactionStatus.java](../../services/common/src/main/java/com/processing/common/dto/transactionlogger/TransactionStatus.java) содержит только APPROVED/DECLINED. ROLLED_BACK хранится в [ReservationStatus.java](../../services/card-management/src/main/java/com/processing/cardmanagement/models/ReservationStatus.java). Откат резерва не переписывает опубликованную транзакцию. |
| D5 | 3 доставки, затем DLQ | В RabbitMQConfig задан `x-max-delivery=3`; явной настройки listener retry/recoverer в исходниках и application-конфигах не найдено. По одному аргументу очереди нельзя считать гарантию выполненной: нужен отдельный эксперимент с ошибкой consumer. TTL 60000 задан именно DLQ, обратной привязки в основную очередь нет. |
| D6 | Проверка daily раньше balance | [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java) проверяет status → expiry → balance → SQL лимитов → reserve. AUTH-M01 из практики 2 ожидает 61, но код возвращает 51 при одновременно недостаточном balance. |
| D7 | CMS timeout: 05 / ISSUER_TIMEOUT | [DeclineOutcome.java](../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java) и [AuthorizationResponse.java](../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationResponse.java) дают 96 / SERVICE_UNAVAILABLE; прямой HTTP Authorization — 503. Старый oracle сохранён как расхождение, а не объявлен пройденным. |
| D8 | Неизменный usage при ошибке reserve | [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java) обновляет usage до HTTP reserve, перехватывает ошибку и возвращает обычный результат. Явной компенсации usage нет; rollback также вызывает только CMS. Риск фиксируется отдельной интеграционной проверкой. |
| D9 | BIN lookup retry и обогащение | [BinLookupClient.java](../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java) делает один HTTP-вызов, при ошибке возвращает empty. AuthServiceImpl только логирует найденный issuerId, не заменяя issuerId из Switch. |
| D10 | list по умолчанию 50 | [CardController.java](../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java) задаёт HTTP default limit=10. Ожидание 50 в CMS-P12/P26 требует уточнения; проверка «не более 50» сама по себе дефект не обнаружит. |
| D11 | Outbox с exponential backoff и общей транзакцией всегда | [OutboxProcessor.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java) использует fixedDelay 1000 мс по умолчанию, [OutboxEventProcessorImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java) — максимум 3 ошибки; ожидания confirm нет. PATCH/DELETE/reserve/rollback обёрнуты TransactionRunner, но createCard/createCards в [CardServiceImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java) не имеют такой общей обёртки вокруг сохранения карт и outbox. |

Это статические наблюдения, а не протокол воспроизведения ошибок на стенде.
Исходные артефакты практики 2 и код СМП не изменялись.
