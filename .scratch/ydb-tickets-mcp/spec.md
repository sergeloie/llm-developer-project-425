# Спецификация: ydb-tickets-mcp

## Проблема

AI-агент службы поддержки не имеет собственного MCP-инструмента для работы с тикетами. Текущая реализация обрабатывает только email-входящие, но не может создавать и управлять обращениями в YDB. Нужен Cloud Function, который предоставляет 3 MCP-инструмента для записи обращений и истории диалогов в YDB Serverless.

## Решение

Создать Cloud Function `ydb-tickets` на Java 21, которая:
1. Подключается к YDB через official SDK
2. Предоставляет 3 инструмента через MCP Hub: `create-ticket`, `list-my-tickets`, `append-message`
3. Обрабатывает 3 типа входных событий: direct invoke, API Gateway, MCP Hub
4. Использует prepared statements для YDB-операций

## Пользовательские истории

### create-ticket
1. Как AI-агент, я хочу создать тикет, чтобы зарегистрировать обращение пользователя в YDB
2. Как пользователь, я хочу, чтобы мое обращение сохранялось с категорией (bug/docs/feature/access) и статусом open
3. Как система, я хочу, чтобы при создании тикета автоматически создавалась первая запись в истории диалогов

### list-my-tickets
4. Как AI-агент, я хочу показать пользователю его ранее созданные заявки, чтобы он мог видеть историю обращений
5. Как пользователь, я хочу видеть список своих тикетов с статусами и датами создания
6. Как система, я хочу, чтобы запрос списка тикетов работал через вторичный индекс по user_id

### append-message
7. Как AI-агент, я хочу добавлять ответы в историю диалога, чтобы сохранять контекст беседы
8. Как пользователь, я хочу, чтобы каждое сообщение (мое и агента) сохранялось с метаданными (модель, токены, латентность)
9. Как система, я хочу, чтобы сообщения хранились в отдельной таблице с привязкой к тикету

### Общие
10. Как разработчик, я хочу, чтобы функция обрабатывала 3 типа входов (direct invoke, API Gateway, MCP Hub) без дублирования логики
11. Как разработчик, я хочу, чтобы функция возвращала JSON-ответы по контракту для каждого инструмента
12. Как разработчик, я хочу, чтобы credentials YDB передавались через environment variables

## Решения по реализации

### Архитектура функции
- Один handler-класс `YdbTicketsHandler` реализует `YcFunction<String, String>`
- Метод-диспетчер `dispatch(String event)` определяет тип входа по ключам:
  - Если есть `httpMethod` → API Gateway (извлекаем body)
  - Если есть `action` → direct invoke
  - Иначе → MCP Hub (диспетчеризация по набору ключей: `user_id`/`ticket_id`/`text`)
- Внутренний класс `Action` enum: `CREATE_TICKET`, `LIST_MY_TICKETS`, `APPEND_MESSAGE`

### YDB операции
- Подключение: `System.getenv("YDB_ENDPOINT")`, `System.getenv("YDB_DATABASE")`, `System.getenv("YDB_TOKEN")`
- Prepared statements: `session.prepare(yql)` → `transaction().execute(prepared, {"$name": value})`
- Транзакции: одна транзакция на операцию (create-ticket создает и тикет, и сообщение)
- UUID: `java.util.UUID.randomUUID().toString()`
- Timestamps: `Instant.now()` → конвертация в YDB Timestamp

### Контракты ответов
- `create-ticket`: `{"ticket_id": "...", "created_at": "..."}`
- `list-my-tickets`: `[{"id": "...", "status": "...", "category": "...", "text": "...", "created_at": "..."}]`
- `append-message`: `{"message_id": "...", "ok": true}`

### MCP Tools Configuration
- Файл `mcp-tools.yaml` в корне проекта
- 3 инструмента с `input_json_schema` как JSON-encoded строка
- Все инструменты указывают на одну CF `ydb-tickets`

### Пакет и структура
- Пакет: `ru.anseranser.ydb`
- Handler: `YdbTicketsHandler`
- YDB клиент: `YdbClient` (обертка над SDK)
- Dispatcher: `EventDispatcher` (диспетчеризация типов событий)

## Решения по тестам

- Юнит-тесты с моками YDB Session/Transaction
- Тестирование диспетчеризации 3 типов событий
- Тестирование каждого инструмента (create-ticket, list-my-tickets, append-message)
- Паттерн: Mockito + JUnit 5 (по аналогии с EmailHandlerTest)
- Интеграционные тесты с реальным YDB — опционально, в Out of Scope для первого релиза

## Out of Scope

- PII-маскирование текста (упомянуто в task.md, отложено на шаг про защиту)
- Интеграция с AI Studio (шаг про настройку AI Studio)
- Интеграционные тесты с реальным YDB
- Автоматический деплой (скрипты описаны в README)
- Мониторинг и алерты
- Rate limiting и retry логика

## Критерии готовности

- [ ] Cloud Function `ydb-tickets` создана и развёрнута
- [ ] 3 инструмента работают через MCP Hub
- [ ] Direct invoke возвращает корректный JSON для каждого action
- [ ] API Gateway обрабатывает POST запросы
- [ ] YDB credentials берутся из environment variables
- [ ] Prepared statements используются для всех YDB-операций
- [ ] Юнит-тесты проходят для всех компонентов
- [ ] mcp-tools.yaml создан и валиден
