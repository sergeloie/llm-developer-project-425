---
title: "Проект: AI-агент службы поддержки"
source: "https://ru.hexlet.io/projects/425/members/53300?step=9"
site: ru.hexlet.io
saved: "2026-08-26T18:36:29.567Z"
---
## QA, токены, трейсы

Тестируем агента вручную по чек-листу, разбираемся, где в AI Studio смотреть потраченные токены и трейсы вызовов, и готовим проект к сдаче.

Финальная проверка. Вы проходите по сценарию end-to-end, фиксируете артефакты (скрины трейсов, отчёт по токенам, дамп из YDB) и оцениваете стоимость владения.

### Ссылки

-   [Справочник YQL](https://ydb.tech/docs/ru/yql/reference/) — документация YDB

### Задачи

1.  Сквозной сценарий (зафиксируйте скриншотами):
    
    -   Пользователь пишет на адрес Help Desk (почта): «у меня сломался принтер, что делать?»
    -   Агент (через `email-poller`) забирает непрочитанное письмо → вызывает Responses API → отвечает отправщику по SMTP.
    -   Пользователь: «не помогло, создай тикет категория bug».
    -   Агент вызывает MCP `create-ticket` → возвращает `ticket_id` в письме-ответе и сохраняет свой ответ через `append-message` (role=agent).
    -   Самопроверка через CF:
        
        1
        
        2
        
        ```
        yc serverless function invoke ydb-tickets \
        
          --data '{"action":"list-my-tickets","user_id":"<your-email>"}'
        ```
        
        Запись о тикете должна совпасть с тем, что вернул агент. `yc ydb yql execute` не существует — проверяйте через CF.
2.  Где смотреть трейсы:
    
    -   Email poller: `yc logging read --filter resource_id=<CF_ID>` — видны `GOT_UNSEEN`, `MSG ... from=...`, `MCP session started`, `mcp_call name=create-ticket args={...}`, `AGENT_OK`, `SEND_OK`.
    -   Workflow `daily-escalation`: `yc serverless workflow execution get <execution_id>` — в поле `result.result_json` полный output; в `error.message` если упало.
    -   MCP gateway: `yc logging read --filter resource_id=<MCP_GW_ID>` — видны `MCP session started`, `Tool call started`, `Tool call finished`.
    -   AI Studio UI: для сохранённого агента — вкладка Traces; для inline Responses API — `output[]` массив в response (с типами `mcp_list_tools`, `mcp_call`, `message`).
3.  Подсчёт токенов. В ответе Responses API есть поле `usage`:
    
    1
    
    2
    
    3
    
    4
    
    5
    
    6
    
    7
    
    ```
    "usage": {
    
      "input_tokens": 14,
    
      "output_tokens": 2,
    
      "total_tokens": 16,
    
      "input_tokens_details": { "cached_tokens": 0 },
    
      "output_tokens_details": { "reasoning_tokens": 0 }
    
    }
    ```
    
    Сравните с `tokens_in` / `tokens_out` в таблице `messages` — должны примерно совпадать (расхождение ≤10%). Токены берутся из поля `usage` ответа и передаются в `append-message`.
    
4.  Негативные сценарии:
    
    -   injection в текст обращения → должен быть отражён guardrail'ом из шага про защиту (классификатор).
    -   обращение вне базы → бот честно говорит «не знаю» и предлагает создать тикет.
    -   недоступность YDB → шаги `databaseQuery` в workflow упадут с понятной ошибкой (`error.message` покажет YDB-код); в CF `ydb-tickets` — Exception 500.
    -   PII в обращении (телефон, e-mail, номер карты) → в `tickets.text` должно появиться маскированное значение (`+7 (***) ***-**-NN`, `[email]`, `****-****-****-****`).
5.  Чек-лист сдачи в *README.md*:
    
    -   адрес Help Desk-ящика (с пометкой про 60-секундный latency из-за pull-архитектуры);
    -   ссылка на репозиторий с конфигами;
    -   ссылка на агент в AI Studio (если есть доступ);
    -   список того, что работает и что не работает.

### Подсказки

-   Приложите скриншоты сквозного сценария (обращение → ответ, тикет в YDB, трейс) — проверяющий не должен воспроизводить ваши шаги вручную. Положить их в репозиторий или приложить к сдаче — на ваш выбор; в репозитории проще, не нужен внешний хостинг.
-   Трейс для inline Responses API всегда лежит в массиве `output[]`; для сохранённого агента трейсы включаются в его настройках (вкладка Traces).
-   Если тикет не появился в YDB — агент не вызвал `create-ticket`: проверьте, что MCP-инструмент подключён и стоит `require_approval: "never"`.
-   App-password для почты держите в Lockbox (секрет `email-credentials`), а не в `workflow.yaml` и не в коде поллера: в YaWL нет прямого lockbox-шага, правильный обход — Cloud Function с `--secret environment-variable=...,name=email-credentials,key=password`.
-   `append-message` может писать `tokens_in/out` неточно: агент не всегда передаёт токены из `usage` — доставайте их отдельно или логируйте на стороне poller'а.

### Результат шага

-   Проект задеплоен, публично доступен, работает end-to-end.
-   Вы можете объяснить каждую стрелку на архитектурной диаграмме (см. *README.md*) — включая pull-архитектуру, MCP Hub dispatch и PII-маскирование.
-   В *README.md* есть раздел «что попробовать» с 3-4 готовыми промптами для проверяющего.

Комментарии

Направления

[Программирование](/courses_programming) [ИИ](/courses_artificial-intelligence) [Аналитика](/courses_data_analytics) [DevOps](/courses_devops) [Тестирование](/courses_testing) [Фронтенд](/courses_front_end_dev) [Бэкенд](/courses_backend_development)

[](mailto:support@hexlet.io)

[support@hexlet.io](mailto:support@hexlet.io)

[

t.me/hexlet\_help\_bot

](https://t.me/hexlet_help_bot)

RU

[EN](/locale/switch?new_locale=en) [KZ](/locale/switch?new_locale=kz)

[+7 800 100 22 47](tel:+78001002247)

бесплатно по РФ

[+7 495 085 21 62](tel:+74950852162)

бесплатно по Москве

[Правовая информация](/pages/legal) [Оферта](/pages/offer) [Лицензия](/pages/license) [Контакты](/pages/contacts)

108813 г. Москва, вн.тер.г. поселение Московский,

г. Московский, ул. Солнечная, д. 3А, стр. 1, помещ. 20Б/3

ОГРН 1217300010476

ИНН 7325174845