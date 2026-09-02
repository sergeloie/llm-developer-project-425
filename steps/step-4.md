---
title: "Проект: AI-агент службы поддержки"
author: "Яндекс ID: пароли приложений"
source: "https://ru.hexlet.io/projects/425/members/53300?step=4"
site: ru.hexlet.io
saved: "2026-08-31T08:22:01.455Z"
---
## Базовый workflow по почте

Собираем минимальный рабочий цикл: сотрудник пишет на адрес `helpdesk@...` → письмо уходит в LLM → ответ возвращается отправщику. Пока без инструментов и без базы знаний — просто «выводим LLM в почтовый канал».

Поток данных (pull-архитектура, как и в TG-варианте):

Почему pull, а не push: webhook при входящем письме потребовал бы внешнего провайдера (Mailgun/Yandex 360 API), который сам доставкастает почту в YC. Pull-вариант сам забирает непрочитанное раз в минуту — без новой внешней зависимости.

Egress IMAP/SMTP из YC Cloud Functions работает — сырые TLS-сокеты (993/465) не блокируются. Это отличается от известного ограничения YC CF: исходящий POST к `api.telegram.org` режется. SMTP — это не HTTP, и блокировки для него нет.

### Ссылки

-   — для авторизации по IMAP/SMTP
-   [Миграция с Assistant API на Responses API](https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/agents/assistant-responses-migration.html) — документация Yandex AI Studio
-   [Таймер, который вызывает Cloud Function](https://yandex.cloud/ru/docs/functions/concepts/trigger/timer) — инструкция от Яндекс
-   [imaplib — IMAP4 protocol client](https://docs.python.org/3/library/imaplib.html) / [smtplib — SMTP protocol client](https://docs.python.org/3/library/smtplib.html) — стандартная библиотека Python (для других языков возьмите их IMAP/SMTP-эквиваленты)

### Задачи

1.  **Создайте app-password в Яндексе** (или другом провайдере). У Яндекса: `id.yandex.ru` → Безопасность → Создать пароль приложения → Почта. Этот пароль (=не пароль аккаунта) используется и для IMAP, и для SMTP.
    
2.  **Положите креды в Lockbox** — секрет `email-credentials` с ключом `password`. Для `IMAP_USER` / `SMTP_USER` / `HELPDESK_MAILBOX` / `OPERATOR_EMAIL` — обычные env vars, они не секрет.
    
3.  **Напишите Cloud Function `email-poller`** на выбранном вами языке; примеры ниже — для Python-варианта (*src/email\_poller.py*):
    
    -   Раз в минуту запускается по timer-триггеру.
    -   Подключается к IMAP (`imaplib.IMAP4_SSL(IMAP_HOST, 993)`), логинится, выбирает `INBOX`.
    -   Забирает непрочитанные письма: `imap.search(None, "UNSEEN")`.
    -   Для каждого письма: достаёт `From:` (`email.utils.parseaddr`), текст (`msg.get_body(preferencelist=("plain",))`), вызывает Responses API (`https://rest-assistant.api.cloud.yandex.net/v1/responses`).
    -   Отправляет ответ через SMTP (`smtplib.SMTP_SSL(SMTP_HOST, 465)` + `send_message`).
    -   Маркирует исходное письмо `\Seen` (`imap.store(num, "+FLAGS", "\\Seen")`) — иначе при следующем запуске оно снова попадёт в выборку.
4.  **Задеплойте функцию**. IAM-токен для Responses API берётся из metadata service (роль `ai.languageModels.user` у SA). Команда ниже — для Python-рантайма; под другой язык поменяйте `--runtime`, `--entrypoint` и `--source-path` (доступные рантаймы — в документации Cloud Functions):
    
    1
    
    2
    
    3
    
    4
    
    5
    
    6
    
    7
    
    8
    
    9
    
    10
    
    11
    
    ```
    yc serverless function version create \
    
      --function-name email-poller \
    
      --runtime python312 \
    
      --entrypoint email_poller.handle \
    
      --memory 256m \
    
      --execution-timeout 120s \
    
      --source-path src/email_poller.py \
    
      --service-account-id <SA_ID> \
    
      --environment YC_FOLDER_ID=<folder-id>,IMAP_HOST=imap.yandex.ru,IMAP_USER=helpdesk@yandex.ru,SMTP_HOST=smtp.yandex.ru,SMTP_PORT=465,SMTP_USER=helpdesk@yandex.ru,HELPDESK_MAILBOX=helpdesk@yandex.ru \
    
      --secret environment-variable=IMAP_PASSWORD,name=email-credentials,key=password \
    
      --secret environment-variable=SMTP_PASSWORD,name=email-credentials,key=password
    ```
    
5.  **Создайте timer-триггер**:
    
    1
    
    2
    
    3
    
    4
    
    5
    
    6
    
    ```
    yc serverless trigger create timer \
    
      --name email-poller-trigger \
    
      --cron-expression "0/1 * * * ? *" \
    
      --invoke-function-name email-poller \
    
      --invoke-function-tag '$latest' \
    
      --invoke-function-service-account-id <SA_ID>
    ```
    
    Cron-формат — AWS EventBridge (`0/1 * * * ? *` = каждую минуту).
    
6.  **Ручной тест**: отправьте письмо на `helpdesk@yandex.ru` с текстом «привет», дождитесь ответного письма (в пределах одного цикла таймера — до 60 секунд), или `yc serverless function invoke email-poller` — должно вернуть `{"processed": N, ...}`.
    

### Подсказки

-   `<SA_ID>` — сервисный аккаунт `ai-studio-sa`, созданный на шаге про настройку AI Studio. Для timer-триггера подойдёт тот же `ai-studio-sa` — роль `functions.functionInvoker` у него уже есть. Не забудьте выдать ему `lockbox.payloadViewer` на секрет `email-credentials`.
-   Каждое обработанное письмо обязательно маркируйте `\Seen`. Альтернатива (надёжнее при ошибках в середине цикла) — сохранять последний UID в YDB, но для MVP достаточно флага.
-   Если письмо multipart — `msg.get_body(preferencelist=("plain",))` выбирает text/plain часть. Для писем только в HTML нужен fallback на `html.parser` или `beautifulsoup` (последний — внешний пакет).
-   У Яндекса IMAP и SMTP используют одни и те же user + app-password. Поэтому `--secret` для `IMAP_PASSWORD` и `SMTP_PASSWORD` ссылается на один и тот же секрет `email-credentials` с ключом `password`.
-   При ошибке обработки конкретного письма (например, агент недоступен) всё равно маркируйте его `\Seen` — иначе poller зациклится на одном проблемном письме и не дойдёт до остальных. Шаблон: try/except + `_imap_mark_seen` в ветке except.

### Результат шага

-   Вы пишете письмо на `helpdesk@yandex.ru` с темой «Тест» и текстом «привет» — в течение минуты (один цикл таймера) приходит ответ от YandexGPT с темой `Re: Тест`.
-   В логах CF `email-poller` видно цепочку: `GOT_UNSEEN=1` → `MSG num=... from=... subject=...` → `AGENT_OK len=...` → `SEND_OK to=...`.
-   Исходное письмо в ящике помечено как прочитанное.

---

## Приложение: Вариант Telegram

Если предпочитаете Telegram — соберите `telegram-poller` по той же pull-схеме (CF раз в минуту забирает `getUpdates` и шлёт ответ через `sendMessage` GET). Идея та же, что у почтового поллера, — меняется только транспорт.

Отличия от почтового варианта:

-   Telegram требует создания бота через `@BotFather`, кред — один токен (`TELEGRAM_BOT_TOKEN`).
-   Идемпотентность — сдвигом `offset` в `getUpdates?offset=<update_id+1>`, а не флагом `\Seen`.
-   Известная особенность YC CF: исходящий POST к `api.telegram.org` блокируется, поэтому `sendMessage` переписан на GET с query params. IMAP/SMTP такой проблемы нет.
-   `user_id` в YDB — это числовой chat id (не email).

Переменные окружения для TG-варианта — в закомментированном блоке *code/.env.example*.

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