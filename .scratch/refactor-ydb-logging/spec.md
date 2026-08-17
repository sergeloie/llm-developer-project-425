# Spec: Refactor YDB package — extract nested classes + add logging

## Problem

Cloud Function runtime на Java нестабильно работает с вложенными классами. Все вложенные классы пакета `ru.anseranser.ydb` нужно вынести в отдельные файлы. Кроме того, в пакете полностью отсутствует логирование — при дебаге в Yandex Cloud Functions невозможно понять, что происходит при вызове функции.

## Solution

1. **Вынести вложенные классы в отдельные файлы** — каждый вложенный тип становится отдельным `.java`-файлом в том же пакете `ru.anseranser.ydb`.
2. **Добавить логирование через `System.out.println`** — в начале и в конце каждой операции (включая внутренние методы), а также подробный вывод ошибок в каждом catch-блоке.

## Вложенные классы для извлечения

| Класс | Тип | Текущий файл | Новый файл |
|-------|-----|-------------|------------|
| `EventDispatcher.Action` | enum | `EventDispatcher.java:12-16` | `Action.java` |
| `EventDispatcher.DispatchResult` | record | `EventDispatcher.java:19` | `DispatchResult.java` |
| `YdbClient.StaticTokenProvider` | class | `YdbClient.java:260-271` | `StaticTokenProvider.java` |

## Формат логирования

Формат: `[ClassName] methodName: START/END/FINISHED ...`

Примеры:
```
[YdbTicketsHandler] handle: START event=...
[YdbTicketsHandler] handle: Dispatch resolved action=CREATE_TICKET
[YdbClient] createTicket: START userId=u1 category=bug textPreview=Hello world...
[YdbClient] createTicket: YDB transaction executed
[YdbClient] createTicket: FINISHED ticketId=abc-123
```

### Логирование ошибок

В catch-блоках — `e.getMessage()` + cause chain:
```
[YdbClient] createTicket: ERROR YDB error: ... | caused by: ...
```

Без `printStackTrace` — в логах Cloud Functions это длинный мусор.

### Логирование входных данных

В `createTicket` — метаданные (userId, category) + первые 100 символов text.
В остальных методах — только параметры запроса (без PII).

## Методы для логирования

Все публичные и внутренние операции:

| Класс | Метод | Точки логирования |
|-------|-------|-------------------|
| `YdbTicketsHandler` | `handle()` | START, action resolved, END/ERROR |
| `YdbTicketsHandler` | `getOrCreateYdbClient()` | START (только при инициализации), END |
| `YdbClient` | `createTicket()` | START, YDB executed, FINISHED, ERROR |
| `YdbClient` | `listMyTickets()` | START, YDB queried, FINISHED, ERROR |
| `YdbClient` | `appendMessage()` | START, YDB executed, FINISHED, ERROR |
| `YdbClient` | `executeQuery()` | START, FINISHED, ERROR |
| `YdbClient` | `executeInTransaction()` | START, committed, FINISHED, ERROR |
| `YdbClient` | `convertParams()` | START, FINISHED |
| `EventDispatcher` | `dispatchAndExtract()` | START, resolved action, FINISHED, ERROR |
| `EventDispatcher` | `parseEvent()` | START, FINISHED, ERROR |
| `EventDispatcher` | `extractBodyFromJson()` | START, FINISHED |
| `EventDispatcher` | `dispatchFromDirectInvoke()` | START, FINISHED, ERROR |
| `EventDispatcher` | `dispatchFromMcpHub()` | START, FINISHED, ERROR |

## Пользовательские истории

1. Как разработчик я хочу видеть в логах CF начало и конец каждой операции, чтобы понимать где функция "застряла".
2. Как разработчик я хочу видеть подробные сообщения об ошибках в catch-блоках, чтобы не подключать отладку для простых случаев.
3. Как разработчик я хочу, чтобы вложенные классы были в отдельных файлах, чтобы Cloud Function runtime корректно их загружал.

## Решения по реализации

- Извлечение классов — чистый move, без изменения логики.
- Логирование — только `System.out.println`, без фреймворков логирования (SLF4J, Log4j и т.д.) — в CF они не доступны по умолчанию.
- Тесты не меняются — логирование не влияет на поведение, проверяется только через compilation.
- Пакет `ru.anseranser.ydb2` не затрагивается.

## Решения по тестам

- После изменений запустить `mvn compile test` для проверки compilation и всех существующих тестов.
- Новые тесты на логирование не пишутся — это инфраструктурный код, проверяется визуально в логах CF.

## Out of Scope

- Пакет `ru.anseranser.ydb2` — не перерабатывается.
- Фреймворки логирования (SLF4J, Logback, Log4j) — не引入ются.
- Логирование PII-данных (полный текст обращения) — не делается.
- Изменение логики работы методов — только структурные изменения + логирование.
- `plans/refactor-ydb-package.md` — существующий план по рефакторингу конструкторов не затрагивается этим изменением.

## Критерии готовности

1. Все вложенные классы вынесены в отдельные файлы (`Action.java`, `DispatchResult.java`, `StaticTokenProvider.java`).
2. `EventDispatcher.java` и `YdbClient.java` не содержат вложенных типов.
3. Каждый публичный и внутренний метод содержит `System.out.println` в начале и в конце.
4. Каждый catch-блок содержит `System.out.println` с `e.getMessage()` и cause chain.
5. `mvn compile test` проходит без ошибок.
6. Все существующие тесты зелёные.
