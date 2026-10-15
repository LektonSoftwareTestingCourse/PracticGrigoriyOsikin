# Тест-кейсы и анализ пирамиды

## Метод и границы

Всего 47 исходных кейсов практики 2 и 20 новых, то есть 67. Каждая строка с ID считается
одним кейсом, даже если содержит несколько граничных значений. Таблицы классов/границ и
строки PICT повторно не считаются: их проекции уже имеют ID в разделе 4
[практики 2](../practice-2/test-design.md).

Это спецификация будущих проверок, не отчёт о выполнении. Ожидания описывают либо
реализованный контракт, либо явно обозначенное требование/инвариант, которое код может нарушать.
Известные расхождения D1–D11 перечислены в [README](README.md).

- `unit`: вызов Java-метода, без Spring/HTTP/БД/брокера; внешние зависимости mock/stub.
- `api`: реальный HTTP binding, validation и mapping одного сервиса или HTTP-клиент со stub-сервером; внешние зависимости изолированы. Проверка DB-эффекта в этот уровень не входит.
- `integration`: настоящая БД, брокер, транзакция или несколько выбранных сервисов. Даже если вход HTTP, проверка persistence/очереди относится сюда.
- `e2e`: полный бизнес-путь от внешнего API через реальные сервисы и инфраструктуру. Браузер для backend E2E не обязателен.

Все суммы в копейках. Базовая карта — PAN `4000003458730237`, ACTIVE, expiry 2027-12,
RUB (643), dailyLimit=10000, monthlyLimit=30000, balance=20000, usage=0, если строка
не переопределяет fixture. Для независимых прогонов карта/usage восстанавливаются.
Запросы содержат все обязательные валидные поля [AuthorizationRequest.java](../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java);
STAN/UUID/RRN уникальны в пределах соответствующего сценария. BIN зарегистрирован.
Для SQL-проверок используется PostgreSQL со штатными миграциями, а не H2.

В async-кейсах timeout 10s и интервал polling 100ms — параметры тестового стенда, не SLA продукта.
Корреляция выполняется по id/RRN/STAN/PAN, а не общим количествам чужих записей.
Журнал опрашивается через `GET /api/transactions/search?pan=<PAN>&limit=500`;
нужные RRN/STAN ищутся в полученных строках (при необходимости с пагинацией),
поскольку отдельных query-фильтров rrn/stan нет.
При истечении timeout сохраняются очереди, payload и логи для диагностики.
TTL-тест использует отдельное окно 65s. Разрушительные fixtures и fault injection — только в изолированной тестовой среде.

## Б. Новые тест-кейсы

P3-Rxx — локальные идентификаторы проверяемых требований этой практики;
точная формулировка указана в колонке требования, первоисточник — в последней колонке.
Для N14/N16 нормативный источник — задание модуля 3; ссылки на код показывают место проверки.
N17 проверяет инвариант сохранности лимита из AUTH-M02 практики 2.

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N01 | P3-R01: BIN routing | unit | позитивный / негативный | RoutingService с таблицей 400000 → ISS001 | Вызвать getIssuerIdByPan для 4000003458730237, 9999990000000000, null и 12345 | ISS001 только для известного BIN; иначе UnknownBinException | Класс эквивалентности; [RoutingService.java](../../services/switch/src/main/java/com/processing/service/RoutingService.java) |
| N02 | P3-R02: отказ до побочных эффектов | unit | негативный | AuthServiceImpl; CMS stub отдаёт BLOCKED; BIN stub empty | Вызвать authorize валидного запроса; проверить вызовы mock | DECLINED/05 CARD_BLOCKED; upsertLimitUsage и reserve не вызваны | Класс состояния; [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [DeclineOutcome.java](../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java) |
| N03 | P3-R03: срок по UTC-дате запроса | unit | граничный | ACTIVE; expiry 2026-10; balance/limits достаточны; mocks успешны | Изолированно authorize с transmissionDateTime 2026-10-31T23:59:59Z и 2026-11-01T00:00:00Z | Первый APPROVED, второй DECLINED/54; у второго нет reserve | Граничное значение; [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java) |
| N04 | P3-R04: ошибка публикации вызывает компенсацию | unit | негативный | RouteService; authorize mock APPROVED с RRN; LoggerClient mock false; rollback mock APPROVED | Вызвать route и проверить аргументы rollback | Один rollback с исходными PAN/amount и полученным RRN; итог DECLINED/96 | Сценарий; [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java) |
| N05 | P3-R04: declined не компенсируется | unit | негативный | RouteService; authorize mock DECLINED CARD_BLOCKED; LoggerClient mock false | Вызвать route; проверить rollback | Возвращён исходный DECLINED/05; rollback не вызван | Класс эквивалентности; [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java) |
| N06 | P3-R05: предел ошибок outbox | unit | негативный / граничный | PENDING событие retryCount=0; maxRetryCount=3; RabbitTemplate бросает AmqpException; repository mock возвращает сохранённое | Трижды processSingleEvent, каждый раз передав сохранённое предыдущее состояние | После ошибок 1/2 статус PENDING, после 3 — FAILED; retryCount=3 и lastError сохранён | Граничное значение; [OutboxEventProcessorImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [CardOutboxEventData.java](../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardOutboxEventData.java) |
| N07 | P3-R06: контракт HTTP decline | api | негативный | Реальный AuthControllerImpl; AuthService stub отдаёт CARD_BLOCKED/05 | POST /api/internal/authorize с валидным DTO | HTTP 403 и тело DECLINED, responseCode=05, declineReason=CARD_BLOCKED | Контракт; [AuthControllerImpl.java](../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java) |
| N08 | P3-R07: Gateway validation | api | негативный | Gateway; Switch HTTP stub со счётчиком; остальные поля валидны | POST /api/transactions с pan=4000ABC | HTTP 400; Switch не получил запрос | Класс эквивалентности / контракт; [TransactionRequestValidator.java](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java), [TransactionValidationFilter.java](../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionValidationFilter.java) |
| N09 | P3-R08: BIN graceful degradation | api | негативный | Реальный BinLookupClient направлен на HTTP stub | Ответить HTTP 500 на GET /api/bin/400000; вызвать getIssuerId валидного PAN | Optional.empty; ровно один HTTP-запрос, исключение наружу не выходит | Контракт клиента; [BinLookupClient.java](../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java) |
| N10 | P3-R09: SQL дневного лимита | integration | граничный | PostgreSQL; usage daily=2000 monthly=5000; limits=10000/30000 | На независимых fixtures выполнить upsertLimitUsage с 7999, 8000, 8001 | Затронуто 1/1/0 строк; daily становится 9999/10000/2000, monthly 12999/13000/5000 | Граничное значение / состояние БД; [LimitUsageRepository.java](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java) |
| N11 | P3-R10: атомарность PATCH и outbox | integration | негативный | CMS + PostgreSQL; ACTIVE-карта; scheduler отключён; outbox save контролируемо бросает исключение | PATCH status=BLOCKED; дождаться завершения запроса; читать БД в новой транзакции | PATCH неуспешен; карта остаётся ACTIVE; outbox событие не сохранено — обе записи атомарны | Состояние БД; [CardServiceImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [CardEventNotifierImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java), [TransactionRunnerImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/TransactionRunnerImpl.java) |
| N12 | P3-R11: доставка карточного события | integration | позитивный / async | CMS worker + RabbitMQ + Notification + PostgreSQL; пустая очередь; scheduler управляем; создана карта с уникальным PAN | PATCH status=BLOCKED; запустить worker; polling outbox и notification по PAN в payload | В пределах 10s outbox PROCESSED и notification с routingKey=card.CardServicePatchEvent, eventType=CardServicePatchEvent и нужным PAN | Сценарий / состояние БД; [OutboxEventProcessorImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [CardEventListener.java](../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) |
| N13 | P3-R12: доставка и повтор сообщения Logger | integration | позитивный / async | Реальные RabbitMQ, Logger, PostgreSQL; объявлена полная transaction топология; новый UUID | Опубликовать валидный TransactionRequest, дождаться строки; повторить идентичное сообщение с тем же id | В пределах 10s строка доступна и совпадает с сообщением; после повторной обработки всё ещё одна строка; очередь обработана | Контракт / состояние БД; [TransactionLogListener.java](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java), [TransactionService.java](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java) |
| N14 | P3-R13: consumer retry 3 и DLQ по заданию | integration | негативный / async | Изолированный RabbitMQ и Notification; полная топология; счётчик входов listener; DLQ очищена только в тестовом vhost | Опубликовать некорректный JSON; считать попытки; polling DLQ до 10s, снять сообщение до TTL | По требованию: 3 попытки всего, затем одно сообщение card-notifications-dlq, основная очередь освобождена. Иное число или requeue без конца — несоответствие D5 | Контракт задания; [RabbitMQConfig.java](../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java), [CardEventListener.java](../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) |
| N15 | P3-R14: TTL диагностического сообщения | integration | граничный / async | RabbitMQ, обе DLQ объявлены; TTL 60000; consumers DLQ отсутствуют; уникальные marker id | Поместить по одному сообщению через соответствующий DLX; убедиться до 5s в наличии, не извлекая; после 65s проверить через basic.get | До истечения TTL сообщения доступны в DLQ; после срока basic.get пуст; в основных очередях marker не появляется | Граничное значение / контракт; [RabbitMQConfig.java](../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java), [RabbitMQConfig.java](../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java) |
| N16 | P3-R15: confirm 2s и reversal по заданию | integration | негативный / async | Реальный Switch LoggerClient и RabbitMQ; AMQP proxy удерживает только publisher ACK/NACK, публикация доставлена; Authorization и fee — HTTP stubs; authorize APPROVED | Отправить route; удерживать confirm более 2s, но не разрывать socket; записать время и HTTP-запрос компенсации | По заданию: после 2s ожидания (допуск измерения 500ms) отказ 96 и одна компенсация с семантикой 0400. Отдельно проверить MTI; фактический /rollback не содержит его. Код предсказывает отсутствие ожидания/отката — D2/D3 | Контракт задания / сценарий; [LoggerClient.java](../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [AuthorizationClient.java](../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) |
| N17 | P3-R16: ошибка reserve не расходует лимит | integration | негативный | Authorization + PostgreSQL; CMS HTTP stub GET=валидная ACTIVE карта, reserve=503 без побочного эффекта; BIN stub; usage=2000/5000 | POST authorize amount=100; дождаться ответа; SELECT usage из новой транзакции | По денежному инварианту: DECLINED/96 SERVICE_UNAVAILABLE, usage остаётся 2000/5000. Статический анализ предсказывает 2100/5100: кандидат на дефект D8 | Сценарий / состояние БД; [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [LimitUsageRepository.java](../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java) |
| N18 | P3-R17: откат денег и повтор rollback | integration | позитивный / негативный | CMS + PostgreSQL; ACTIVE, balance=20000; новый RRN; amount=100 | reserve; rollback с тем же PAN/RRN/amount; повторить rollback; проверить БД | После reserve balance=19900, после rollback=20000 и reservation ROLLED_BACK; повтор отклонён, balance=20000, второй возврат не создан | Состояние БД; [CardServiceImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [Reservation.java](../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java), [CardController.java](../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java) |
| N19 | P3-R18: успешная транзакция и eventual consistency | e2e | позитивный | Полный контур Gateway/Switch/Auth/CMS/BIN/Merchant/RabbitMQ/Logger/PostgreSQL; ACTIVE-карта, balance=20000, usage=0, limits достаточны; новый STAN | POST /api/transactions amount=100; по полученному RRN polling журнала; прочитать карту и usage | HTTP 200 APPROVED/00; balance=19900, usage=100; в пределах 10s одна APPROVED запись журнала с теми же RRN/amount/PAN; немедленное наличие записи не требуется | Сквозной сценарий; [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java), [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [TransactionService.java](../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java) |
| N20 | P3-R19: блокировка карты видна процессингу | e2e | негативный | Полный контур как N19; новая ACTIVE-карта; записаны исходные balance/usage | PATCH карты в BLOCKED через Gateway; POST транзакции; polling Logger по уникальному STAN | PATCH успешен; транзакция HTTP 200 DECLINED/05 CARD_BLOCKED; balance/usage прежние; в пределах 10s запись DECLINED в журнале | Состояние / сквозной сценарий; [CardServiceImpl.java](../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java), [AuthServiceImpl.java](../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java), [RouteService.java](../../services/switch/src/main/java/com/processing/service/RouteService.java) |

### Почему выбран этот уровень

N01–N06 проверяют ветвления, аргументы и счётчики без инфраструктуры.
N07–N09 проверяют HTTP-преобразование и поведение клиента, поэтому одного unit недостаточно.
N10–N18 требуют SQL, транзакций, RabbitMQ или transport fault injection; mock этой границы
убрал бы сам проверяемый риск. N19/N20 оставлены сквозными: результат связывает изменение
карты, процессинг и последующее появление журнала. Проверка N16 отдельно различает
таймаут confirm, сетевое исключение отправки и формат reversal; N04 не заменяет её.
N13 проверяет идемпотентность по одному message id, а не повтор исходной покупки с новым id.

### Покрытие карты системы

| Область / связь | Новые кейсы |
|---|---|
| А1/А2: внешний вход и Gateway | N08, N19, N20 |
| А2/А3: BIN, HTTP-клиенты, ветвления Authorization | N01, N02, N03, N07, N09 |
| А3: SQL лимитов, CMS, транзакции | N10, N11, N17, N18 |
| А4: отказ и компенсация | N04, N05, N16, N18, N20 |
| А5: обе очереди, повтор, DLQ/TTL, outbox | N06, N12, N13, N14, N15, N16 |
| А6: BLOCKED, EXPIRED, RESERVED → ROLLED_BACK | N02, N03, N18, N20 |

## В. Распределение существующих кейсов

Уровень выбирается для полного содержания строки, а не по названию операции.
Для API явно задана изоляция: фраза «изменений нет» означает отсутствие вызовов изменяющих
зависимостей; если нужно доказать состояние настоящей БД, используется integration.
Шаги HTTP старых кейсов сохраняются, поэтому они не объявлены unit только из-за наличия
внутри простой логики. Выделенные новые unit проверяют дополнительные узкие ветви.
Дубликаты сценариев PICT с разными ID сохранены для полной прослеживаемости.

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| AUTH-P01 | Успешная авторизация | Резерв, расход лимитов и получение RRN | integration | Нужны реальная БД Authorization и CMS; один mock reserve не подтверждает изменение денег |
| AUTH-P02 | CMS недоступен | `DECLINED/05`, `ISSUER_TIMEOUT`, изменений нет | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P03 | Карта отсутствует и BIN недоступен | `DECLINED/14`, fallback не приводит к reserve | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P04 | CMS недоступен, второй набор | `DECLINED/05`, изменений нет | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P05 | CMS и BIN недоступны | Контролируемый `DECLINED/05`, без изменения данных | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P06 | Статус EXPIRED | `DECLINED/54`, лимиты/balance не меняются | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P07 | Статус INACTIVE | `DECLINED`, причина `CARD_INACTIVE`, reserve не вызван | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P08 | BLOCKED и BIN недоступен | `DECLINED`, причина `CARD_BLOCKED`, reserve не вызван | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P09 | Неизвестная карта | `DECLINED/14`, значения лимитов не влияют | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P10 | CMS недоступен, третий набор | `DECLINED/05`, изменений нет | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| AUTH-P11 | Статус BLOCKED | `DECLINED`, `CARD_BLOCKED`, поздние условия не вычисляются | api | Реальные HTTP controller/service; CMS/BIN и repository заменены, запрет изменений проверяется отсутствием вызовов записи. SQL этим тестом не проверяется |
| CMS-P01 | Создание ACTIVE-карты | 201, ACTIVE, валидные PAN/Luhn и expiry | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P02 | Чтение INACTIVE | 200, возвращена нужная карта | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P03 | Отсутствующий PAN | 404 | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P04 | Невалидная пагинация EXPIRED | 400, выборка не выполняется | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P05 | PATCH отсутствующей карты с -1 | 400 валидации, ничего не создано/изменено | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P06 | PATCH невалидного PAN | 400, изменений нет | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P07 | DELETE отсутствующей карты | 404 | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P08 | DELETE невалидного PAN | 400 | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P09 | Нулевая сумма reserve | 400, balance не меняется | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P10 | Отрицательный monthlyLimit | 400, карта не создана | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P11 | Reserve BLOCKED при нехватке денег | Отказ; balance не меняется | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P12 | Фильтр ACTIVE/BIN и default | 200, только совпавшие карты, не более 50 | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P13 | Пагинация и total | 200, корректные `cards` и `total` | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P14 | Reserve отсутствующего PAN | 404, баланс других карт не меняется | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P15 | GET невалидного PAN | 400 | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P16 | PATCH BLOCKED и нулевые лимиты | 200; изменены только переданные поля | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P17 | Отрицательный dailyLimit | 400, карта не создана | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P18 | Невалидная пагинация BLOCKED | 400 | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P19 | Soft delete ACTIVE | 204; карта получает внутренний DELETED | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P20 | Чтение EXPIRED | 200 с status EXPIRED | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P21 | Reserve невалидного PAN | 400, изменений нет | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P22 | Создание с положительным balance | 201, поля сохранены, PAN проходит Luhn | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P23 | PATCH INACTIVE и balance | 200; непереданные поля прежние | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P24 | Soft delete EXPIRED | 204; последующий GET даёт 404 | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| CMS-P25 | Чтение ACTIVE | 200, данные совпадают с fixture | api | Проверка HTTP DTO/валидации/маппинга результата; CardService — stub, для отказа валидации проверяется отсутствие вызова сервиса |
| CMS-P26 | Фильтр INACTIVE без pagination | 200, до 50 INACTIVE, корректный total | integration | Реальные CMS и PostgreSQL: проверяются выборка, сохранённое состояние или отсутствие изменения balance; stub persistence недостаточен |
| AUTH-B01 | Граница дневного лимита | Условие SQL upsert на 7999/8000/8001 | integration | Решение о лимите находится в native SQL, mock не проверяет границу |
| AUTH-B02 | Граница месячного лимита | SQL-накопление monthly_amount | integration | Нужна БД; увеличить balance fixture минимум до 25001, иначе сработает проверка баланса |
| AUTH-B03 | Граница баланса | HTTP authorize при 19999/20000/20001 | api | Сохраняем HTTP-шаг практики 2; реальные controller/service, CMS и repository заменены; лимиты заведомо достаточны |
| AUTH-M01 | Конкурирующие причины отказа | Какой decline возвращает HTTP при двух нарушениях | api | Контракт HTTP проверяется с изолированными зависимостями; старое ожидание 61 расходится с кодом 51 (D6) |
| AUTH-M02 | Ошибка reserve после проверок | Сохранность usage после reserve=503 | integration | Нужен настоящий commit Authorization и чтение SQL из другой транзакции; см. D8/N17 |
| AUTH-M03 | Граница expiry | HTTP-результат для трёх месяцев expiry | api | Сохраняется HTTP-шаг; transmissionDateTime фиксируется вместе с fixture, зависимости stub |
| CMS-B01 | Границы reserve | HTTP-валидация и фактическое уменьшение balance | integration | Кейс включает изменения денег в БД, поэтому не сводится целиком к API slice |
| CMS-M01 | Повтор одного RRN | Уникальность резерва и единственное списание | integration | Нужны ограничения и транзакции PostgreSQL; повтор может вернуть 409, повторного списания быть не должно |
| CMS-M02 | Soft delete во всём контуре | Удаление, GET/list/reserve и дальнейшая авторизация | e2e | Исходный кейс пересекает CMS и Authorization; проверяет внешнюю наблюдаемость удаления |
| CMS-M03 | Граница генератора count | 0 отвергается, 1/2 реально сохраняют точное число карт | integration | Проверяются генератор и persistence с БД, а не только статус ответа |

### Уточнения старых ожиданий перед реализацией

Исходный файл не переписывается. Распределение не означает подтверждения его oracle:

- AUTH-P02/P04/P05/P10: код возвращает SERVICE_UNAVAILABLE/96, а не ISSUER_TIMEOUT/05 (D7).
- AUTH-P03/P05: при ошибке GET карты выполнение заканчивается до BIN lookup. Stub BIN в этих строках может вообще не вызываться; они не доказывают fallback.
- AUTH-B02: baseline balance=20000 маскирует monthly-границу 25000; для этой проверки нужно balance ≥ 25001 и достаточный dailyLimit.
- AUTH-M01: фактический приоритет balance даёт 51, старое ожидание 61 требует согласования с требованием (D6).
- AUTH-M02: неизменность usage требует реального commit и может не выполняться (N17).
- AUTH-M03: сравнение срока основано на transmissionDateTime UTC, поэтому фиксации только системных часов недостаточно.
- CMS-P12/P26: default HTTP limit=10 (D10); fixture с 60 совпадающими картами различит 10 и 50.
- CMS-M01: идемпотентность денежного эффекта не означает два HTTP 200 — повтор RRN отклоняется.
- CMS-P05/P06/P09/P10/P17: ранняя валидация может завершить кейс до проверки PAN/status/BIN; покрытие PICT-пар не гарантирует выполнение всех ветвей.

## Г. Пропорции

| Уровень | Старые | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 0 | 6 | 6 | 8.96% |
| api / integration | 46 | 12 | 58 | 86.57% |
| e2e | 1 | 2 | 3 | 4.48% |
| **Итого** | **47** | **20** | **67** | **100%** |

Для аудита среднего слоя: `api` — 29 старых + 3 новых = 32, `integration` — 17 старых + 9 новых = 26. Доля = количество / 67 × 100%; округление до двух знаков; сумма округлённых долей равна 100.01%, точных — 100%.

### Объяснение распределения

Преобладание среднего слоя связано с исходным набором: практика 2 описана как HTTP-проверки
Authorization/CMS, включая сохранение карт, фильтры, резервы и SQL-лимиты. Перенос этих
кейсов в unit без изменения шагов и oracle исказил бы содержание. Новые проверки также
закрывают риски очередей, outbox, компенсации и commit — их нельзя доказать mock-объектами.
Поэтому пропорции сознательно не подгоняются под 70/20/10.

Перекоса в E2E нет: сквозными остаются только удаление карты, успешная покупка с журналом
и блокировка с последующим отказом. Остальные сбои воспроизводятся на более узком уровне.
Дефицита контрактных проверок по количеству нет, но есть **дефицит unit-проверок**:
6 из 67 — небольшой фундамент, поэтому набор ближе к «ромбу», чем к классической пирамиде.
Это ограничение текущего дизайна, а не признак автоматически хорошего покрытия.

В следующем модуле полезно разложить повторяющиеся AUTH-P отказы на unit логики и небольшой
параметризованный API-набор; отдельно расширить unit для нормализации, доменных денежных
правил и генераторов. Здесь исходные 47 ID не удалены и не пересчитаны задним числом.
Количество не измеряет качество: PICT-строки часто отсекаются ранней валидацией, а большая
доля API не заменяет проверки транзакций и потери сообщений. Времена выполнения и
стабильность этой пирамиды ещё не измерены, поскольку это этап тест-дизайна.
