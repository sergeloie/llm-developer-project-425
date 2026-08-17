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
