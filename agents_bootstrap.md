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
14 классов `src/main/java/io/github/mcgr0g/distdoc/udaf/` (включая подпакеты `anomalies/` и `config/`);
ресурс `src/main/resources/app-config.toml` — TOML-конфиг плагина: `[app]` (версия схемы) и `[trace]` (лимиты, пресет, суффикс):

| Класс | Назначение |
|---|---|
| DistDocPlugin | SPI-регистрация UDAF в Trino |
| JsonSchemaAggregation | UDAF: фазы Input / Shuffle / Combine / Output |
| JsonSchemaTraceAggregation | UDAF-перегрузка analyze_json_schema(line, trace(...)) |
| TraceFunctions | scalar-обёртка trace() / trace(v) |
| config/AppConfig | Единственная точка чтения /app-config.toml |
| config/CoreSettings | Секция [app]: версия схемы rx-data (major.minor) |
| config/TraceSettings | Секция [trace]: лимиты/пресет/суффикс + env-override |
| JsonSchemaAnalyzer | Стриминг-парсер (O(1), без DOM) + trace-режим |
| SchemaState | Состояние схемы (типы, max_length, карта аномалий) |
| SchemaStateSerializer | Сетевой маршалинг (open-closed, динамические флаги) |
| PathMetrics | Метрики JSONPath-путей (включая trace_ids) |
| anomalies/AnomalyDetector | Интерфейс-стратегия анализа массивов |
| anomalies/ArrayAnomalyDetector | Реализация стратегии (флаги аномалий) |
| anomalies/ArrayContext | Неизменяемый DTO-снимок для стратегии |

testFixtures: 9 классов генератора хаос-данных (chaos/ + chaos/apps/), fixtures.toml, fixtures.env — сценарии из docs/testing/fixtures.md.

## Таблица «документ → когда читать»

| Путь | Назначение | Когда читать |
|---|---|---|
| readme.md | визитка | всегда первым |
| agents.md | правила агента | в начале каждой сессии |
| agents_bootstrap.md | эта карта | при входе в незнакомую область |
| docs/contracts/rx-data-contract.md | общий контракт rx-data | при изменении вывода плагина или формата .rx.json |
| docs/adr/0001…0004 | решения (стриминг, стратегия аномалий, сериализация, trace+ShadowDoc) | при изменении архитектурно значимого поведения |
| docs/project-brief.md | бизнес-контекст и требования | при вопросах «зачем» |
| docs/patterns/plugin.md | 5 паттернов реализации | при правках рантайма плагина |
| docs/testing/local-cluster.md | sandbox-стенд (mise + Trino Memory Connector) + ShadowDoc | при работе со стендом |
| etc/shadowdoc/ | shadow-кластер ShadowDoc (compose, trino-config, catalog/remote.properties, fetch-trino2trino.sh, .env.example) | при прод-интроспекции без установки плагина |
| docs/testing/fixtures.md | контракт хаос-фикстур и 10 полей | при изменении генерации данных |
| docs/testing/tracing.md | трассировка идентификаторов (trace_ids, режимы, конфиг) | при работе с trace-режимом UDAF |
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
- `mise run lc-verify` — полная верификация: `./gradlew check` (unit + in-process e2e), затем реальный Docker-путь `lc-schema` и `lc-trace`; после завершения кластер автоматически останавливается через `lc-dn`.
- `mise run lc-demo` — вершина локального DAG: lc-schema + пост-остановка `lc-dn` (depends_post), кластер не работает в холостую;
- `mise run sd-demo` — вершина shadow-DAG: lc-load → sd-schema с авто-остановкой `lc-dn` и `sd-dn`;
- `mise run sd-up` / `sd-dn` — развертывание/остановка shadow-кластера ShadowDoc (`etc/shadowdoc/docker-compose.yml`, depends composeEnv);
- `mise run sd-cli` — интерактивный trino-cli к shadow (каталог `remote`);
- `mise run sd-schema` — интроспекция прод-таблицы через ShadowDoc, отчёт `build/dev-lakehouse/schema/shadow.rx.json` (depends lc-load, sd-up); `SHADOW_PERSIST=true` дополнительно копирует отчёт в `etc/shadowdoc/data/`.
## Карта фикстур
Из docs/testing/fixtures.md:

- `_id.$oid` — вложенный BSON-id (рекурсивный парсер путей верхнего уровня);
- `customer_rating` — INTEGER в чистых, в `all` ~50% DOUBLE; числовая решётка ядра: итог DOUBLE;
- `payment_dates` — пустые массивы / валидные ISO-даты (детекция emptyarray);
- `birth_date` — строка ↔ массив частиц `["1990","05","15"]` (dateasarray, IF-THEN-ELSE typeOf);
- `created_at` — ISO без таймзоны; в `date_at_unix` — Unix-миллисекунды (INTEGER); в `date_as_plain` — обычная дата YYYY-MM-DD (~33% в `all`);
- `updated_at` — ISO с таймзоной (+03:00);
- `promo_expiry_date` — чистая дата happy path (всегда строка);
- `metadata_encoded` — экранированный под-документ (деэкранирование jsonstring);
- `version.$numberLong` — BSON-служебный слог в пути;
- `doc_meta.@type`/`doc_meta.@version` — @-слоги в пути.

6 таблиц-сценариев: crm_combined (all), debug_date_part (date_as_array), debug_empty_arrays (empty_array), debug_date_unix (date_at_unix), debug_date_plain (date_as_plain), clean (clean).

## твой служебный local/
Каталог `local/` включен в `.gitignore`; содержимое служебное — не коммитить и не удалять. Документация генератора лежит в `local/generator/` (строка таблицы выше).
