# Сценарий демонстрации JSON Schema Introspection

Перед запуском демонстрационного стенда выполните необходимые предварительные шаги:
- скомпилировать Java-плагин `shadowJar` (fat-jar) со всеми внутренними зависимостями проекта;
- сформировать набор синтетических JSON Lines данных;
- развернуть локальный in-memory кластер с плагином и зависимостями.

Демонстрация включает в себя:
- подключение к кластеру через trino-cli
- запуск задачи на интроспекцию
- сохранение локально результата интроспекции в виде кастомной схемы данных

Формат отчёта — rx-data 3.0 ([контракт](../contracts/rx-data-contract.md), эталонный пример в разделе 4; ожидания по
таблицам стенда — [fixture-matrix.md](../testing/fixture-matrix.md)). Фрагмент отчёта таблицы `crm_combined`:

```json
{
  "schema_version": "3.0",
  "$._id.$oid": {
    "type": "VARCHAR",
    "max_length": 24
  },
  "$.birth_date": {
    "type": "VARCHAR",
    "max_length": 10,
    "observed_formats": ["DATE_ONLY"],
    "anomalies": {
      "is_polymorphic_structure": { "detected": true }
    }
  },
  "$.birth_date[*]": {
    "type": "VARCHAR",
    "max_length": 4,
    "anomalies": {
      "is_date_part_array": { "detected": true },
      "is_flat_string_array": { "detected": true }
    }
  },
  "$.counterparties": {
    "type": "OBJECT",
    "max_length": 0,
    "anomalies": {
      "is_polymorphic_structure": { "detected": true }
    }
  },
  "$.counterparties.name": {
    "type": "VARCHAR",
    "max_length": 4
  },
  "$.counterparties[*]": {
    "type": "ARRAY",
    "max_length": 0
  },
  "$.counterparties[*].share": {
    "type": "INTEGER",
    "max_length": 0
  },
  "$.created_at": {
    "type": "VARCHAR",
    "max_length": 25,
    "observed_formats": ["DATE_ONLY", "LOCAL_DATETIME", "OFFSET_DATETIME", "UNIX_MILLIS"],
    "anomalies": {
      "is_polymorphic_format": { "detected": true }
    }
  },
  "$.customer_rating": {
    "type": "DOUBLE",
    "max_length": 0
  },
  "$.metadata_encoded": {
    "type": "VARCHAR",
    "max_length": 43,
    "anomalies": {
      "is_json_string": { "detected": true }
    }
  },
  "$.metadata_encoded.user_agent": {
    "type": "VARCHAR",
    "max_length": 7
  },
  "$.metadata_encoded.retry_count": {
    "type": "INTEGER",
    "max_length": 0
  },
  "$.payment_dates[*]": {
    "type": "VARCHAR",
    "max_length": 10,
    "observed_formats": ["DATE_ONLY"],
    "anomalies": {
      "is_array_empty": { "detected": true },
      "is_flat_string_array": { "detected": true }
    }
  }
}
```

Чистые объекты (`$._id`, `$.version`, `$.doc_meta`) собственных записей не имеют: в отчёте только их листья. Запись
`$.counterparties` есть, потому что поле неоднородно (в одних документах объект, в других массив объектов).
