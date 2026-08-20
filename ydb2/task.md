---
title: "Проект: AI-агент службы поддержки"
---
## MCP ydb-tickets: Cloud Function + YDB

Подключаем к агенту собственный MCP-инструмент, который пишет обращения и историю диалогов в YDB Serverless. Используем тот же паттерн Cloud Function + MCP Hub, но теперь инструмент не обращается во внешнюю систему, а работает с нашей собственной БД внутри Yandex Cloud.

Архитектурно это один Cloud Function `ydb-tickets`, который подключается к YDB через SDK для вашего языка (в примерах — Python-пакет `ydb`, PyPI-имя для `ydb-python-sdk`, см. `https://github.com/ydb-platform/ydb-python-sdk`) и предоставляет три инструмента через MCP Hub:

| Инструмент | Что делает | Таблица |
| --- | --- | --- |
| `create-ticket` | Создаёт новый тикет и первую запись в истории | `tickets` + `messages` |
| `list-my-tickets` | Возвращает тикеты пользователя по `user_id` | `tickets` |
| `append-message` | Добавляет запись в историю диалога (ответ агента, уточнение пользователя) | `messages` |

Схема YDB:

```
CREATE TABLE tickets (

  id           Utf8,        -- UUID

  user_id      Utf8,        -- email отправителя

  category     Utf8,        -- bug | docs | feature | access

  status       Utf8,        -- open | answered | escalated | closed

  text         Utf8,        -- текст обращения (после PII-маскирования)

  created_at   Timestamp,

  updated_at   Timestamp,

  PRIMARY KEY (id),

  INDEX tickets_by_user GLOBAL ON (user_id)   -- вторичный индекс для «мои заявки»

);

CREATE TABLE messages (

  id           Utf8,        -- UUID

  ticket_id    Utf8,        -- ссылка на tickets.id

  role         Utf8,        -- user | agent

  text         Utf8,        -- текст сообщения (после PII-маскирования)

  model        Utf8,        -- какая модель отвечала (если role=agent)

  tokens_in    Uint64,

  tokens_out   Uint64,

  latency_ms   Uint32,

  created_at   Timestamp,

  PRIMARY KEY (ticket_id, id)

);
```

### Ссылки

-   [Yandex Managed Service for YDB](https://yandex.cloud/ru/docs/ydb/) — документация от Яндекс
-   [ydb-python-sdk — официальный Python-клиент для YDB](https://github.com/ydb-platform/ydb-python-sdk) — репозиторий на GitHub
-   [MCP Hub в Yandex AI Studio](https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/mcp-hub/) — документация Yandex AI Studio
-   [Responses API: создание ответа](https://aistudio.yandex.ru/docs/ru/ai-studio/api/Responses/createResponse.html) — справочник API Yandex AI Studio

### Задачи

1.  Таблицы в YDB уже созданы.
        
2.  Реализуйте Cloud Function `ydb-tickets` на Java. На вход — JSON. На выходе — JSON-ответ по контракту:
    
    -   `create-ticket` → `{"ticket_id": "...", "created_at": "..."}`.
    -   `list-my-tickets` → `[{"id": "...", "status": "...", "category": "...", "text": "...", "created_at": "..."}, ...]`.
    -   `append-message` → `{"message_id": "...", "ok": true}`.
3.  Контракт Cloud Function — три разных источника события (частый источник ошибок — см. «Подсказки»):
    
    -   Прямой invoke (`yc serverless function invoke`) — `event = {"action": "create-ticket", "user_id": "...", ...}`.
    -   HTTP через API Gateway — `event = {"httpMethod": "POST", "body": "<JSON-string>", ...}`.
    -   Вызов из MCP Hub — аргументы инструмента приходят прямо как `event` (без обёртки); диспетчеризация по набору ключей (см. «Подсказки»).
    
4.  Создайте MCP Hub gateway через CLI (а не только через UI):
    
    ```
    yc serverless mcp-gateway create \
    
      --name ydb-tickets-mcp \
    
      --service-account-id <SA_ID> \
    
      --tools-file src/ydb_tickets/mcp-tools.yaml
    ```
    
    Структура *mcp-tools.yaml* — список из 3 инструментов. Важно: поле `input_json_schema` должно быть строкой (JSON-encoded), не YAML-объектом. Пример:
    
    ```
    - name: create-ticket
    
      description: "Создать тикет"
    
      input_json_schema: '{"type":"object","properties":{"user_id":{"type":"string"}, ...},"required":["user_id"]}'
    
      action:
    
        function_call:
    
          function_id: <CF_ID>
    
          tag: $latest
    ```
    
    `<CF_ID>` — ID функции `ydb-tickets`: `yc serverless function get --name ydb-tickets --format json | jq -r .id`. Все 3 tool'а указывают на ту же CF `ydb-tickets`; диспетчеризация по ключам аргументов делается внутри функции.
    
5.  Подключите MCP к агенту. В текущей версии AI Studio SA к агенту не привязывается — как это устроено, разбирается в шаге про настройку AI Studio (раздел «Как работает авторизация»). Вместо этого MCP-инструменты передаются inline в запросе Responses API или через UI MCP Hub (если используете сохранённого агента в Workflows).
    
6.  В Responses API можно указать `require_approval: "never"` для MCP tool — иначе агент вернёт `mcp_approval_request` вместо `mcp_call`, и тикет не создастся:
    
    ```
    "tools": [{
    
        "type": "mcp",
    
        "server_label": "ydb-tickets",
    
        "server_url": "https://<mcpgw-id>.<salt>.mcpgw.serverless.yandexcloud.net/sse",
    
        "require_approval": "never",
    
    }]
    ```
    
    Полный SSE-URL шлюза (`<mcpgw-id>.<salt>`) возьмите из вывода `yc serverless mcp-gateway create` или позже: `yc serverless mcp-gateway get --name ydb-tickets-mcp`. В массиве `output[]` ответа эти два типа различаются так: `mcp_call` — агент уже выполнил tool (есть `arguments` и `output`), а `mcp_approval_request` — агент только просит разрешение (есть `approval_request_id`, сам MCP-сервер не вызывался). С `require_approval: "never"` вы видите только `mcp_call`; с default — нужно вторым запросом дослать `mcp_approval_response` с `approve: true/false`, чтобы turn продолжился.
    
7.  Обновить инструкции агента — опишите, когда агент должен вызывать каждый из трёх инструментов. Инструкция должна покрывать: когда заводить тикет (`create-ticket`), когда показывать пользователю его ранее созданные заявки (`list-my-tickets`), когда и зачем сохранять реплики в историю диалога (`append-message`). Точную формулировку подберите сами.
    
8.  Проверка end-to-end через почту (pull-режим из шага про email-workflow): «создай тикет категория bug текст:...» → в логах poller'а видно `mcp_call name=create-ticket`, в YDB появляется запись.
    

### Подсказки

-   Параметры в `ydb-python-sdk` работают только через prepared statements: `session.prepare(yql)` → `transaction().execute(prepared, {"$name": value})`, ключи — с префиксом `$`.
-   MCP Hub передаёт аргументы инструмента напрямую как `event` (без обёртки `{"tool": ...}`) — диспетчеризуйте action по набору ключей, иначе функция вернёт `unknown action: None`.
-   Если тикет не создаётся — проверьте в логах CF, что секреты подцепились (`YDB_ENDPOINT` непустой) и что в запросе стоит `require_approval: "never"`.

### Результат шага

-   В YDB созданы таблицы `tickets` и `messages`.
-   Cloud Function `ydb-tickets` развёрнута и отвечает корректным JSON на каждый из трёх `action`.
-   MCP-шлюз `ydb-tickets-mcp` создан через `yc serverless mcp-gateway create --tools-file`, к нему подключены 3 инструмента.
-   При обращении по почте агент вызывает `create-ticket` (видно в логах как `mcp_call name=create-ticket args={...}`), в YDB появляется запись. (PII-маскирование текста добавите на шаге про защиту.)

