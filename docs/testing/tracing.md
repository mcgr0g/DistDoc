# Трассировка идентификаторов (`path_trace`, `anomalies[name].trace`)

Документ описывает trace-режим UDAF `analyze_json_schema`: сбор идентификаторов
строк-источников для новых узлов jsonpath, для каждой аномалии пути и для каждого
написания даты. Результат попадает в rx-data (`.rx.json`) как пары `{id, id_key}` в
`path_trace` и `anomalies[name].trace` (контракт — docs/contracts/rx-data-contract.md).

## Зачем
При обнаружении аномалии или нового узла на проде человеку нужен сам документ-виновник:
извлечь строку по id, при необходимости деперсонализировать и передать агенту — без
прямого доступа к БД и без передачи выборки целиком.

## Режимы

| Режим | SQL | Что является id |
|---|---|---|
| Явный (имя поля) | `analyze_json_schema(line, trace('doc_code'))` | значение поля `doc_code` из строки-первопроходца/аномалии; поле не встретилось — маркер |
| Пресет | `analyze_json_schema(line, trace())` | первое значение по ранжированному пресету; иначе суффикс-правило; иначе маркер |
| Обычный | `analyze_json_schema(line)` | trace-поля в rx-data не пишутся вовсе |

Если явное имя поля в документе не встретилось ни разу — строка получает маркер
`{"id": "", "id_key": ""}` (источник не найден; литеральный фолбэк удалён): в каждом
scope он пишется в отчёт не более одного раза. Колоночная семантика `trace(doc_code)`
без кавычек не поддерживается — аргумент UDAF всегда литерал-имя поля внутри JSON.

## Правила совпадения путей при сборе значения

Сбор ведётся только со скалярных значений: строк (`VARCHAR`) и целых чисел (`INTEGER`).
Значения `DOUBLE`/`BOOLEAN`/`NULL`, объекты и массивы id-кандидатами не считаются
(для BSON `_id` значение лежит на пути `_id.$oid` — он в пресете).

| Режим | Правило |
|---|---|
| Пресет | путь равен `"$." + entry` для entry из пресета, по порядку приоритета |
| Пресет (суффикс) | поле верхнего уровня `$.x`, где `x` оканчивается на суффикс (`_id`) и путь не входит в пресет; кандидат только если пресет не дал значения |
| Явный | путь оканчивается на `"." + имя_поля` (имя поля на любом уровне вложенности); берётся первое совпадение в строке |

Пресет и суффикс не содержат бизнес-кодов (`doc_code`/`code`) — это сознательное
решение; кастомные поля передаются явно: `trace('doc_code')`.

## TOML-конфиг (`src/main/resources/app-config.toml`)

```toml
[trace]
max_ids = 2            # сколько идентификаторов хранить на путь (обычные узлы и аномалии — одинаково)
max_id_length = 128    # максимальная длина одного id-значения в отчёте, символов
preset = ["_id.$oid", "_id", "id", "uuid", "guid", "oid"]  # ранг = приоритет, пути от корня, без "$."
suffix = "_id"         # поле верхнего уровня с этим суффиксом — кандидат после пресета
```

Рядом — секция `[format]` (пресет path hints для числовых дат, env
`DISTDOC_FORMAT_PATH_HINTS`, docs/contracts/value-formats.md, раздел 3.1): от неё зависит,
какие форматы, а значит и какие scopes формата, появятся на пути.

```toml
[format]
path_hints = ["_at", "_utc"]
```

Конфиг читается классами `TraceSettings` (секция `[trace]`), `FormatSettings` (секция
`[format]`) и `CoreSettings` (секция `[app]`) через `AppConfig` (org.tomlj) при первом обращении. Ресурс внутренний и
контролируемый: любая ошибка чтения/парсинга — `IllegalStateException`; дефолтов
в коде нет. Пресет и суффикс могут быть переопределены переменными окружения
`DISTDOC_TRACE_PRESET` (CSV через запятую) и `DISTDOC_TRACE_SUFFIX`; заданная
переменная полностью заменяет значение из `[trace]`.
Значение `app.distdoc_version` подставляет Gradle при сборке из `gradle.properties`
(`distdocVersion`).

## Куда попадают id

По завершении каждой строки UDAF один раз резолвит пару `{id, id_key}` (id-поле может
идти в документе после массива, поэтому резолв отложен до конца строки) и кладёт её в
каждый **scope**, который строка затронула впервые:

| Scope | Когда строка в него попадает | Где в rx-data |
|---|---|---|
| путь | путь встретился в анализаторе впервые (новый узел jsonpath) | `path_trace` |
| аномалия пути | на пути впервые зафиксирована именно эта аномалия | `anomalies[name].trace` |
| формат пути | на пути впервые доказано это написание даты | `anomalies.is_polymorphic_format.trace` (элементы с полем `format`) |

Scopes независимы: строка с новым путём и двумя аномалиями кладёт одну и ту же пару
в три коллекции; повторные строки того же факта id не добавляют. Появление пути
аномалией не является, поэтому `path_trace` не смешивается с trace аномалий.

**Полиморфизм формата.** `is_polymorphic_format` вычисляется из множества форматов пути,
поэтому его trace строится из scopes форматов: для каждого формата — до `max_ids` пар,
элементы получают поле `format`, порядок — по формату (порядок словаря), внутри — по
паре. Так аномалия, возникшая только при слиянии воркеров (каждый видел один формат),
всё равно имеет evidence, и видно, какой документ дал какое написание.

**Лимит и порядок.** Scope — ограниченный набор (bounded min-set): хранится `max_ids`
лексикографически наименьших пар по `(id, id_key)`; id длиннее `max_id_length` усекается.
Маркер `{"id": "", "id_key": ""}` хранится флагом и пишется в отчёт не более одного раза,
в хвосте, только при свободном слоте. Пустой scope в отчёт не пишется. Пара `{id, id_key}`
никогда не разрывается — в 1.1 `trace_ids` и `trace_id_key` сливались раздельно и могли
дать ложную пару.

## Merge (распределённый кластер)

Слияние состояний воркеров использует то же правило, что и добавление в строке: union
пар с усечением до `max_ids` наименьших, маркер — по OR. Поэтому merge ассоциативен,
коммутативен и идемпотентен (unit `SchemaStateSerializerTest`), а итог не зависит от
порядка слияния воркеров.

Что гарантируется, а что нет:

- **обычный отчёт** (`type`, `max_length`, `observed_formats`, имена `anomalies`)
  совпадает с одноузловым прогоном по тем же строкам;
- **набор id в trace зависит от разбиения на сплиты**: воркер кладёт в scope только
  **первое** появление факта в своей части данных, поэтому при другом разбиении в
  min-set попадут другие id. Гарантируются инварианты: каждый scope непуст у каждого
  факта, попавшего в отчёт, пары согласованы (id одного источника не получает ключ другого),
  лимит и порядок соблюдены (E2E `DistDocDistributedE2ETest`, таблица
  `trace_mixed_sources`, docs/testing/fixture-matrix.md, раздел 3).

Внутреннее состояние между воркерами отличается от отчёта: вычисляемая
`is_polymorphic_format` не пишется, scopes форматов передаются как `format_trace`.
Разбор состояния — whitelist служебных полей, fault tolerance — docs/patterns/plugin.md
(паттерны 4–5).

## Формат в rx-data

```json
{
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
    },
    "$.payment_dates[*]": {
        "type": "VARCHAR",
        "max_length": 10,
        "observed_formats": ["DATE_ONLY"],
        "anomalies": {
            "is_array_empty": {
                "detected": true,
                "trace": [
                    { "id": "60b8d29f1a4c8b0000000000", "id_key": "_id.$oid" }
                ]
            },
            "is_flat_string_array": {
                "detected": true,
                "trace": [
                    { "id": "60b8d29f1a4c8b0000000001", "id_key": "_id.$oid" }
                ]
            }
        },
        "path_trace": [
            { "id": "60b8d29f1a4c8b0000000000", "id_key": "_id.$oid" }
        ]
    }
}
```

### Trace неоднородной структуры и JSON-строк

`is_polymorphic_structure`, `is_json_string` и `has_non_json_strings` — производные аномалии: они вычисляются при сборке отчёта
из **типов пути** и в состоянии воркеров не хранятся (как `is_polymorphic_format` из форматов). Их trace собирается из trace по типам
(`type_trace` в состоянии воркера: «тип → пары», только trace-режим; первое появление типа на пути, как `format_trace` для форматов):

| Аномалия | Источник trace | Элемент |
|---|---|---|
| `is_polymorphic_structure` | первые документы каждой формы пути: `SCALAR` (любой скалярный тип), `OBJECT`, `JSON_OBJECT`, `ARRAY` (из записи `P[*]`) | `{id, id_key, form}`; лимит `max_ids` на форму |
| `is_json_string` | первые документы с JSON-строкой на пути | `{id, id_key}` |
| `has_non_json_strings` | первые документы с обычной строкой на пути, где есть JSON-строки | `{id, id_key}` |

```json
"$.counterparties": {
    "type": "OBJECT",
    "max_length": 0,
    "anomalies": {
        "is_polymorphic_structure": {
            "detected": true,
            "trace": [
                { "id": "60b8d29f1a4c8b0000000000", "id_key": "_id.$oid", "form": "ARRAY" },
                { "id": "60b8d29f1a4c8b0000000001", "id_key": "_id.$oid", "form": "OBJECT" }
            ]
        }
    }
}
```

Merge `type_trace` — тот же bounded min-set, что у остальных scopes.

В обычном режиме (`analyze_json_schema(line)`) `path_trace` и `anomalies[name].trace`
в отчёте отсутствуют; `anomalies[name]` остаётся `{"detected": true}`.

Пример снят с одного воркера: у каждого scope одна пара — первая строка факта. На
кластере scope набирает до `max_ids` пар (первые появления на разных воркерах).

## Примеры SQL (локальный стенд)

```sql
-- Явная трассировка по имени поля
SELECT analyze_json_schema(line, trace('created_at')) AS jsonpath FROM crm_combined;

-- Пресет: BSON-значения _id.$oid
SELECT analyze_json_schema(line, trace()) AS jsonpath FROM crm_combined;

-- Контроль контракта: path_trace и anomalies[name].trace в отчёте отсутствуют
SELECT analyze_json_schema(line) AS jsonpath FROM crm_combined;
```

## Дополнительные команды (lc-trace)

`mise run lc-trace` — прогон UDAF над `crm_combined` в пресет-режиме
(`TRACE_ARG = ", trace()"`), отчёт `build/dev-lakehouse/schema/combined.trace.rx.json`.
В `mise.toml` рядом закомментирован явный режим по полю 1 уровня
(`env = { TRACE_ARG = ", trace('surrogate_pk')" }`) — раскомментировать вместо
пресет-строки; поле `surrogate_pk` присутствует в ~50% строк `crm_combined`
(чётные индексы), поэтому в scopes, впервые затронутых строками без поля, появится
маркер `{"id": "", "id_key": ""}` (не более одного на scope).

## Локальная трассировка (стенд)
- интерактивный режим: mise run lc-cli (запросы к Trino Memory Connector);
- логи контейнера: docker compose -f etc/local-cluster/docker-compose.yml logs trino.

## Удалённая трассировка (ShadowDoc)
Интроспекция прод-данных через shadow-кластер (имя `shadowdoc`, trino2trino-коннектор,
read-only каталог `remote`) описана в docs/testing/local-cluster.md (раздел ShadowDoc)
и docs/adr/0004-trace-and-shadowdoc.md. Прод-контур аутентификации — OAuth2/LDAP через
параметры JDBC-драйвера (`REMOTE_PASSWORD` / `accessToken` в `REMOTE_TRINO_URL`).
