# Спецификация сценариев данных и фикстур (Chaos Fixtures)

Документ описывает контракт данных, структуру полей и реестр аномалий, используемых для тестирования и отладки UDAF-плагина.

### ⚠️ Важное примечание для LLM / Контекста автоматизации:
В проекте реализовано четкое разделение между быстрыми изолированными тестами кода и сквозным локальным стендом Trino:

#### 1. Сквозной стенд разработки (`fixtures.toml` → `lc-gen` → `lc-load`)
Генератор (`ChaosGeneratorApp`) сначала физически записывает файлы «хаоса» на жесткий диск ПК (`.jsonl`), а затем автономный загрузчик (`JdbcChaosLoaderApp`) считывает их с диска, упаковывает в JSON-массив и транслирует по JDBC в оперативную память Trino.

**Запуск**: `mise run lc-demo` (Docker Compose + генерация + загрузка + анализ схемы)

#### 2. Unit-тесты (`src/test/`)
Работают полностью изолированно в памяти JVM. Тест напрямую вызывает метод `ChaosDataGenerator.generateSingleLine()` и сразу передает полученную строку в парсер плагина, минуя стадию записи на диск и сетевые раунд-трипы до контейнера Trino.

**Запуск**: `./gradlew test` (~1-2 сек)

#### 3. E2E-тесты (`src/e2e/`)
**Используют in-memory генерацию**, а не файловый путь через `lc-gen → lc-load`. Причины:

- **Скорость**: ~8-10 сек (in-memory) vs ~60 сек (Docker + JDBC load)
- **Изоляция**: нет зависимости от Docker Compose, сетевого стека, файловой системы
- **Детерминизм**: seed-based генерация `ChaosDataGenerator.generateSingleLine(src, scenario, seed)` — воспроизводимость без внешних файлов

**Реализация**:
```java
// src/e2e/java/.../DistDocDistributedE2ETest.java
@Override
protected QueryRunner createQueryRunner() throws Exception {
    // ...
    StringBuilder sb = new StringBuilder("INSERT INTO big VALUES ");
    ForgottenMigrationsSource src = new ForgottenMigrationsSource();
    for (int i = 0; i < 500; i++) {
        if (i > 0) sb.append(',');
        String line = ChaosDataGenerator.generateSingleLine(src, AnomalyScenario.ALL, i);
        sb.append('(').append(quote(line)).append(')');
    }
    runner.execute("CREATE TABLE big(line varchar)");
    runner.execute(sb.toString());
    return runner;
}
```

**Что проверяется**:
- `DistDocQueryE2ETest`: 100 строк (заменяет `mise run lc-schema`)
- `DistDocTraceE2ETest`: trace-перегрузки (заменяет `mise run lc-trace`)
- `DistDocDistributedE2ETest`: 500 строк `all` и 400 строк `trace_mixed_sources`, реальный шаффл
  `SchemaState` между нодами; отчёт сверяется с одноузловым, trace — по инвариантам пар

Состав и режимы таблиц `fixtures.toml` проверяет unit `FixtureMatrixTest` (генерирует каждую
таблицу тем же `index 0…count-1`, что `lc-gen`, и сверяет с fixture-matrix.md).

**Что НЕ проверяется**:
- Запись/чтение `.jsonl` и JDBC-загрузка `lc-gen → lc-load` (golden-check `crm_combined` через `mise run lc-verify`)
- Загрузка через JDBC в настоящий Docker-контейнер (только `installSmokeTest` через Testcontainers)

**Запуск**: `./gradlew e2eTest` или `./gradlew check` (unit + e2e)

---

**Итого**:
- **Обязательная верификация (CI)**: `./gradlew check` (unit + e2e in-memory) + `installSmokeTest` (Testcontainers)
- **Интерактивная отладка (dev)**: `mise run lc-demo` (Docker + файловый путь + trino-cli)

Подробности: [docs/testing/reliability-matrix.md](reliability-matrix.md), [ADR-0005](../adr/0005-e2e-sourceSet.md)

## 1. Файл конфигурации
Располагается по пути `src/testFixtures/resources/fixtures.toml`: одна запись `[[scenarios]]` на таблицу.
Состав таблиц и ожидание каждой — [fixture-matrix.md](fixture-matrix.md) (префикс имени = ось проверки:
`fmt_`, `arr_`, `pol_`, `trace_`). Типовой фрагмент:
```toml
[[scenarios]]
# Все группы вместе, детерминированно по index (fixture-matrix §4, golden-check lc-verify)
table = "crm_combined"
count = 100
type = "all"
file = "build/dev-lakehouse/data/crm_combined.jsonl"

[[scenarios]]
# created_at = 1784769300000 → INTEGER, UNIX_MILLIS
table = "fmt_created_at_unix_millis"
count = 50
type = "date_at_unix"
file = "build/dev-lakehouse/data/fmt_created_at_unix_millis.jsonl"

[[scenarios]]
# created_at по i%3: local / date-only / offset → is_polymorphic_format
table = "pol_created_at_formats"
count = 60
type = "pol_created_at_formats"
file = "build/dev-lakehouse/data/pol_created_at_formats.jsonl"
```

Правила:
- `type` — константа `AnomalyScenario` в нижнем регистре; `file` — по имени таблицы;
- группы строк внутри режима выбираются по `index` (`i%N`), не случайно — отчёт воспроизводим;
- `FixtureMatrixTest` читает этот же файл: новая или переименованная таблица без строки в
  матрице и без ожиданий в тесте роняет `./gradlew test`.

Все сценарии генерации описываются декларативно в TOML-формате. 
Пути выгрузки изолированы в директории `build/dev-lakehouse/data/` для корректной работы инкрементального кэша сборщиков.

## 2. Целевой JSON-контракт («Монстр CRM»)

Каждая строка файла представляет собой плоский JSON-объект (формат JSON Lines), 
эмулирующий выгрузку документов BSON из MongoDB. Эталонная структура в режиме `all` / `date_as_array` подвергается контролируемым деформациям.

### Пример эталонной хаос-строки:
(Строка 1 — чистая; строка 2 — мутации режима all.)
```jsonlines
{"_id": {"$oid": "60b8d29f1a4c8b0000000001"}, "customer_rating": 4, "payment_dates": ["2026-06-01", "2026-06-02"], "birth_date": "1990-05-15", "created_at": "2026-07-23T01:15:00", "updated_at": "2026-07-23T01:15:00+03:00", "promo_expiry_date": "2026-08-01", "metadata_encoded": "{\"user_agent\": \"Mozilla\", \"retry_count\": 1}", "version": {"$numberLong": 7}, "doc_meta": {"@type": "deal", "@version": 2}}
{"_id": {"$oid": "60b8d29f1a4c8b0000000002"}, "customer_rating": 4.5, "payment_dates": [], "birth_date": ["1990", "05", "15"], "created_at": "2026-07-23", "updated_at": "2026-07-23T01:15:00+03:00", "promo_expiry_date": "2026-08-01", "metadata_encoded": "{\"user_agent\": \"Mozilla\", \"retry_count\": 1}", "version": {"$numberLong": 7}, "doc_meta": {"@type": "deal", "@version": 2}}
```

### Спецификация полей и их назначение:

1.  **`_id.$oid` (VARCHAR)**: вложенный BSON-идентификатор документа MongoDB вместо плоского `tx_id`; отлаживает рекурсивный парсер путей верхнего уровня (`$._id.$oid`).
2.  **`customer_rating` (INTEGER → DOUBLE)**: в чистых сценариях INTEGER (`numberBetween(1,5)`); DOUBLE подмешивают `rating_promotion` и `pol_created_at_with_rating` (нечётные строки) и `all` (`i%5<2`) — числовая решётка ядра поднимает итог до DOUBLE (без потери данных, заметно в git diff); ложных аномалий в схеме не порождает.
3.  **`payment_dates` (ARRAY)**: Честный массив оплат. Используется строго для детекции пустых массивов (`[]`) или валидных списков ISO-дат `["2026-06-01", "2026-06-02"]`.
4.  **`birth_date` (VARCHAR / ARRAY)**: Поле-хамелеон со структурным хаосом.
    *   В режиме `clean`: обычная плоская строка `"1990-05-15"`.
    *   В режиме `date_as_array` (и в нечётных строках `pol_created_at_with_arrays` и `all`): взрывается в массив текстовых частиц `["1990", "05", "15"]`. Позволяет отлаживать dbt-шаблонизаторы на построение условных `IF-THEN-ELSE` конструкций с проверкой `typeOf`.
5.  **`created_at` (TIMESTAMP)**: Дата-время регистрации сущности на бэкенде. Особенность продуктового кода — пишется в локальном ISO-формате **без таймзоны** (`2026-07-23T01:15:00`). Помогает дата-аналитикам подобрать корректный метод парсинга в Trino/dbt. В изолированном сценарии `date_at_unix` поле хранит Unix-миллисекунды (INTEGER, `1784769300000` — 2026-07-23T01:15:00Z). В изолированном сценарии `date_as_plain` поле хранит обычную дату `"2026-07-23"` (YYYY-MM-DD). Режимы `pol_*` чередуют три написания по `i%3` (local / date-only / `+03:00`), режим `all` — четыре по `i%4` (добавляются Unix-миллисекунды).
6.  **`updated_at` (TIMESTAMP WITH TIME ZONE)**: Дата-время обновления **с явным указанием таймзоны/смещения UTC** (`2026-07-23T01:15:00+03:00`). Не является аномалией, используется для проверки корректности приведения специфичных типов данных внутри Trino.
7.  **`promo_expiry_date` (VARCHAR)**: Эталонная чистая дата в формате `"YYYY-MM-DD"` (`"2026-08-01"`). Всегда остается строкой, создана для отладки «счастливого пути» (happy path) генератора dbt-шаблонов без усложненной логики.
8.  **`metadata_encoded` (VARCHAR, jsonstring)**: экранированный под-документ (CDC-кейс); парсер деэкранирует строку и продолжает обход внутрь — пути `$.metadata_encoded.user_agent` (VARCHAR), `$.metadata_encoded.retry_count` (INTEGER).
9.  **`version.$numberLong` (INTEGER)**: BSON-служебный слог `$numberLong` сохраняется в пути в исходном виде.
10. **`doc_meta.@type` / `doc_meta.@version` (VARCHAR / INTEGER)**: словесные слоги с `@` сохраняются в пути в исходном виде.
11. **`surrogate_pk` (VARCHAR, только в `all`)**: суррогатный ключ уровня 1, присутствует
    в чётных строках режима `all`. Служит для явной
    трассировки `trace('surrogate_pk')`: пути строк с полем получают id `sp-…`, пути строк
    без поля — маркер `""`.
12. **`id` / `order_id` (VARCHAR, только в `trace_mixed_sources`)**: альтернативные источники
    id. По `i%4`: `_id.$oid` (базовая запись), `id` = `id-…` без `_id`, `order_id` = `ord-…`
    без `_id`/`id` (suffix-fallback пресета), строка без id. Префикс значения однозначно
    задаёт ожидаемый `id_key` — тесты ловят ложную пару `{id, id_key}`.

## 3. Структура таблиц в Trino

В зависимости от сценария, `JdbcChaosLoaderApp` инициализирует в каталоге `lakehouse.default` две принципиально разные структуры таблиц:

### А. Таблицы сырого хаоса 

Все таблицы `fixtures.toml`: `crm_combined`, `clean`, `fmt_*`, `arr_*`, `pol_*`, `trace_*`.
Используют Staging-паттерн со строго **одной текстовой колонкой**. Это необходимо, чтобы Trino не падал при чтении структурных мутаций (например, когда вместо строки в `birth_date` прилетает массив), а отдавал сырой JSON целиком в UDAF-плагин для анализа:
```sql
CREATE TABLE lakehouse.default.crm_raw_combined (
    line VARCHAR
);
```

### Б. Таблица чистых эталонных данных (`crm_clean`)
Создается сразу с **явным указанием типов данных**. Она не содержит аномалий и служит для проверки того, как ваш будущий dbt-шаблонизатор и аналитики будут работать с корректными типами данных Trino (включая продвижение типов и UTC-смещения):
```sql
CREATE TABLE lakehouse.default.crm_clean (
    _id_oid VARCHAR,
    customer_rating DOUBLE,         -- Движок отработал Type Promotion
    payment_dates ARRAY(VARCHAR),   -- Честный массив дат
    birth_date VARCHAR,             -- Чистая строка даты
    created_at TIMESTAMP,           -- Бэкенд-дата без таймзоны
    updated_at TIMESTAMP WITH TIME ZONE, -- Бэкенд-дата со смещением
    promo_expiry_date VARCHAR       -- Чистая дата для счастливого пути
);
```