# Test-design ядра: Authorization и Card-Management

Основание: `tz/04-authorization.md` и `tz/05-card-management.md`. Денежные значения передаются в копейках. В артефакте используются только техники лекции 3: классы эквивалентности, граничные значения и pairwise.

## 0. Идентификаторы требований и тестовые данные

| ID | Требование |
|---|---|
| AUTH-01 | Неизвестный PAN отклоняется с `responseCode=14`. |
| AUTH-02 | Только ACTIVE допускается дальше; INACTIVE/BLOCKED отклоняются с соответствующей причиной, EXPIRED — с кодом 54. |
| AUTH-03 | Истёкший `expiryDate` отклоняется с кодом 54; текущий месяц ещё действителен. |
| AUTH-04 | `dailyUsage + amount <= dailyLimit`, иначе decline 61. |
| AUTH-05 | `monthlyUsage + amount <= monthlyLimit`, иначе decline 61. |
| AUTH-06 | `amount <= availableBalance`, иначе decline 51. |
| AUTH-07 | При успехе выполняется reserve, возвращаются `APPROVED/00`, уникальный 12-значный RRN и authCode `[A-Z0-9]{6}`. |
| AUTH-08 | При недоступности CMS — `DECLINED/05`, причина `ISSUER_TIMEOUT`, без изменения денег и usage. |
| AUTH-09 | Bin Lookup имеет retry/fallback; его отказ не останавливает авторизацию. |
| CMS-01 | Создание даёт ACTIVE-карту, PAN из 16 цифр с Luhn и expiry +3 года. |
| CMS-02 | GET по PAN возвращает карту или 404; невалидный PAN отклоняется. |
| CMS-03 | Список поддерживает default `limit=50`, `offset=0`, пагинацию и фильтры status/BIN. |
| CMS-04 | PATCH изменяет только переданные статус, лимиты и баланс. |
| CMS-05 | DELETE выполняет soft delete; DELETED не выдаётся и не участвует в операциях. |
| CMS-06 | Генератор соблюдает count, BIN, диапазоны, `monthly=daily×30` и распределение статусов 95/3/2%. |
| CMS-07 | Reserve положительной суммы не больше balance уменьшает balance ровно один раз. |

Базовая карта: PAN `4000003458730237`, status ACTIVE, expiry — следующий месяц, dailyLimit 10 000, monthlyLimit 30 000, balance 20 000, dailyUsage 2 000, monthlyUsage 5 000. Для pairwise значения `Within/Exceeded` задаются относительно текущего остатка соответствующего ограничения. Неприменимые к выбранной операции параметры строки не передаются в запрос и не влияют на oracle.

## 1. Классы эквивалентности

| Поле | Класс | Представитель | Ожидаемый результат | Требование |
|---|---|---|---|---|
| Card status | Допустимый | ACTIVE | Перейти к expiry | AUTH-02 |
| Card status | Недопустимые бизнес-состояния | INACTIVE, BLOCKED | Decline с соответствующей причиной | AUTH-02 |
| Card status | Истёкший | EXPIRED | `DECLINED/54` | AUTH-02 |
| PAN | Существующий валидный | `4000003458730237` | Карта найдена | AUTH-01, CMS-02 |
| PAN | Валидный по формату, отсутствующий | `4000000000000002` | GET 404 / authorization 14 | AUTH-01, CMS-02 |
| PAN | Невалидный формат | `4000ABC` | 400, бизнес-операция не выполняется | CMS-02 |
| Expiry | Действующий | Текущий/будущий месяц | Перейти к лимитам | AUTH-03 |
| Expiry | Истёкший | Предыдущий месяц | `DECLINED/54` | AUTH-03 |
| Daily usage + amount | В пределах / превышает | 10 000 / 10 001 при limit 10 000 | Продолжить / `DECLINED/61` | AUTH-04 |
| Monthly usage + amount | В пределах / превышает | 30 000 / 30 001 при limit 30 000 | Продолжить / `DECLINED/61` | AUTH-05 |
| Amount относительно balance | Достаточно / недостаточно | 20 000 / 20 001 при balance 20 000 | Продолжить / `DECLINED/51` | AUTH-06 |
| CMS | Доступен / недоступен | 200 / timeout | Продолжить / `DECLINED/05` | AUTH-08 |
| Bin Lookup | Успех / недоступен | 200 / два 5xx | Обогащение / fallback | AUTH-09 |
| Лимиты CMS | Неотрицательные / отрицательные | 0, 1 / -1 | Принять / 400 | CMS-01, CMS-04 |
| Reserve amount | Положительный в balance / неположительный / выше balance | 1 / 0 / balance+1 | 200 / 400 / отказ без изменения | CMS-07 |
| Pagination | Валидная / невалидная | `limit=50,offset=0` / `limit=0,offset=-1` | 200 / 400 | CMS-03 |
| BIN при создании | Зарегистрированный / отсутствующий | `400000` / `999999` | Создание / контролируемый отказ | CMS-01 |

## 2. Граничные значения

Шаг для денег — 1 копейка. ON — значение на границе, OFF — ближайшее значение по обе стороны.

| Поле | Граница | ON | OFF | Ожидаемый результат | Требование |
|---|---:|---|---|---|---|
| Сумма к дневному остатку | 8 000 при usage 2 000 и limit 10 000 | 8 000 | 7 999; 8 001 | 7 999/8 000 проходят; 8 001 → 61 | AUTH-04 |
| Сумма к месячному остатку | 25 000 при usage 5 000 и limit 30 000 | 25 000 | 24 999; 25 001 | 24 999/25 000 проходят; 25 001 → 61 | AUTH-05 |
| Сумма к balance | 20 000 | 20 000 | 19 999; 20 001 | 19 999/20 000 проходят; 20 001 → 51 | AUTH-06 |
| Минимальная сумма authorize | 0 | 0 | -1; 1 | -1 → 400; 0/1 — согласно контракту | AUTH-04..AUTH-07 |
| DailyLimit при create/PATCH | 0 | 0 | -1; 1 | -1 → 400; 0/1 сохраняются | CMS-01, CMS-04 |
| MonthlyLimit при create/PATCH | 0 | 0 | -1; 1 | -1 → 400; 0/1 сохраняются | CMS-01, CMS-04 |
| Reserve относительно balance | 20 000 | 20 000 | 19 999; 20 001 | До границы 200; выше — отказ без изменения | CMS-07 |
| Минимальный reserve | 1 | 1 | 0; 2 | 0 → 400; 1/2 резервируются | CMS-07 |
| List limit/default | 50 | 50 | 49; 51 | Не более requested; без параметра 50 | CMS-03 |
| List offset | 0 | 0 | -1; 1 | -1 → 400; 0/1 корректно смещают выборку | CMS-03 |
| Expiry относительно текущего месяца | Текущий месяц | Текущий | Предыдущий; следующий | Предыдущий → 54; текущий/следующий действуют | AUTH-03 |
| Generate count | 1 | 1 | 0; 2 | 0 отклоняется; 1/2 создают точное число карт | CMS-06 |

## 3. Попарное тестирование (pairwise)

### 3.1 Модели и наборы

- Authorization: модель из 8 параметров и трёх ограничений — [`pict/model.txt`](pict/model.txt); набор из 11 строк — [`pict/cases.txt`](pict/cases.txt).
- Card Management: модель из 8 параметров и одного ограничения — [`pict/model-card-management.txt`](pict/model-card-management.txt); набор из 26 строк — [`pict/cases-card-management.txt`](pict/cases-card-management.txt).
- Формат `cases*.txt`: tab-separated, первая строка — заголовок. Номер строки данных используется в колонке «Источник» тест-кейсов.

2-wise покрытие выбрано, потому что число полных комбинаций велико: Authorization — до 512, Card Management — до 15 552 до ограничений. Pairwise покрывает взаимодействие каждой пары значений при компактном регрессионном наборе. Оно не доказывает корректность комбинаций из трёх и более условий и не заменяет доменные граничные проверки.

### 3.2 Ограничения моделей

- У отсутствующей карты status/expiry неизвестны; в модели им назначены нейтральные `ACTIVE/Valid`, чтобы не создавать ложные комбинации.
- При недоступном CMS нельзя достоверно установить отсутствие карты, поэтому `CMS=Unavailable` сочетается с `CardExists=Yes` как подготовленным fixture.
- Создание карты возможно только для зарегистрированного BIN; negative BIN проверяется отдельно как класс эквивалентности.

### 3.3 Критичные сочетания, добавленные вручную

Pairwise-строка может завершиться на ранней проверке и не активировать более позднее условие. Поэтому вручную добавлены: точные границы daily/monthly/balance; одновременное превышение daily и balance; успешный reserve; ошибка reserve после успешных проверок; повтор RRN; soft delete с последующими GET/list/reserve; генерация карт на минимальном count.

## 4. Тест-кейсы

### 4.1 Authorization — проекция каждой строки `cases.txt`

Общий шаг: подготовить fixture по строке набора и отправить валидный POST `/api/internal/authorize`. Более ранняя проверка определяет результат; поля после неё не должны менять данные.

| ID | Требование | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|
| AUTH-P01 | AUTH-07, AUTH-09 | Pairwise AUTH row 1 | Все условия допустимы; Bin Lookup успешен | Выполнить общий шаг | `APPROVED/00`, reserve один раз, валидные RRN/authCode |
| AUTH-P02 | AUTH-08 | Pairwise AUTH row 2 | CMS недоступен | Выполнить общий шаг | `DECLINED/05`, `ISSUER_TIMEOUT`, изменений нет |
| AUTH-P03 | AUTH-01, AUTH-09 | Pairwise AUTH row 3 | Карта отсутствует; Bin Lookup недоступен | Выполнить общий шаг | `DECLINED/14`, fallback не приводит к reserve |
| AUTH-P04 | AUTH-08 | Pairwise AUTH row 4 | CMS недоступен | Выполнить общий шаг | `DECLINED/05`, изменений нет |
| AUTH-P05 | AUTH-08, AUTH-09 | Pairwise AUTH row 5 | CMS и Bin Lookup недоступны | Выполнить общий шаг | Контролируемый `DECLINED/05`, без изменения данных |
| AUTH-P06 | AUTH-02 | Pairwise AUTH row 6 | Карта найдена, status EXPIRED | Выполнить общий шаг | `DECLINED/54`, лимиты/balance не меняются |
| AUTH-P07 | AUTH-02 | Pairwise AUTH row 7 | Карта INACTIVE | Выполнить общий шаг | `DECLINED`, причина `CARD_INACTIVE`, reserve не вызван |
| AUTH-P08 | AUTH-02, AUTH-09 | Pairwise AUTH row 8 | Карта BLOCKED; Bin Lookup недоступен | Выполнить общий шаг | `DECLINED`, причина `CARD_BLOCKED`, reserve не вызван |
| AUTH-P09 | AUTH-01 | Pairwise AUTH row 9 | Карта отсутствует | Выполнить общий шаг | `DECLINED/14`, значения лимитов не влияют |
| AUTH-P10 | AUTH-08 | Pairwise AUTH row 10 | CMS недоступен | Выполнить общий шаг | `DECLINED/05`, изменений нет |
| AUTH-P11 | AUTH-02 | Pairwise AUTH row 11 | Карта BLOCKED | Выполнить общий шаг | `DECLINED`, `CARD_BLOCKED`, поздние условия не вычисляются |

### 4.2 Card Management — проекция каждой строки `cases-card-management.txt`

Для операции используются только относящиеся к ней параметры строки: create — BIN/лимиты/initialBalance; get/delete — PAN; list — status/pagination/BIN; patch — PAN/status/лимиты/availableBalance; reserve — PAN/status/amount.

| ID | Требование | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|
| CMS-P01 | CMS-01 | Pairwise CMS row 1 | Зарегистрирован BIN | POST create с нулевыми лимитами/balance | 201, ACTIVE, валидные PAN/Luhn и expiry |
| CMS-P02 | CMS-02 | Pairwise CMS row 2 | Существует INACTIVE-карта | GET по PAN | 200, возвращена нужная карта |
| CMS-P03 | CMS-02 | Pairwise CMS row 3 | PAN отсутствует | GET по PAN | 404 |
| CMS-P04 | CMS-03 | Pairwise CMS row 4 | Есть EXPIRED-карты | GET list с EXPIRED и invalid pagination | 400, выборка не выполняется |
| CMS-P05 | CMS-04 | Pairwise CMS row 5 | PAN отсутствует, monthlyLimit=-1 | PATCH | 400 валидации, ничего не создано/изменено |
| CMS-P06 | CMS-04 | Pairwise CMS row 6 | PAN невалиден, dailyLimit=-1 | PATCH | 400, изменений нет |
| CMS-P07 | CMS-05 | Pairwise CMS row 7 | PAN отсутствует | DELETE | 404 |
| CMS-P08 | CMS-05 | Pairwise CMS row 8 | PAN невалиден | DELETE | 400 |
| CMS-P09 | CMS-07 | Pairwise CMS row 9 | Существует INACTIVE-карта | POST reserve amount=0 | 400, balance не меняется |
| CMS-P10 | CMS-01 | Pairwise CMS row 10 | Зарегистрирован BIN, monthlyLimit=-1 | POST create | 400, карта не создана |
| CMS-P11 | CMS-07 | Pairwise CMS row 11 | BLOCKED-карта, amount>balance | POST reserve | Отказ; balance не меняется |
| CMS-P12 | CMS-03 | Pairwise CMS row 12 | Есть ACTIVE-карты нужного BIN | GET list с default pagination | 200, только совпавшие карты, не более 50 |
| CMS-P13 | CMS-03 | Pairwise CMS row 13 | Есть ACTIVE-карты | GET list с валидной pagination | 200, корректные `cards` и `total` |
| CMS-P14 | CMS-07 | Pairwise CMS row 14 | PAN отсутствует, amount=balance fixture | POST reserve | 404, баланс других карт не меняется |
| CMS-P15 | CMS-02 | Pairwise CMS row 15 | PAN невалиден | GET по PAN | 400 |
| CMS-P16 | CMS-04 | Pairwise CMS row 16 | Существует карта | PATCH status=BLOCKED, оба limit=0, balance в допустимом диапазоне | 200; изменены только переданные поля |
| CMS-P17 | CMS-01 | Pairwise CMS row 17 | Зарегистрирован BIN, dailyLimit=-1 | POST create | 400, карта не создана |
| CMS-P18 | CMS-03 | Pairwise CMS row 18 | Есть BLOCKED-карты | GET list с invalid pagination | 400 |
| CMS-P19 | CMS-05 | Pairwise CMS row 19 | Существует ACTIVE-карта | DELETE | 204; карта получает внутренний DELETED |
| CMS-P20 | CMS-02 | Pairwise CMS row 20 | Существует EXPIRED-карта | GET по PAN | 200 с status EXPIRED |
| CMS-P21 | CMS-07 | Pairwise CMS row 21 | PAN невалиден | POST reserve | 400, изменений нет |
| CMS-P22 | CMS-01 | Pairwise CMS row 22 | Зарегистрирован BIN | POST create с нулевыми лимитами и положительным balance | 201, поля сохранены, PAN проходит Luhn |
| CMS-P23 | CMS-04 | Pairwise CMS row 23 | Существует карта | PATCH status=INACTIVE, limits=0, balance=граница | 200; непереданные поля прежние |
| CMS-P24 | CMS-05 | Pairwise CMS row 24 | Существует EXPIRED-карта | DELETE | 204; последующий GET даёт 404 |
| CMS-P25 | CMS-02 | Pairwise CMS row 25 | Существует ACTIVE-карта | GET по PAN | 200, данные совпадают с fixture |
| CMS-P26 | CMS-03 | Pairwise CMS row 26 | Есть INACTIVE-карты | GET list без pagination с filter INACTIVE | 200, до 50 INACTIVE, корректный total |

### 4.3 Ручные критичные и граничные тесты

| ID | Требование | Источник | Предусловие | Шаги | Ожидаемый результат |
|---|---|---|---|---|---|
| AUTH-B01 | AUTH-04 | Daily boundary | usage=2 000, limit=10 000 | Изолированно authorize 7 999, 8 000, 8 001 | Первые два проходят daily; 8 001 → 61 |
| AUTH-B02 | AUTH-05 | Monthly boundary | usage=5 000, limit=30 000; daily достаточен | Изолированно authorize 24 999, 25 000, 25 001 | Первые два проходят monthly; 25 001 → 61 |
| AUTH-B03 | AUTH-06 | Balance boundary | balance=20 000; лимиты достаточны | Изолированно authorize 19 999, 20 000, 20 001 | Первые два проходят; 20 001 → 51 |
| AUTH-M01 | AUTH-04, AUTH-06 | Critical manual | daily остаток=500, balance=100, amount=501 | Выполнить authorize | Возвращается 61: daily проверяется раньше balance |
| AUTH-M02 | AUTH-07, AUTH-08 | Critical manual | GET карты успешен, reserve stub возвращает 503 | Выполнить authorize; проверить balance/usage | Decline; balance и usage не изменены |
| AUTH-M03 | AUTH-03 | Expiry boundary | Текущий месяц зафиксирован | Проверить предыдущий/текущий/следующий expiry | Предыдущий → 54; текущий и следующий проходят |
| CMS-B01 | CMS-07 | Reserve boundary | ACTIVE, balance=20 000 | Изолированно reserve 0, 1, 20 000, 20 001 | 0 → 400; 1/20 000 → 200; выше balance — отказ |
| CMS-M01 | CMS-07 | Critical manual | ACTIVE, новый RRN | Дважды reserve с одинаковым RRN | Balance уменьшается только первым запросом |
| CMS-M02 | CMS-05 | Critical manual | ACTIVE-карта | DELETE; затем GET, list, reserve и authorize | Карта нигде не выдаётся и не участвует в операции |
| CMS-M03 | CMS-06 | Count boundary | Зарегистрированы два BIN | Generate count 0, 1, 2 | 0 отклоняется; создаётся ровно 1/2 карты с инвариантами генератора |

## 5. Прослеживаемость

| Требование | Тест-кейсы |
|---|---|
| AUTH-01 | AUTH-P03, AUTH-P09 |
| AUTH-02 | AUTH-P06, AUTH-P07, AUTH-P08, AUTH-P11 |
| AUTH-03 | AUTH-M03 |
| AUTH-04 | AUTH-B01, AUTH-M01 |
| AUTH-05 | AUTH-B02 |
| AUTH-06 | AUTH-B03, AUTH-M01 |
| AUTH-07 | AUTH-P01, AUTH-M02 |
| AUTH-08 | AUTH-P02, AUTH-P04, AUTH-P05, AUTH-P10, AUTH-M02 |
| AUTH-09 | AUTH-P01, AUTH-P03, AUTH-P05, AUTH-P08 |
| CMS-01 | CMS-P01, CMS-P10, CMS-P17, CMS-P22 |
| CMS-02 | CMS-P02, CMS-P03, CMS-P15, CMS-P20, CMS-P25 |
| CMS-03 | CMS-P04, CMS-P12, CMS-P13, CMS-P18, CMS-P26 |
| CMS-04 | CMS-P05, CMS-P06, CMS-P16, CMS-P23 |
| CMS-05 | CMS-P07, CMS-P08, CMS-P19, CMS-P24, CMS-M02 |
| CMS-06 | CMS-M03 |
| CMS-07 | CMS-P09, CMS-P11, CMS-P14, CMS-P21, CMS-B01, CMS-M01 |
