# Сценарий демонстрации JSON Schema Introspection

Перед запуском демонстрационного стенда выполните необходимые предварительные шаги:
- скомпилировать Java-плагин `shadowJar` (fat-jar) со всеми внутренними зависимостями проекта;
- сформировать набор синтетических JSON Lines данных;
- развернуть локальный in-memory кластер с плагином и зависимостями.

Демонстрация включает в себя:
- подключение к кластеру через trino-cli
- запуск задачи на интроспекцию
- сохранение локально результата интроспекции в виде кастомной схемы данных

```json
{
  "$.payment_dates[*]": {
    "type": "VARCHAR",
    "max_length": 10,
    "is_array_empty": true,
    "is_flat_string_array": true
  },
  "$.customer_rating": {
    "type": "DOUBLE",
    "max_length": 0
  },
  "$.metadata_encoded.user_agent": {
    "type": "VARCHAR",
    "max_length": 7
  },
  "$.metadata_encoded.retry_count": {
    "type": "INTEGER",
    "max_length": 0
  },
  "$.created_at": {
    "type": "VARCHAR",
    "max_length": 19
  },
  "$.birth_date": {
    "type": "VARCHAR",
    "max_length": 10
  },
  "$.updated_at": {
    "type": "VARCHAR",
    "max_length": 25
  },
  "$._id.$oid": {
    "type": "VARCHAR",
    "max_length": 24
  },
  "$.version.$numberLong": {
    "type": "INTEGER",
    "max_length": 0
  },
  "$.doc_meta.@type": {
    "type": "VARCHAR",
    "max_length": 4
  },
  "$.birth_date[*]": {
    "type": "VARCHAR",
    "max_length": 4,
    "is_date_part_array": true,
    "is_flat_string_array": true
  },
  "$.promo_expiry_date": {
    "type": "VARCHAR",
    "max_length": 10
  },
  "$.doc_meta.@version": {
    "type": "INTEGER",
    "max_length": 0
  }
}
```
