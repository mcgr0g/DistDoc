# Контракт rx-data (.rx.json)

> Общий контракт плагина DistDoc (Java) и генератора dbt-моделей (Python).
> Копируется в репозиторий генератора при его выделении. Канонический источник —
> этот файл и rx-data.schema.json; документация генератора ссылается на них,
> не дублируя формат.
>
> Машиночитаемая форма контракта: [rx-data.schema.json](./rx-data.schema.json)
> (JSON Schema Draft 2020-12) — канонический формат `.rx.json` плагина версии 2.0
> (`type` / `max_length` / `observed_formats` / `anomalies` / `path_trace`).
> Написания дат (`observed_formats`) — [value-formats.md](./value-formats.md).

## 1. Формат и именование
- `schema_version` — корневой строковый ключ rx-data, версия схемы major.minor
  (сейчас `"2.0"`); обязателен во всех режимах.
- rx-data — текстовый JSON, пути записаны в лексикографическом порядке.
- Имя файла генератора — `<MODEL_PREFIX>__<ИМЯ_БД>__<ТАБЛИЦА>.rx.json`
  (пример: `stg__deal_online_v3__deal.rx.json`); это соглашение генератора
  (см. документацию генератора), плагин имя файла не фиксирует — в local dev имя
  отчёта задаёт переменная `LC_REPORT` (`mise.toml`, по умолчанию `combined.rx.json`).
  Кэш-данные `.jsonl` существуют только
  в режиме local dev (`mise run lc-gen`, каталог `build/dev-lakehouse/data`); в серверном
  режиме плагин ничего на диск не пишет.
- Структура записи:
  `{ "<jsonpath>": { "type": "<тип>", "max_length": <N>, "observed_formats"?, "anomalies"?, "path_trace"? } }`.
  Других ключей у пути нет.
- `<jsonpath>` — абсолютный путь от корня документа: `$.клиент.поле`, `$.массив[*].поле`.
  Служебные ключи: `$oid`/`$date`/`$numberLong` (BSON), `@type`/`@version` — в исходном виде.
- `observed_formats` — необязательный массив написаний дат, доказанных хотя бы у одного
  скалярного значения пути (раздел 2a). Ключ отсутствует, если форматов нет.
- `anomalies` — необязательный объект, единственный контейнер аномалий пути:
  `{"<имя>": {"detected": true, "trace"?: [...]}}`. Имя — `is_*`/`has_*` (раздел 3).
  Аномалия монотонна: после фиксации не сбрасывается, `detected: false` в отчёт не пишется.
  Ключ отсутствует, если аномалий нет.
- `path_trace` и `anomalies[name].trace` — необязательные массивы атомарных пар
  `{"id": "...", "id_key": "..."}`, присутствуют только в trace-режиме UDAF:
  `path_trace` — документы, где путь встретился впервые; `anomalies[name].trace` —
  документы, где впервые зафиксирована именно эта аномалия. `id_key` — имя
  поля-источника id (пресет/суффикс/explicit); пара `{"id": "", "id_key": ""}` —
  маркер «источник не найден». Лимит — `max_ids` пар на scope
  (`src/main/resources/app-config.toml`). У `is_polymorphic_format` элементы trace
  дополнительно несут `format` (какое написание дал документ), лимит — на каждый формат.
  Правила сбора и слияния — [docs/testing/tracing.md](../testing/tracing.md).
  Генератор dbt trace-поля игнорирует (не влияют на типы и аномалии).
- SQL-синтаксис: `analyze_json_schema(line)` — обычный режим (без trace-полей);
  `analyze_json_schema(line, trace('имя_поля'))` — явная трассировка по имени поля;
  `analyze_json_schema(line, trace())` — пресет: поиск id по ранжированному пресету
  и суффикс-правилу (`_id`).

### Версия схемы (правила нумерации)

- **major** — ломающее изменение схемы: удаление или переименование ключа, смена типа
  или формы значения, изменение семантики существующего формата значения.
- **minor** — аддитивное изменение: новый необязательный ключ, новое имя аномалии,
  новый формат значения.
- **patch** — изменения кода без изменения схемы.
- В rx-data входит только major.minor: patch контракт не меняет и потребителю
  неразличим.
- Генератор dbt сверяет major.minor на совместимость.
- Источник значения единственный: `gradle.properties` (`distdocVersion`, полная X.Y.Z);
  полная версия транспортируется в сборку через `app-config.toml` (ключ
  `app.distdoc_version`), сокращение до major.minor выполняет класс `CoreSettings`.

### Переход 1.1 → 2.0 (major)

2.0 — ломающее изменение без compatibility layer (генератор dbt на момент перехода
не реализован):

| 1.1 | 2.0 |
|---|---|
| плоские флаги `"is_*": true` на пути | `anomalies["is_*"] = {"detected": true}` |
| `trace_ids` (общий на путь: появление + все аномалии) | `path_trace` и `anomalies[name].trace` — независимые scopes |
| `trace_id_key` (один на путь, merge отдельно от id) | `id_key` внутри каждой пары `{id, id_key}` |
| — | `observed_formats`, аномалия `is_polymorphic_format` |
| порядок путей не гарантирован | лексикографический |

Потребитель 1.1 на отчёте 2.0 **теряет семантику аномалий**: плоских `is_*` больше
нет, чтение флага по старому ключу даёт «аномалии нет». Поэтому генератор обязан
проверять major `schema_version` и отвергать несовместимый отчёт.

## Детерминизм

Полный детерминизм набора trace-id недоступен в силу распределённой обработки: строки
расходятся по воркерам Trino по предикатам, порядок обхода строк не гарантирован.
Плагин нормирует результат правилом merge-детерминизма:

1. Запрос разбивается на воркеры по предикатам; воркер, получивший данные,
   интроспектирует каждую строку; новые jsonpath-узлы дописываются в rx-data.
2. Обычный отчёт (`type`, `max_length`, `observed_formats`, имена `anomalies`) не
   зависит ни от порядка строк, ни от разбиения на воркеры: множества форматов и
   аномалий монотонно растут, merge — union; `is_polymorphic_format` вычисляется из
   множества форматов, поэтому возникает и тогда, когда форматы видели разные воркеры.
3. По каждой строке (в trace-режиме) ищется пара `{id, id_key}`: пресет — первое
   совпавшее поле по рангу; иначе суффикс-фолбэк; иначе маркер `{"", ""}`. Explicit:
   значение поля `trace('имя_поля')`, при отсутствии поля — маркер. Пара кладётся в
   каждый scope, который строка затронула впервые (новый путь, новая аномалия пути,
   новый формат пути).
4. Scope хранит `max_ids` лексикографически наименьших пар по `(id, id_key)`; маркер —
   не более одного, в хвосте, при свободном слоте. Одно правило для строки и для
   слияния воркеров: merge ассоциативен, коммутативен, идемпотентен, пара не
   разрывается. Какие именно id попадут в scope, зависит от разбиения на сплиты
   (scope видит первое появление факта на своём воркере) — подробности в
   [docs/testing/tracing.md](../testing/tracing.md).

## 2. Правила определения типов (Type Promotion)
- `type` — итоговый физический тип пути, сведённый по правилу Type Promotion
  (enum: UNKNOWN, VARCHAR, INTEGER, DOUBLE, ARRAY, BOOLEAN).
- Единственный наблюдаемый тип остаётся собой.
- Числовое смешение {INTEGER, DOUBLE} → DOUBLE (расширение без потери данных);
  аномалией формата не является.
- Любое смешение с VARCHAR/BOOLEAN/ARRAY → VARCHAR.
- Запрещено менять регистр ключей и приводить к snake_case на этапе интроспекции.
- jsonstring: строковый скаляр, парсящийся как валидный JSON-объект/массив,
  деэкранируется, обход продолжается рекурсивно внутрь; отдельный флаг в rx-data
  не пишется.

## 2a. Форматы значений (`observed_formats`)

Словарь `ValueFormat`, правила распознавания строк и чисел, path hints, путь формата
(scalar-leaf) и таблица примеров — [value-formats.md](./value-formats.md). Для
потребителя контракта существенно:

- значения — элементы закрытого словаря (`DATE_ONLY`, `LOCAL_DATETIME`,
  `OFFSET_DATETIME`, `UTC_DATETIME`, `UNIX_SECONDS`, `UNIX_MILLIS`); порядок в массиве —
  порядок словаря и одновременно приоритет SQL-приведения генератора;
- формат фиксирует **написание**, а не момент времени (`+00:00` ≠ `Z`);
- только positive evidence: формат доказан хотя бы для одного значения пути, но не
  гарантирован для каждого — генератор всегда применяет `TRY_CAST`/fallback;
- формат лежит на пути доказавшего скаляра (`$.created_at.$date.$numberLong`, а не
  `$.created_at`); элементы массива — на `$.x[*]`;
- ≥ 2 форматов на пути — аномалия `is_polymorphic_format`.

## 3. Маркеры аномалий (соответствие аномалиям плагина)
rx-data хранит аномалию плагина ключом в `anomalies` (вторая колонка); генератор dbt
отображает её в собственный маркер модели (первая колонка).

| Маркер dbt-модели | Ключ в `anomalies` (docs/guides/demo.md) | Смысл |
|---|---|---|
| dateasarray | is_date_part_array | массив строковых частиц даты (3–6 элементов, ключ с временным слогом date/time/dt/created/update/issued/valid) — подавить генерацию unnest-моделей |
| stringsasarray | is_flat_string_array | плоский массив строковых/числовых литералов — разворачивать как varchar |
| emptyarray | is_array_empty | массив пуст на всей выборке — запрет дочерних unnest-таблиц |
| polyformat | is_polymorphic_format | на пути ≥ 2 написаний даты (`observed_formats`) — приведение цепочкой `TRY_CAST` по форматам |
| jsonstring | (экранированная строка) | внутри строки сериализованный JSON — обход продолжается внутрь |

## 4. Эталонный rx-data

Обычный режим (фрагмент отчёта `crm_combined`, ожидания — docs/testing/fixture-matrix.md, раздел 4):

```json
{
  "schema_version": "2.0",
  "$.birth_date[*]": {
    "type": "VARCHAR",
    "max_length": 4,
    "anomalies": {
      "is_date_part_array": { "detected": true },
      "is_flat_string_array": { "detected": true }
    }
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
  "$.doc_meta.@type": {
    "type": "VARCHAR",
    "max_length": 4
  },
  "$.payment_dates[*]": {
    "type": "VARCHAR",
    "max_length": 10,
    "observed_formats": ["DATE_ONLY"],
    "anomalies": {
      "is_array_empty": { "detected": true },
      "is_flat_string_array": { "detected": true }
    }
  },
  "$.version.$numberLong": {
    "type": "INTEGER",
    "max_length": 0
  }
}
```

Trace-режим (фрагмент одного пути):

```json
"$.created_at": {
  "type": "VARCHAR",
  "max_length": 25,
  "observed_formats": ["DATE_ONLY", "LOCAL_DATETIME"],
  "anomalies": {
    "is_polymorphic_format": {
      "detected": true,
      "trace": [
        { "id": "60b8d29f1a4c8b0000000001", "id_key": "_id.$oid", "format": "DATE_ONLY" },
        { "id": "60b8d29f1a4c8b0000000000", "id_key": "_id.$oid", "format": "LOCAL_DATETIME" }
      ]
    }
  },
  "path_trace": [
    { "id": "60b8d29f1a4c8b0000000000", "id_key": "_id.$oid" }
  ]
}
```
