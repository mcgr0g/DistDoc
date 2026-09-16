# Reliability Matrix

## Что это

Reliability Matrix — набор e2e-тестов, проверяющих совместимость плагина DistDoc с несколькими версиями Trino (481/482/483) без развёртывания Docker-контейнеров. Используется in-process кластер (`DistributedQueryRunner` из `trino-testing`) для прогона UDAF на реальных данных.

## Зачем

### Проблема
`io.trino.testing.*` — внутренний API, не гарантирован Trino SPI. Unit-тесты проверяют только логику плагина изолированно, но не проверяют:
1. **Интеграцию с реальным движком Trino** (координатор + воркеры, exchange, шаффл)
2. **Сериализацию состояния между нодами** (`SchemaStateSerializer` — паттерн 3 из docs/patterns/plugin.md)
3. **Загрузку через PluginManager** (ServiceLoader, META-INF/services, изоляция classloader)

Локальный Docker-кластер (`mise run lc-demo`) медленный (~60 сек) и неудобен для CI.

### Решение
- **E2E in-process тесты** — быстрые (~8-10 сек), воспроизводят distributed-план с реальным шаффлом
- **Отдельный sourceSet** (`src/e2e/`) — изоляция classpath (guava 33.4.0 в unit, 33.6.0 в e2e)
- **Install-smoke (Testcontainers)** — один тест с реальным образом `trinodb/trino:483` для проверки PluginManager

## Структура тестов

### 1. Unit-тесты (`src/test/`)
**Что**: 25 тестов, 30 jar, без Trino-рантайма  
**Зачем**: Проверка логики плагина изолированно (парсер, аномалии, сериализация)  
**Как**: JUnit 5, classpath: guava 33.4.0, jackson 2.18.2

---

### 2. E2E in-process (`src/e2e/`)
**Что**: 13 тестов (4 класса), реальный движок Trino + реальный шаффл + упаковка  
**Зачем**: Проверка интеграции с Trino без Docker  
**Как**: 
- Отдельный sourceSet с изоляцией classpath
- Classpath: `trino-testing`, guava 33.6.0, jackson 2.21.3
- JVM-флаги: `--add-modules=jdk.incubator.vector`, `-Xmx3g`, `-Djava.io.tmpdir=build/tmp/e2e`

#### DistDocQueryE2ETest (3 теста)
**Что**: UDAF `analyze_json_schema(line)` над хаос-фикстурами (100 строк, сценарий ALL)  
**Зачем**: Заменяет `mise run lc-schema` — проверка базовой работы UDAF на аномалиях  
**Как**:
- In-memory генерация через `ChaosDataGenerator.generateSingleLine()` (быстрее файлового пути)
- Валидация rx-data против `docs/contracts/rx-data.schema.json`
- Проверка флагов аномалий (`is_date_part_array`, `is_array_empty`)

#### DistDocTraceE2ETest (4 теста)
**Что**: Trace-режим UDAF с идентификаторами строк  
**Зачем**: Заменяет `mise run lc-trace` — проверка trace-перегрузки и env-override  
**Как**:
- `analyze_json_schema(line, trace())` — пресет из `app-config.toml`
- `analyze_json_schema(line, trace('surrogate_pk'))` — явный режим (поле присутствует в ~50% строк)
- Проверка отсутствия `trace_ids` в обычном режиме

#### DistDocDistributedE2ETest (5 тестов)
**Что**: Распределённый кластер (coordinator + 2 workers) с реальным шаффлом по памяти  
**Зачем**: Проверка `SchemaStateSerializer` (паттерн 3) — единственный тест, где combine выполняется через `RemoteSource`  
**Как**:
- `DistributedQueryRunner.builder(...).build()` (без `setNodeCount` — количество нод по умолчанию, тест ассертит `nodes >= 3`)
- `EXPLAIN (TYPE DISTRIBUTED)` содержит `RemoteSource[sourceFragmentIds = [1]]` — фазы Input → Serialize → Combine → Output
- 500 строк (большая таблица) → результат == результат на одной ноде (корректность combine)

**Почему "реальный" шаффл**: обмен `SchemaState` между нодами через сериализацию в memory exchange (не через сеть, но через тот же `SchemaStateSerializer`, что и в проде). Unit-тесты и `lc-schema` выполняются на одной ноде — combine никогда не вызывается.

**Стоит ли проверять сетевой шаффл**: Нет — для плагина достаточно in-process. Сетевой шаффл (TCP exchange) проверяется самим Trino, наш `SchemaStateSerializer` не зависит от транспорта.

#### DistDocPackagingE2ETest (1 тест)
**Что**: Загрузка плагина через `ServiceLoader` с изолированным classloader  
**Зачем**: Проверка `META-INF/services/io.trino.spi.Plugin` и раскладки `build/libs/dist` (thin jars)  
**Как**:
- `URLClassLoader` с child-first `loadClass` для не-SPI классов (строки 45-71 в файле)
- `ServiceLoader.load(Plugin.class, pluginCl)` находит `DistDocPlugin`
- Проверка изоляции: guava/jackson берутся из `build/libs/dist`, а не из тестового classpath
- **Примечание**: `getResource` остаётся parent-first (ограничение `URLClassLoader`) — полная изоляция ресурсов только через реальный PluginManager

**Что не проверяется**: PluginManager жизненный цикл (install → подключение к каталогу → getFunctions()) — это только в install-smoke.

#### DistDocInstallSmokeTest (1 тест)
**Что**: Реальный образ `trinodb/trino:<ver>` + bind-mount `build/libs/dist` + UDAF через JDBC  
**Зачем**: Единственный тест, проверяющий настоящую загрузку через PluginManager  
**Как**:
- `@Tag("install-smoke")`, исключён из дефолтного `check` (требует Docker)
- Testcontainers: `GenericContainer("trinodb/trino:483")`
- Wait strategy: `forResponsePredicate(response -> response.contains("\"starting\":false"))` — обход race condition `/v1/info` (HTTP 200 до готовности координатора)
- ~60-70 сек (pull образа + start + wait + UDAF)

---

### 3. Разграничение Gradle / mise

**Gradle** — владелец версий и сериализации:
- `gradle.properties` → `build/compose/versions.env` (через task `composeEnv`)
- `chaosLoad.dependsOn(chaosGen)` — сериализация генерации → загрузки внутри Gradle

**mise** — оркестратор:
- Читает `--env-file build/compose/versions.env`
- `lc-gen` использует `./gradlew chaosGen` (JavaExec); `lc-load` использует `./gradlew prepareLocalCluster` (сериализует chaosGen → chaosLoad + downloadTrinoCli)
- `TRINO_VERSION`, `DISTDOC_VERSION`, `UID`, `GID` удалены из `mise.toml [env]`

---

## Локальный запуск

```bash
# Полная верификация (unit + e2e) на текущей версии (483)
./gradlew check

# Конкретная версия Trino
./gradlew check -PtrinoVersion=481
./gradlew check -PtrinoVersion=482

# Только e2e-тесты
./gradlew e2eTest -PtrinoVersion=481

# Install-smoke (требует Docker)
./gradlew installSmokeTest -PtrinoVersion=483

# Mise-обёртка (запускает ./gradlew check)
mise run lc-verify
```

---

## GitHub Actions CI

Файл `.github/workflows/reliability-matrix.yml`:

```yaml
matrix:
  trino-version: [481, 482, 483]
```

Каждая ячейка:
1. `./gradlew test -PtrinoVersion=${{ matrix.trino-version }}`
2. `./gradlew e2eTest -PtrinoVersion=${{ matrix.trino-version }}`

Install-smoke (отдельный job):
1. `./gradlew build -PtrinoVersion=483`
2. `./gradlew installSmokeTest -PtrinoVersion=483`

---

## Метрики

Локальный прогон (macOS arm64, Java 25):
- Unit-тесты: ~1-2 секунды
- E2E suite (13 тестов): ~8-10 секунд
- Полная матрица (3 версии × check): ~30-40 секунд
- Install-smoke: ~60-70 секунд (pull образа + start + wait + UDAF)

---

## Что НЕ проверяется

- **Прод-аутентификация** (LDAP/OAuth2) — только на реальном контуре
- **Сетевой шаффл** (TCP exchange между нодами) — достаточно in-process memory exchange
- **Реальный образ на каждой версии** — install-smoke только на 483

---

## Почему отдельный sourceSet для e2e?

**Проблема**: `trino-testing` подтягивает guava 33.6.0 и jackson 2.21.3, что ломает unit-тесты (они должны выполняться на продовых версиях 33.4.0/2.18.2 из build.gradle).

**Решение**: отдельный sourceSet `e2e` с собственным classpath. Unit-тесты (`src/test/`) остаются изолированными от Trino-рантайма.

**Альтернативы**:
1. Теги `@Tag("e2e")` в `src/test/` — отвергнуто: classpath общий, guava 33.6.0 попадёт в unit-тесты
2. Отдельный подпроект (multi-module Gradle) — избыточно для 4 тестовых классов

**Детали**: см. `docs/adr/0005-e2e-sourceSet.md`

---

## См. также

- ADR: `docs/adr/0005-e2e-sourceSet.md`
- План: `local/research/reliability-matrix-plan.md`
- Пробы: `local/research/probes/inproc-probe/`
- Фикстуры: `docs/testing/fixtures.md`
- Local cluster: `docs/testing/local-cluster.md`
