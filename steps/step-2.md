---
title: "Проект: AI-агент службы поддержки"
source: "https://ru.hexlet.io/projects/425/members/53300?step=2"
site: ru.hexlet.io
saved: "2026-08-31T08:21:34.136Z"
---
## Подготовка окружения

Поднимаем рабочее окружение: репозиторий проекта, утилиты командной строки, облачный каталог и serverless-базу YDB. После этого шага у вас есть чистая площадка, на которой дальше разворачиваются все компоненты.

В проекте используется Yandex Cloud — поэтому кроме git и редактора нам понадобится `yc` (Yandex Cloud CLI) и доступ к консоли. Всё, что мы деплоим дальше (Cloud Functions, Workflows, MCP-шлюзы), создаётся через `yc` либо через UI AI Studio. Обращения пользователей и история диалогов хранятся в YDB Serverless — это дешевле на старте (платим за запросы, не за простой).

### Ссылки

-   [Инструкция по установке yc](https://yandex.cloud/ru/docs/cli/quickstart)
-   [Yandex Managed Service for YDB](https://yandex.cloud/ru/docs/ydb/) — описание сервиса в Yandex Cloud

### Задачи

1.  Склонируйте созданный на GitHub репозиторий себе локально.
    
2.  Установите утилиту `yc`.
    
3.  Инициализируйте профиль: `yc init` → выберите облако и каталог (или создайте новый).
    
4.  Выберите язык решения — Cloud Functions поддерживают несколько рантаймов; примеры во всех шагах даны для Python 3.12. Независимо от выбранного языка установите Python: он нужен для CLI `yandex-ai-studio` из пакета `yandex-ai-studio-sdk` (шаг про RAG). Для Python-варианта пригодится и пакет `ydb` (инициализация схемы — шаг про MCP и YDB). Ставьте в удобное вам окружение (venv, uv, conda — на ваш выбор).
    
5.  Создайте базу YDB Serverless в каталоге:
    
    1
    
    ```
    yc ydb database create help-desk-db --serverless
    ```
    
    Создание идёт асинхронно (~40 секунд). Дождитесь статуса `RUNNING`: `yc ydb database get help-desk-db --format json | jq -r .status` (значения: `PROVISIONING` → `RUNNING`).
    
6.  Получите и запишите значения в *.env* (реальный файл, он в *.gitignore*):
    
    1
    
    2
    
    3
    
    4
    
    5
    
    ```
    ENDPOINT=$(yc ydb database get help-desk-db --format json | jq -r .endpoint)
    
    YDB_ENDPOINT=$(echo "$ENDPOINT" | sed -E 's|(grpcs://[^/?]+).*|\1|')
    
    YDB_DATABASE=$(echo "$ENDPOINT" | sed -E 's|.*database=([^&]+).*|\1|')
    
    echo "YDB_ENDPOINT=$YDB_ENDPOINT"
    
    echo "YDB_DATABASE=$YDB_DATABASE"
    ```
    
    У `yc ydb database get` нет отдельного поля `.database` — путь зашит в `endpoint` как query-параметр `?database=/ru-central1/...`. Поэтому парсим sed'ом. Поле `.name` возвращает короткое имя `help-desk-db`, для SDK не подходит. Дальше значения перенесем в Lockbox.
    
7.  Создайте файл *.env.example* в репозитории — те же переменные без значений (шаблон для проверяющего). Реальные значения держите в *.env*, который добавлен в *.gitignore*.
    

### Результат шага

-   В консоли `yc config list` показывает `cloud-id` и `folder-id`.
-   В репозитории есть *README.md*, *.env.example*, *.gitignore* (включая *.env*).
-   `yc serverless function list` не падает с ошибкой авторизации.
-   `yc ydb database get help-desk-db` возвращает статус базы `RUNNING` (не `HEALTHY`); `YDB_ENDPOINT` и `YDB_DATABASE` записаны в *.env*.

### Подсказки

-   Узнать `folder-id` можно с помощью CLI:
    
    1
    
    ```
    yc resource-manager folder list
    ```
    
-   Если `yc ydb database create` падает с `quota exceeded` — в каталоге лимит на число баз: удалите старые (`yc ydb database delete`) или запросите увеличение квоты в консоли.
    

Завершено

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