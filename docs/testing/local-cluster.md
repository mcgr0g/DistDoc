# Инфраструктура тестирования и паттерны производительности

Документ описывает техническую обвязку локального sandbox-стенда на базе `mise`, `Trino` и `Memory Connector`, 
а также фиксирует архитектурные паттерны оптимизации рантайма.

## 1. Архитектурный стек и топология

Стенд минимизирован до одного контейнера и нативного запуска Java-процессов на хост-машине:
*   **Trino** — SQL-движок, работающий в режиме `connector.name=memory`. Данные хранятся исключительно в RAM JVM. 
    При перезапуске контейнера память полностью очищается.
*   **Mise** — менеджер задач и окружения. Обеспечивает сквозное управление версиями инструментов
    и автоматическое проброс переменных среды из `fixtures.env`.

## 2. Паттерн производительности: Безцикловый JSON-импорт

### Проблема (OLTP Bottleneck)
Официальный JDBC-драйвер Trino не поддерживает аппаратное пакетное склеивание запросов. 
Команда `PreparedStatement.executeBatch()` под капотом превращается в последовательный цикл HTTP-запросов. 
Передача даже 200 строк приводит к 200 сетевым раундтрипам (Network Latency) между хостом и Docker, что занимает **~5200 мс**.

### Решение (Single-Request JSON Array via UNNEST)
Вместо циклической отправки строк, класс `JdbcChaosLoaderApp` с помощью библиотеки `Jackson` 
упаковывает весь JSONL-файл целиком в один экранированный текстовый JSON-массив на хосте 
и отправляет его в Trino **ровно за один сетевой запрос**.

На стороне сервера Trino массив разворачивается в стандартные SQL-строки «на лету» с помощью встроенных функций `json_parse` и `unnest`:

```sql
INSERT INTO crm_combined (line)
SELECT cast(t.item as varchar)
FROM unnest(cast(json_parse(?) as array(json))) as t(item)
```

**Результат паттерна:** Время заливки снижено с 5200 мс до **780 мс**. 
Паттерн на безопасен для UTF-эмодзи и спецсимволов, так как данные передаются как стандартный экранированный `java.lang.String`.

## 3. Механика инкрементального кэширования в `mise`

Для обеспечения максимальной скорости локальной обратной связи (Feedback Loop) 
полностью исключена команда `clean` из Gradle-сборок. Оптимизация построена на двух уровнях кэширования:

1.  Уровень Mise (`sources` / `outputs`): Утилита считает хеш папки `src/` перед вызовом задач. 
    Если изменений в коде нет и объявленные `outputs` задачи на месте, запуск Gradle блокируется целиком (`task is up to date`), экономя 3–5 секунд на прогрев JVM-демона.
    Если артефакты удалены (например, `./gradlew clean`) — задача перезапускается, несмотря на неизменность `sources` (поэтому у `build` объявлены `outputs`).
2.  Уровень Gradle (Инкрементальный `build`): Если `mise` пропустил команду дальше, чистый `./gradlew build` компилирует 
    только изменившиеся классы. Если вы правите фикстуры, сборка тяжелого `shadowJar` самого плагина полностью пропускается.

## 4. Карта локальных команд (`mise run ...`)

*   `mise run build` — Инкрементальная сборка всех артефактов (плагина и фикстур) за один проход Gradle.
*   `mise run lc-up` / `lc-dn` — Управление жизненным циклом локального Docker-контейнера Trino.
*   `mise run lc-gen` — Генерация хаос-данных на жесткий диск (запускается, только если изменился `fixtures.toml` или JAR фикстур).
*   `mise run lc-load` — Ультра-быстрый импорт данных в память Trino по JDBC (вызывает `./gradlew prepareLocalCluster` — сериализует chaosGen → chaosLoad + downloadTrinoCli, depends lc-up).
*   `mise run lc-cli` — Вход в интерактивный терминал Trino CLI для ручных запросов.
*   `mise run lc-schema` — Запуск UDAF-плагина над таблицей хаоса, анализ схемы и выгрузка отформатированного JSON-отчета в `build/dev-lakehouse/schema/combined.rx.json` с помощью `jaq`.
*   `mise run lc-trace` — Тот же UDAF в trace-режиме (пресет), отчёт `build/dev-lakehouse/schema/combined.trace.rx.json`. Детали и явный режим — docs/testing/tracing.md, раздел "Дополнительные команды (lc-trace)".
*   `mise run lc-demo` — Вершина локального DAG: полный демо-пайплайн (lc-schema) с авто-остановкой кластера в пост-вызове (`depends_post = ["lc-dn"]`), чтобы контейнер не работал в холостую. `lc-schema` остаётся изолированной задачей DAG.
*   `mise run lc-verify` — Полная верификация: `./gradlew check` (unit + e2e-тесты) с авто-остановкой кластера (`depends_post = ["lc-dn"]`). Заменяет `lc-schema` + `lc-trace` — E2E-тесты выполняются in-memory.
*   `mise run sd-demo` — Вершина shadow-DAG: `lc-load` (прод-эмулятор) → `sd-schema` (shadow-интроспекция) с авто-остановкой обоих кластеров (`depends_post = ["lc-dn", "sd-dn"]`).

## 5. ShadowDoc (прод-интроспекция без установки плагина)

ShadowDoc — теневой кластер (имя **shadowdoc**, mise-задачи `sd-*`) для тяжёлых случаев, когда на прод катить страшно. Прод-Trino подключается как **read-only** каталог данных: плагин DistDoc ставится только в shadow-контейнер, UDAF `analyze_json_schema` выполняется локально, на прод ничего не устанавливается и ничего не пишется.

### Архитектура
- shadow-Trino (контейнер `shadowdoc-trino-local`, порт `${SHADOW_PORT:-8081}`) + плагин DistDoc из `build/libs/dist`;
- сторонний Apache-2.0 коннектор **kkd927/trino2trino** (встроенного Trino→Trino коннектора в открытом Trino нет) монтируется в `/usr/lib/trino/plugin/trino`;
- прод виден как локальный каталог `remote` (имя файла `catalog/remote.properties`); удалённый каталог прода (например, `lakehouse`) задаётся последним слогом `REMOTE_TRINO_URL` и в SQL не используется — запросы всегда к `remote.<schema>.<table>`;
- `extra_hosts: host.docker.internal` даёт контейнеру доступ к прод-эмулятору на хосте (и на Linux, и на macOS).

### Переменные окружения
| Переменная | Дефолт | Смысл |
|---|---|---|
| `REMOTE_TRINO_URL` | `jdbc:trino://host.docker.internal:8080/lakehouse` | JDBC URL удалённого Trino; последний слог — удалённый каталог |
| `REMOTE_USER` | `admin` | пользователь удалённого Trino |
| `REMOTE_PASSWORD` | пусто | пароль (LDAP/basic); OAuth2 — `accessToken` в `REMOTE_TRINO_URL` |
| `REMOTE_SCHEMA` / `REMOTE_TABLE` | `default` / `crm_combined` | схема и таблица источника |
| `SHADOW_PORT` | `8081` | порт shadow на хосте |
| `SHADOW_PERSIST` | `false` | `true` — копия отчёта в `etc/shadowdoc/data/<REMOTE_TABLE>-<ГГГГммдд-ЧЧММСС>.rx.json` |

Значения по умолчанию — в `[env]` раздела mise.toml; переопределяются корневым `.env` (см. `etc/shadowdoc/.env.example`).

### Порядок работы (прод-эмулятор = локальный lc-кластер)
```bash
mise run lc-up        # прод-эмулятор на хосте (порт 8080, таблица crm_combined)
mise run lc-load      # залить фикстуры в RAM-таблицу crm_combined (lc-up сам данные не грузит)
./etc/shadowdoc/fetch-trino2trino.sh   # однократно: скачать коннектор в etc/shadowdoc/plugin/
mise run sd-up        # поднять shadow-кластер
mise run sd-cli       # интерактивный trino-cli к shadow (каталог remote)
mise run sd-schema    # интроспекция remote.default.crm_combined -> build/dev-lakehouse/schema/shadow.rx.json
mise run sd-dn        # остановить shadow-кластер
```

`sd-schema` данные сам не грузит: Memory-коннектор стирается при `down -v` (это локальный
контур, не прод), поэтому после перезапуска кластера нужна последовательность
`lc-up → lc-load → sd-up → sd-cli/sd-schema`.
Вся цепочка автоматизирована: `mise run sd-demo` = `lc-load` → `sd-schema` (шаги идут последовательно) с пост-остановкой обоих кластеров.
Ожидаемый результат `sd-schema` — rx-data (`.rx.json`), идентичный локальному прогону `lc-schema` (одни данные, один UDAF, прогон через федеративный каталог). Проверка каталога: `SHOW SCHEMAS FROM remote` должен вернуть схемы удалённого `lakehouse`.

### Аутентификация
Аутентификация к проду покрыта параметрами JDBC-драйвера Trino, которые коннектор пробрасывает в `connection-*`:
- **LDAP/basic**: пароль в `REMOTE_PASSWORD` (`connection-password`);
- **OAuth2**: токен доступа в URL (`connection-url=jdbc:trino://host:443/catalog?SSL=true&accessToken=...`) — значение вставляет пользователь в `REMOTE_TRINO_URL`.

**Keycloak-эмуляция (вывод анализа, без реализации):** локальная эмуляция «trino + keycloak → shadow» технически возможна (keycloak/keycloak + realm import + OAuth2-конфиг на lc), но не входит в эту поставку: проверка цепочки OAuth2 делается на реальном контуре (или отдельным стендом позже). В shadow-стенде аутентификация покрыта параметрами JDBC (password/accessToken), поведение которых документировано производителями драйвера.

### Персистентность
Таблицы Trino **всегда** in-memory: Memory connector хранит все данные и метаданные в RAM и теряет их при рестарте (документация Trino, connector/memory.md). Полноценное переживающее рестарт хранилище таблиц потребовало бы Delta/Iceberg-коннектора — вне этой поставки.
Персистентность ShadowDoc обеспечивают **файлы** в bind-mount `etc/shadowdoc/data`:
- `SHADOW_PERSIST=true` — копия rx-data сохраняется в `etc/shadowdoc/data/<REMOTE_TABLE>-<ГГГГммдд-ЧЧММСС>.rx.json` (имя файла = имя таблицы источника; формат — rx-data, docs/contracts/rx-data-contract.md); файл переживает `sd-dn` и рестарты;
- `SHADOW_PERSIST=false` (дефолт) — отчёт только в `build/dev-lakehouse/schema/shadow.rx.json`.
