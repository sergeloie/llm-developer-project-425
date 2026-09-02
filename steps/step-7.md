---
title: "Проект: AI-агент службы поддержки"
source: "https://ru.hexlet.io/projects/425/members/53300?step=7"
site: ru.hexlet.io
saved: "2026-08-26T18:21:21.719Z"
---
## Подключение RAG: Help Desk

Загружаем корпоративную базу знаний (документация, FAQ, регламенты) в векторное хранилище Яндекса и подключаем её к агенту через инструмент `file_search`. После этого шага бот превращается в полноценный Help Desk: отвечает на вопросы пользователя на основе документов, а не придумывает ответ.

К этому моменту агент уже умеет:

-   общаться с пользователем по почте (шаг про email-workflow),
-   сохранять обращения в YDB через MCP `ydb-tickets` (шаги про MCP и авто-эскалацию).

Но без базы знаний он галлюцинирует. RAG закрывает этот пробел: перед ответом агент ищет релевантные документы и формирует ответ на их основе.

Поток:


```
пользователь: "как оформить командировку?"

  │

  ▼

агент → file_search → top-5 релевантных документов

  │

  ▼

агент формирует ответ на основе документов + ссылается на них

  │

  ▼

если ответа нет → через MCP ydb-tickets создаётся тикет в YDB
```

### Ссылки

-   [Поисковые индексы Vector Store](https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/search/vectorstore.html) — документация Yandex AI Studio

### Задачи

1.  Подготовьте корпус документов (markdown / PDF). Минимум — 5-10 документов про HR / IT / администрирование. Если документы разбиты по темам — решите: либо один большой индекс, либо несколько меньших (см. ниже ограничение file\_search).
2.  Создайте search index — через CLI `yandex-ai-studio vector-stores local` (локальные файлы), `s3` (бакет), `confluence`, `wiki`. Полный список флагов — `yandex-ai-studio vector-stores local --help`. Доступны `--name`, `--max-file-size`, `--expires-after-days`, `--poll-timeout`. Выбор embedding-модели происходит на стороне AI Studio (подробнее — в блоке «Ссылки»).
    
    ```
    source .venv/bin/activate
    
    yandex-ai-studio vector-stores local docs/*.md docs/*.pdf --name "help-desk-kb"
    ```
    
    Команда вернёт `search_index_id` (строка вида `fvt...`) — сохраните его.
3.  Получите ID уже созданных индексов (если они создавались в UI AI Studio или другим путём) — через `yandex_ai_studio_sdk`:
    
   
    ```
    from yandex_ai_studio_sdk import AIStudio
    
    ai = AIStudio()  # подхватит YC_FOLDER_ID и YC_IAM_TOKEN из env
    
    for idx in ai.search_indexes.list():
    
        print(idx.id, idx.name)
    ```
    
    yc CLI для search indexes НЕТ — только SDK или UI.
4.  Подключите индекс к агенту через `file_search` tool в Responses API. Никакого UI-шага не нужно, если вы вызываете агента inline (как в функции `email-poller`):

    ```
    "tools": [
    
        {
    
            "type": "file_search",
    
            "vector_store_ids": [<single_search_index_id>],
    
        },
    
        # ... другие tools (MCP, function)
    
    ]
    ```
    
5.  Пропишите в инструкциях (поле `instructions` в Responses API, не UI) поведение агента: перед ответом обращаться к базе знаний через `file_search`; при найденном ответе — коротко + ссылка на документ; при отсутствии — честно сказать «не знаю» и предложить создать тикет через `create-ticket`. Точную формулировку промпта подберите сами.
6.  Проверка: вопрос из базы → в `output[]` видно `type: "file_search_call"` с результатами → ответ с цитатой и ссылкой. Вопрос вне базы → агент честно говорит «не знаю» и предлагает тикет.

### Подсказки

-   Если агент игнорирует базу знаний — усильте системный промпт: явно «перед ответом ВСЕГДА обращайся к базе знаний через `file_search` ».
-   Если агент цитирует документ целиком — добавьте в промпт «не цитируй больше 3 предложений, дай краткое резюме и ссылку».
-   Если топ-1 документ нерелевантен (низкий score) — лучше ответить «не знаю»; поднимите порог релевантности в настройках tool'а.

### Результат шага

-   Вы задаёте боту вопрос из базы знаний — получаете ответ со ссылкой на документ (видно `file_search_call` в трейсе).
-   Вы задаёте вопрос вне базы — бот честно говорит «не знаю» и предлагает создать тикет; в YDB появляется новая запись (`yc serverless function invoke ydb-tickets --data '{"action":"list-my-tickets",...}'`).
-   Вы можете показать в трейсе: какой документ был найден, какие `queries` агент сгенерировал.

### Ограничения Yandex Responses API для file\_search (важно!)

-   Только один `vector_store_id` за раз. Передача массива из нескольких IDs даёт `400 Bad Request: "FileSearch support only one vector store id"`.
-   Только один `file_search` tool за раз. Попытка добавить два инструмента file\_search в один запрос даёт `400 Bad Request: "Filesearch tool was given more than once"`.
-   Следствие: если у вас 3 независимых корпуса (например, `start`, `code-errors`, `payments`), их надо объединить в один search index при создании (`yandex-ai-studio vector-stores local start/*.md errors/*.md payments/*.md --name help-desk-unified`). Либо поднимать несколько ботов/агентов с разными индексами.
