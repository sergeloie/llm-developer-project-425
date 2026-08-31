# 01: Parent POM + common модуль

**What to build:** Вынести общий код в `common` и завести `parent pom` как BOM, чтобы все Cloud Function модули собирались одной командой и не дублировали `EmailSender`/`PiiMasker`. После тикета `mvn -pl common test` зелёный и хак `sourceDirectory` удалён.

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] Создан `pom.xml` в корне (`groupId ru.anseranser`, `artifactId helpdesk-parent`, `version 1.0.0`, `packaging pom`, `modules [common, email-poller, ydb-tickets, email-sender]`), `dependencyManagement`/`pluginManagement` фиксируют `yc-sdk 2.14.0`, `ydb 2.4.9`, `angus-mail 2.0.5`, `gson 2.11`, `junit 5.11`, `mockito 5.14`, `maven-shade 3.6.0`, `maven-compiler 3.13.0` `release 21`
- [ ] Создан модуль `common` (`artifactId common`, `version 1.0.0`, `jar` без shade) с классами `ru.anseranser.pii.PiiMasker` (из `step8/PiiMasker.java`), `ru.anseranser.security.InjectionClassifier` (из `step8/InjectionClassifier.java`), `ru.anseranser.ydb.YdbTransportFactory`, `ru.anseranser.mail.SmtpEmailSender` (дедуп `helpdesk/mail/EmailSender.java` + `step6/mailsender/EmailSender.java`), `ru.anseranser.json.JsonEventParser` (логика `parseInput`/`detectAction` из `ydb2/Handler.java`)
- [ ] `common` покрыт unit-тестами: перенесён `step8/SecurityTest.java` + новые тесты `YdbTransportFactory`/`JsonEventParser`/`SmtpEmailSender`, `mvn -pl common test` проходит
- [ ] Исправлен `.gitignore`: убран `*.md`/`*.json` игнор ломающий `infra/`/`step7/docs`, добавлено `!README.md` корректно, `target/`/`*.iml` остаётся, удалён хак `<sourceDirectory>${project.basedir}</sourceDirectory>` из всех `pom.xml`
- [ ] `mvn clean verify -pl common -am` зелёный, `common` ставится в `localRepo` для зависимых модулей
