# 04: email-sender — выделение из step6

**What to build:** Выделить `step6/mailsender/*` в изолированный `email-sender` модуль, чтобы `YaWL httpCall` мог слать дайджест через `EmailSenderFunction` на `OPERATOR_EMAIL` из env, а `body` массив/объект сериализовался в `pretty JSON`.

**Blocked by:** 01 Parent POM + common модуль

**Status:** done

- [ ] Модуль `email-sender` (`artifactId email-sender`, `version 1.0.0`, `dependencies: common`, `angus-mail 2.0.5`, `gson 2.11`, `java-sdk-serverless`) создан из `step6/src/main/java/ru/anseranser/mailsender/*`, использует `common.SmtpEmailSender`, `EmailSender.java` дубль удалён из `step6`
- [ ] `EmailSenderFunction.handle` парсит `JsonObject` (`subject`, `body`), `getStringField` сериализует массивы/объекты через `Gson prettyPrinting`, `to=HELPDESK_MAILBOX`/`OPERATOR_EMAIL` из `env`, дефолты `subject=No Subject`, `body=""`, `500` если `HELPDESK_MAILBOX` blank, `502` при `MessagingException`, `400` при невалидном JSON
- [ ] `maven-shade-plugin` (`mainClass ru.anseranser.mailsender.EmailSenderFunction`, `ServicesResourceTransformer`), `mvn -pl common,email-sender -am package -DskipTests` даёт `email-sender/target/email-sender-1.0.0.jar`
- [ ] Тесты: перенесён `step6/src/test/java/ru/anseranser/mailsender/EmailSenderFunctionTest.java:14` (valid, missing mailbox, missing subject/body, smtp error, array/object body, invalid json), `mvn -pl email-sender test` зелёный
