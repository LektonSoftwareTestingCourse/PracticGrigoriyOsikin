# Чек-листы

## 1. Smoke test

Перед началом: развернуть текущий commit командой `docker compose up -d`, дождаться завершения health-check и использовать только синтетические данные.

-  PostgreSQL и RabbitMQ имеют состояние `healthy`.
-  Все 11 сервисов запущены; нет циклических перезапусков контейнеров.
-  Gateway `GET http://localhost:8080/health` возвращает 200.
-  Card Management `GET http://localhost:8081/health` возвращает 200 и корректное число карт.
-  Switch `GET http://localhost:8082/health` возвращает 200.
-  Authorization `GET http://localhost:8083/health` возвращает 200 и сообщает доступность CMS.
-  Terminal Simulator, Merchant Acquirer и Transaction Logger отвечают на health-check.
-  Bin Lookup и Notification Service отвечают на actuator health-check.
-  Dashboard открывается на `http://localhost:3000` без критической ошибки загрузки.
-  RabbitMQ Management UI доступен; обязательные exchange и queue созданы.
-  `POST /api/cards/generate` создаёт минимум одну карту с валидным PAN.
-  `GET /api/cards` возвращает созданную карту.
-  Одна валидная транзакция проходит через Gateway и получает структурированный ответ.
-  После smoke в логах нет необработанных исключений и постоянных retry.

## 2. Критический путь

### Подготовка карты

-  Зарегистрирован тестовый BIN и создана ACTIVE-карта с будущим expiry.
-  PAN состоит из 16 цифр и проходит алгоритм Луна.
-  dailyLimit, monthlyLimit и availableBalance заведомо больше тестовой суммы.
-  Исходные balance и usage зафиксированы до операции.

### Успешная покупка

-  Запрос `mti=0100` содержит валидные обязательные поля и уникальный STAN.
-  Gateway принимает запрос и Switch маршрутизирует его по BIN.
-  Authorization получает карту и проходит status, expiry, daily, monthly и balance.
-  Ответ имеет `APPROVED`, `responseCode=00`, 12-значный RRN и шестисимвольный authCode.
-  availableBalance уменьшен ровно на amount один раз.
-  daily/monthly usage увеличен ровно на amount один раз.
-  Транзакция появляется в Logger в пределах согласованного deadline.
-  В RabbitMQ не остаётся неожиданного сообщения в DLQ.
-  Dashboard/API отображает результат без расхождения с Logger.

### Критические отказы

-  Неизвестный PAN даёт `DECLINED/14` и не меняет деньги/usage.
-  INACTIVE, BLOCKED и EXPIRED не допускаются к reserve.
-  Превышение dailyLimit и monthlyLimit даёт decline с кодом `61`.
-  Сумма выше availableBalance даёт decline с кодом `51`.
-  Недоступность CMS приводит к контролируемому decline без изменения balance/usage.
-  Недоступность Bin Lookup включает fallback и не обрушает авторизацию.
-  Повтор reserve с тем же RRN не уменьшает баланс второй раз.
-  После soft delete карта не выдаётся GET/list и не участвует в reserve/authorization.
-  Ошибка подтверждения публикации APPROVED-транзакции инициирует reversal; баланс восстанавливается один раз.

### Завершение

-  Фактические результаты и версия commit сохранены.
-  Для каждого отклонения от ожидаемого результата создан воспроизводимый дефект.
-  После исправлений повторены упавший кейс, связанные границы и smoke.
