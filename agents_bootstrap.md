# AGENTS_BOOTSTRAP — Карта проекта DistDoc

Карта проекта для людей и агентов. Читать при входе в незнакомую область; правила работы — в agents.md.

## Суть DistDoc
DistDoc — UDAF-плагин Trino (481+) для послойной интроспекции полиморфного JSON за один проход: O(1) память на строку, без DOM. Результат — rx-data (`.rx.json`) (docs/contracts/rx-data-contract.md). Второй компонент — Python-генератор dbt-моделей (код вне этого репозитория, регламент в local/generator/).

## C4 L1 (System Context)

```mermaid
graph LR
    Trino["Trino (Loom)"] -->|SQL UDAF| Plugin["DistDoc Plugin"]
    Plugin -->|rx-data (.rx.json)| Gen["Python dbt Generator"]
    Gen -->|root/unnest модели| Staging["dbt Staging Layer"]
```

Подпись: стрелка Trino↔плагин — вызов UDAF; плагин↔генератор — контракт docs/contracts/rx-data-contract.md.

## C4 L2 (компоненты плагина)
18 классов `src/main/java/io/github/mcgr0g/distdoc/udaf/` (включая подпакеты `anomalies/`, `config/` и `formats/`);
ресурс `src/main/resources/app-config.toml` — TOML-конфиг плагина: `[app]` (версия схемы), `[trace]` (лимиты, пресет, суффикс) и `[format]` (path hints):

| Класс | Назначение |
|---|---|
| DistDocPlugin | SPI-регистрация UDAF в Trino |
| JsonSchemaAggregation | UDAF: фазы Input / Shuffle / Combine / Output |
| JsonSchemaTraceAggregation | UDAF-перегрузка analyze_json_schema(line, trace(...)) |
| TraceFunctions | scalar-обёртка trace() / trace(v) |
| config/AppConfig | Единственная точка чтения /app-config.toml |
| config/CoreSettings | Секция [app]: версия схемы rx-data (major.minor) |
| config/TraceSettings | Секция [trace]: лимиты/пресет/суффикс + env-override |
| config/FormatSettings | Секция [format]: path hints numeric timestamp + env-override |
| JsonSchemaAnalyzer | Стриминг-парсер (O(1), без DOM) + trace-режим; сборка отчёта и внутреннего состояния (whitelist) |
| SchemaState | Состояние схемы (типы, max_length, карта аномалий) |
| SchemaStateSerializer | Сетевой маршалинг состояния (паттерн 5: чужой путь пропускается с WARNING; разбор — в анализаторе) |
| PathMetrics | Метрики пути: типы, форматы, аномалии (монотонно), trace scopes |
| TraceEvidence | Bounded min-set пар {id, id_key} одного trace scope |
| anomalies/AnomalyDetector | Интерфейс-стратегия анализа массивов |
| anomalies/ArrayAnomalyDetector | Реализация стратегии (флаги аномалий) |
| anomalies/ArrayContext | Неизменяемый DTO-снимок для стратегии |
| formats/ValueFormat | Закрытый словарь написаний дат (порядок = приоритет, docs/contracts/value-formats.md) |
| formats/FormatDetector | Pure-детектор: сканер строк, Unix-время по контексту пути |

testFixtures: 9 классов генератора хаос-данных (chaos/ + chaos/apps/), fixtures.toml, fixtures.env — сценарии из docs/testing/fixtures.md.

## Таблица «документ → когда читать»

| Путь | Назначение | Когда читать |
|---|---|---|
| readme.md | визитка | всегда первым |
| agents.md | правила агента | в начале каждой сессии |
| agents_bootstrap.md | эта карта | при входе в незнакомую область |
| docs/contracts/rx-data-contract.md | общий контракт rx-data | при изменении отчёта плагина или формата .rx.json |
| docs/contracts/value-formats.md | форматы дат: словарь, правила, path hints, таблица примеров | при работе с `observed_formats` и `FormatDetector` |
| docs/adr/0000-index.md | индекс действующих ADR (0001…0006) и где их решения актуальны | в последнюю очередь — только для «почему так решили» |
| docs/project-brief.md | бизнес-контекст и требования | при вопросах «зачем» |
| docs/patterns/plugin.md | 5 паттернов реализации | при правках рантайма плагина |
| docs/testing/local-cluster.md | sandbox-стенд (mise + Trino Memory Connector) + ShadowDoc | при работе со стендом |
| etc/shadowdoc/ | shadow-кластер ShadowDoc (compose, trino-config, catalog/remote.properties, fetch-trino2trino.sh, .env.example) | при прод-интроспекции без установки плагина |
| docs/testing/fixtures.md | контракт хаос-фикстур и 10 полей | при изменении генерации данных |
| docs/testing/fixture-matrix.md | ожидания каждой таблицы фикстур, golden-map `crm_combined` | при изменении фикстур и assertions |
| docs/testing/tracing.md | трассировка идентификаторов (`path_trace`, `anomalies[name].trace`, пары `{id, id_key}`, merge, режимы, конфиг) | при работе с trace-режимом UDAF |
| docs/testing/prod-access.md | прод-доступ (заглушка) | при работе с продом |
| docs/guides/demo.md | сценарий демонстрации | при подготовке демо |
| local/generator/README.md | карта документации генератора | при работе над вторым компонентом |
| local/steps.md | протокол памяти | перед каждым новым шагом |

## Карта команд mise
Точный список задач из mise.toml:

- `mise run composeEnv` — генерация `build/compose/versions.env` из gradle.properties (TRINO_VERSION, DISTDOC_VERSION, UID, GID);
- `mise run build` — инкрементальная сборка fat-jars (плагин + фикстуры) в build/libs/;
- `mise run test` — JUnit: `./gradlew cleanTest test --info`;
- `mise run cli-download` — trino-cli в `.local/bin/` (depends build);
- `mise run lc-up` — docker compose up локального кластера Trino 483 (depends build, composeEnv);
- `mise run lc-dn` — остановка: `docker compose … down -v` (имя именно lc-dn, не lc-down);
- `mise run lc-gen` — генерация хаос-данных через `./gradlew chaosGen` (JavaExec, без fat-jar);
- `mise run lc-load` — импорт в RAM Trino через `./gradlew prepareLocalCluster` (сериализует chaosGen → chaosLoad + downloadTrinoCli, depends lc-up);
- `mise run lc-cli` — интерактивный trino-cli;
- `mise run lc-schema` — прогон UDAF над crm_combined и отчёт `build/dev-lakehouse/schema/combined.rx.json` (depends lc-load);
- `mise run lc-trace` — trace-режим с пресетом, отчёт `combined.trace.rx.json` (depends lc-load);
- `mise run lc-verify` — проверка глазами: `./gradlew check` (unit + in-process e2e), затем реальный Docker-путь `lc-schema` и `lc-trace`; после завершения кластер автоматически останавливается через `lc-dn`. Обратная связь агента — `./gradlew verify` (AGENTS.md).
- `mise run lc-demo` — вершина локального DAG: lc-schema + пост-остановка `lc-dn` (depends_post), кластер не работает в холостую;
- `mise run sd-demo` — вершина shadow-DAG: lc-load → sd-schema с авто-остановкой `lc-dn` и `sd-dn`;
- `mise run sd-up` / `sd-dn` — развертывание/остановка shadow-кластера ShadowDoc (`etc/shadowdoc/docker-compose.yml`, depends composeEnv);
- `mise run sd-cli` — интерактивный trino-cli к shadow (каталог `remote`);
- `mise run sd-schema` — интроспекция прод-таблицы через ShadowDoc, отчёт `build/dev-lakehouse/schema/shadow.rx.json` (depends lc-load, sd-up); `SHADOW_PERSIST=true` дополнительно копирует отчёт в `etc/shadowdoc/data/`.
## Карта фикстур
Из docs/testing/fixtures.md:

- `_id.$oid` — вложенный BSON-id (рекурсивный парсер путей верхнего уровня);
- `customer_rating` — INTEGER в чистых, DOUBLE в `rating_promotion` (нечётные) и `all` (`i%5<2`); числовая решётка ядра: итог DOUBLE;
- `payment_dates` — пустые массивы / валидные ISO-даты (детекция emptyarray);
- `birth_date` — строка ↔ массив частиц `["1990","05","15"]` (dateasarray, IF-THEN-ELSE typeOf);
- `created_at` — ISO без таймзоны; в `date_at_unix` — Unix-миллисекунды (INTEGER); в `date_as_plain` — обычная дата YYYY-MM-DD; в `pol_*` три написания по `i%3`, в `all` четыре по `i%4`;
- `updated_at` — ISO с таймзоной (+03:00);
- `promo_expiry_date` — чистая дата happy path (всегда строка);
- `metadata_encoded` — экранированный под-документ (деэкранирование jsonstring);
- `version.$numberLong` — BSON-служебный слог в пути;
- `doc_meta.@type`/`doc_meta.@version` — @-слоги в пути.

25 таблиц `fixtures.toml`, префикс = ось проверки (ожидания — docs/testing/fixture-matrix.md, исполняются `FixtureMatrixTest`):
- `crm_combined` (all), `clean` (clean);
- `fmt_created_at_local`, `fmt_updated_at_offset`, `fmt_promo_expiry_date` (clean), `fmt_created_at_unix_millis` (date_at_unix), `fmt_created_at_date_only` (date_as_plain), `fmt_customer_rating_promotion` (rating_promotion);
- `arr_birth_date_parts` (date_as_array), `arr_payment_dates_empty` (empty_array);
- `pol_created_at_formats`, `pol_created_at_with_arrays`, `pol_created_at_with_rating` (режим = имя таблицы);
- `obj_nested_plain`, `obj_array_of_objects`, `obj_object_or_array`, `obj_object_or_scalar`, `obj_object_or_array_formats`, `obj_json_object_or_array`, `obj_json_array_elements`, `obj_json_with_plain`, `obj_json_with_native_object`, `obj_json_false_alarm`, `obj_bson_id_forms` (структура, JSON-строки, BSON-обёртки; режим = имя таблицы);
- `trace_mixed_sources` (id-источник по `i%4`: `_id.$oid` / `id` / `order_id` / нет).
Группы строк внутри режима — только по `index`, без random.

## твой служебный local/
Каталог `local/` включен в `.gitignore`; содержимое служебное — не коммитить и не удалять. Документация генератора лежит в `local/generator/` (строка таблицы выше).
