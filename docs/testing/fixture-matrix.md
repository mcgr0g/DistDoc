# Матрица фикстур и карта ожиданий

Документ — единственное место, где записано, **что** должна показать каждая таблица
хаос-фикстур. `src/testFixtures/resources/fixtures.toml` остаётся исполняемым конфигом,
`docs/testing/fixtures.md` описывает поля и стенд. Правила распознавания форматов —
[value-formats.md](../contracts/value-formats.md); форма отчёта —
[rx-data-contract.md](../contracts/rx-data-contract.md).

## Как читать

- **Префикс таблицы — ось проверки**: `fmt_` — один формат/тип на одном поле;
  `arr_` — одна структурная аномалия массива; `pol_` — полиморфизм форматов и его
  взаимодействие с другой осью; `trace_` — источник трассировки и merge;
  `obj_` — структура: неоднородные формы пути (`is_polymorphic_structure`), массивы объектов, JSON-строки,
  BSON-обёртки (контракт rx-data 3.0, разделы 2 и 2b).
  Без префикса: `crm_combined` — интеграционная таблица, `clean` — эталон.
- После префикса — имя проверяемого поля и вариант: `fmt_created_at_unix_millis`.
- **Декартово произведение не строится**: новая ось добавляет атомарные таблицы и
  только те комбинации, где оси взаимодействуют.
- Проверяются смысловые множества (пути, форматы, имена аномалий, `type`), а не
  полный JSON snapshot и не порядок путей.
- «Режим» — значение `type` в `fixtures.toml` (`AnomalyScenario`), а не ось.

## 0. Базовая запись

`ForgottenMigrationsSource.generateBaseRecord` одинакова для всех таблиц; режим
мутирует только свои поля. Поле, которое режим не трогает, даёт в отчёте строку
этой таблицы:

| Путь | `type` | `observed_formats` | `anomalies` |
|---|---|---|---|
| `$._id.$oid` | `VARCHAR` | — | — |
| `$.customer_rating` | `INTEGER` | — (нет hint) | — |
| `$.payment_dates[*]` | `VARCHAR` | `DATE_ONLY` (элементы) | `is_flat_string_array` |
| `$.birth_date` | `VARCHAR` | `DATE_ONLY` | — |
| `$.created_at` | `VARCHAR` | `LOCAL_DATETIME` | — |
| `$.updated_at` | `VARCHAR` | `OFFSET_DATETIME` | — |
| `$.promo_expiry_date` | `VARCHAR` | `DATE_ONLY` | — |
| `$.metadata_encoded` | `VARCHAR`, `max_length` = длина исходной строки (43) | — | `is_json_string` |
| `$.metadata_encoded.user_agent` | `VARCHAR` | — | — |
| `$.metadata_encoded.retry_count` | `INTEGER` | — | — |
| `$.version.$numberLong` | `INTEGER` | — (слог `version`, нет hint) | — |
| `$.doc_meta.@type` / `$.doc_meta.@version` | `VARCHAR` / `INTEGER` | — | — |

Базовая запись без мутаций — это и есть ожидание таблицы `clean`. Отсутствие
`observed_formats` на `$.customer_rating`, `$.version.$numberLong`,
`$.doc_meta.@version` — обязательная negative-проверка: числа без контекста датами
не становятся. Чистые объекты (`$._id`, `$.version`, `$.doc_meta`) собственных записей
не имеют (negative-проверка: ни одного пути с `type=OBJECT` в базовой записи); `$._id.$oid`
— лист, BSON-обёртка объектом не считается. `$.metadata_encoded` — JSON-строка: запись строки
с `is_json_string` и потомки содержимого.

## 1. Атомарные таблицы

Ниже — только отличия от базовой записи и обязательные assertions.

| Таблица | Режим | Поле | Вход | Ожидаемый report |
|---|---|---|---|---|
| `clean` | `clean` | все | базовая запись | ровно раздел 0; ни одной `is_polymorphic_format` |
| `fmt_created_at_local` | `clean` | `created_at` | `2026-07-23T01:15:00` | `$.created_at`: `LOCAL_DATETIME`, без `is_polymorphic_format` |
| `fmt_created_at_unix_millis` | `date_at_unix` | `created_at` | `1784769300000` | `$.created_at`: `type=INTEGER`, `max_length=0`, `UNIX_MILLIS`; `VARCHAR` отсутствует |
| `fmt_created_at_date_only` | `date_as_plain` | `created_at` | `2026-07-23` | `$.created_at`: `DATE_ONLY`, без `is_polymorphic_format` |
| `fmt_updated_at_offset` | `clean` | `updated_at` | `2026-07-23T01:15:00+03:00` | `$.updated_at`: `OFFSET_DATETIME` (не `UTC_DATETIME`) |
| `fmt_promo_expiry_date` | `clean` | `promo_expiry_date` | `2026-08-01` | `$.promo_expiry_date`: `DATE_ONLY` без hint (строковым форматам hint не нужен) |
| `fmt_customer_rating_promotion` | `rating_promotion` | `customer_rating` | чётный индекс `INTEGER`, нечётный `DOUBLE` | `$.customer_rating`: `type=DOUBLE`, нет `observed_formats`, нет `anomalies` |
| `arr_birth_date_parts` | `date_as_array` | `birth_date` | `["1990","05","15"]` | `$.birth_date[*]`: `is_date_part_array` + `is_flat_string_array`, нет `observed_formats`; путь `$.birth_date` отсутствует |
| `arr_payment_dates_empty` | `empty_array` | `payment_dates` | `[]` | `$.payment_dates[*]`: `type=ARRAY`, `is_array_empty`, нет `observed_formats` |

Три таблицы на режиме `clean` (`fmt_created_at_local`, `fmt_updated_at_offset`,
`fmt_promo_expiry_date`) — короткие repro с одним фокусным полем: базовая запись уже
даёт нужное значение.

## 1a. Структура (ось `obj_`)

Режим = имя таблицы (как у `pol_*`), `count = 60`, группы выбираются по `index`. Каждая
таблица — базовая запись (раздел 0) плюс одно структурное поле; ниже — только добавленные
пути. Assertions проверяются и «в плюс», и «в минус» (поддерево чужой ветки отсутствует).
Формы пути, флаг `is_polymorphic_structure` и `form` в trace — контракт rx-data 3.0, раздел 2b.

| Таблица | Группы по `index` | Вход (добавленное поле) | Ожидаемый report |
|---|---|---|---|
| `obj_nested_plain` | все строки одинаковы | `profile={"contacts":{"email":"…","phone":"…"},"flags":{}}` | только листья: `$.profile.contacts.email`/`.phone`: `VARCHAR`; **ни одной записи** `$.profile`, `$.profile.contacts`, `$.profile.flags` (чистые объекты, пустой `{}` не оставляет ничего); нет путей `[*]` у `profile` |
| `obj_array_of_objects` | все строки одинаковы | `items=[{"sku":"…","qty":2,"dims":{"w":1,"h":2}}, …]` (2 элемента) | `$.items[*]`: `ARRAY`, без аномалий (массив объектов не пуст); `$.items[*].sku`: `VARCHAR`; `$.items[*].qty`, `$.items[*].dims.w`/`.h`: `INTEGER`; **нет** `$.items` и `$.items[*].dims` |
| `obj_object_or_array` | `party`: `i%2==0` — объект `{"name":"…","role":"…"}`; `i%2==1` — массив `[{"id":"…","share":50},…]` (1–2 элемента) | как слева | неоднородное поле: `$.party`: `type=OBJECT`, `is_polymorphic_structure` (формы `OBJECT`, `ARRAY`); `$.party.name`/`.role`: `VARCHAR`; `$.party[*]`: `ARRAY` без аномалий; `$.party[*].id`: `VARCHAR`; `$.party[*].share`: `INTEGER`. Ветки не пересекаются: нет `$.party.id`, `$.party.share`, `$.party[*].name`, `$.party[*].role` |
| `obj_object_or_scalar` | `contact`: `i%2==0` — объект `{"email":"…"}`; `i%2==1` — строка `"n/a"` | как слева | `$.contact`: `type=VARCHAR`, `max_length=3`, `is_polymorphic_structure` (формы `OBJECT`, `SCALAR`); `$.contact.email`: `VARCHAR`; пути `$.contact[*]` нет |
| `obj_object_or_array_formats` | `party` как в `obj_object_or_array`, плюс `signed_at`: объектная ветка `2026-07-23T01:15:00`, массивная `2026-07-23` | как слева | как `obj_object_or_array` + `$.party.signed_at`: `LOCAL_DATETIME`; `$.party[*].signed_at`: `DATE_ONLY` (без `is_polymorphic_format`); форматы и аномалии веток независимы |
| `obj_json_object_or_array` | `meta` — JSON-строка: `i%2==0` объект `{"u":"a"}`, `i%2==1` массив `[{"k":1}]` | как слева | `$.meta`: `VARCHAR`, `is_json_string`, `is_polymorphic_structure` (формы `JSON_OBJECT`, `ARRAY`); `$.meta.u`: `VARCHAR`; `$.meta[*]`: `ARRAY`; `$.meta[*].k`: `INTEGER` |
| `obj_json_array_elements` | все строки одинаковы | `list=["{\"a\":1}","{\"a\":2}"]` (массив JSON-строк) | `$.list[*]`: `VARCHAR`, `is_json_string`, **нет** `is_array_empty` и `is_flat_string_array`; `$.list[*].a`: `INTEGER` |
| `obj_json_with_plain` | `meta`: `i%2==0` JSON-строка `{"u":"a"}`; `i%4==1` `""`; `i%4==3` `"n/a"` | как слева | `$.meta`: `VARCHAR`, `is_json_string`, `has_non_json_strings`, `is_polymorphic_structure` (формы `JSON_OBJECT`, `SCALAR`); `$.meta.u`: `VARCHAR`. Генератор по `has_non_json_strings` downstream-модель не строит |
| `obj_json_with_native_object` | `meta`: `i%2==0` нативный объект `{"u":"a"}`; `i%2==1` JSON-строка с тем же объектом | как слева | `$.meta`: `VARCHAR`, `is_json_string`, `is_polymorphic_structure` (формы `OBJECT`, `JSON_OBJECT`); нет `has_non_json_strings`; `$.meta.u`: `VARCHAR` |
| `obj_json_false_alarm` | `t`: `i%2==0` `[TEST]`, `i%2==1` `{abc}` (текст, лишь похожий на JSON) | как слева | `$.t`: `VARCHAR`, без аномалий; **нет** путей `$.t[*]` и `$.t.*`, нет `is_json_string` |
| `obj_bson_id_forms` | `_id`: `i%2==0` обёртка `{"$oid":"…"}`; `i%2==1` строка с тем же значением | заменяет `_id` базовой записи | листья `$._id` и `$._id.$oid`: `VARCHAR`, без аномалий; **нет** записи `OBJECT` и `is_polymorphic_structure` (обёртка — не форма `OBJECT`); сведение в одну колонку — задача генератора (`coalesce`) |

Для `obj_object_or_array` и `obj_object_or_array_formats` обязателен unit-кейс на отдельных строках
(объект → массив → объект): отчёт не зависит от порядка строк и разбиения на воркеры.

## 2. Комбинационные таблицы

Группы внутри таблицы выбираются по `index`, а не `random`.

| Таблица | Режим | Группы по `index` | Ожидаемый report |
|---|---|---|---|
| `pol_created_at_formats` | `pol_created_at_formats` | `created_at`: `i%3==0` local, `1` date-only, `2` offset | `$.created_at`: `observed_formats=[DATE_ONLY, LOCAL_DATETIME, OFFSET_DATETIME]`, `is_polymorphic_format`, `type=VARCHAR`, `max_length=25` |
| `pol_created_at_with_arrays` | `pol_created_at_with_arrays` | `created_at` как выше; `birth_date` parts при `i%2==1`; `payment_dates=[]` при `i%5==0` | как выше + `$.birth_date`: `is_polymorphic_structure` (формы `SCALAR`, `ARRAY`); `$.birth_date[*]`: `is_date_part_array`, `is_flat_string_array`; `$.payment_dates[*]`: `is_array_empty`, `is_flat_string_array`, `DATE_ONLY`; в trace-режиме у каждой аномалии свой `trace`, независимый от `path_trace` |
| `pol_created_at_with_rating` | `pol_created_at_with_rating` | `created_at` как выше; `customer_rating` `DOUBLE` при `i%2==1` | как в `pol_created_at_formats` + `$.customer_rating`: `type=DOUBLE`, нет `observed_formats` и `anomalies` |
| `trace_mixed_sources` | `trace_mixed_sources` | id-источник: `i%4==0` `_id.$oid`, `1` `id` (без `_id`), `2` `order_id` (без `_id`/`id`), `3` без id | базовая запись + `$.id`, `$.order_id`: `VARCHAR`; trace — раздел 3 |

## 3. Trace-ожидания

Проверяются запросом `analyze_json_schema(line, trace())`. В обычном режиме ни в одной
таблице нет `path_trace` и `anomalies.*.trace`.

| Таблица | Scope | Ожидание |
|---|---|---|
| любая | `path_trace` каждой записи пути (включая записи неоднородных полей); `trace` каждой аномалии, в том числе `is_json_string` — той же формы | 1…`max_ids` пар; сортировка по `(id, id_key)`; маркер `{"id":"","id_key":""}` не более одного и только в хвосте |
| `trace_mixed_sources` | все scopes | каждая пара согласована: префикс id задаёт ключ — `60b8…` ↔ `_id.$oid`, `id-…` ↔ `id`, `ord-…` ↔ `order_id`, `""` ↔ `""`. Ложная пара (id одного источника с ключом другого) — провал |
| `trace_mixed_sources` | merge | E2E на распределённом кластере: обычный отчёт совпадает с одноузловым; в trace-отчёте все пары согласованы, `$.id`/`$.order_id`/`$._id.$oid` несут id только своего источника. Значения id не сравниваются: scope хранит первое появление факта на воркере, поэтому набор id зависит от разбиения на сплиты (алгебра merge — unit `SchemaStateSerializerTest`) |
| `pol_created_at_with_arrays` | `anomalies.is_date_part_array.trace`, `anomalies.is_array_empty.trace` | id только из строк своей группы (`i%2==1` и `i%5==0` соответственно) |
| `pol_created_at_formats` | `anomalies.is_polymorphic_format.trace` | элементы с полем `format`; для каждого из трёх форматов 1…`max_ids` пар; id элемента принадлежит строке своей группы |
| `obj_object_or_array` | `path_trace` записей `$.party`, `$.party.name`, `$.party[*]`, `$.party[*].id` | независимы: `$.party`, `$.party.*` — id только из строк `i%2==0`, `$.party[*]`, `$.party[*].*` — только из строк `i%2==1` |
| `obj_object_or_array` | `anomalies.is_polymorphic_structure.trace` | элементы с полем `form`: `OBJECT` — id из чётных строк, `ARRAY` — из нечётных; по каждой форме 1…`max_ids` пар |
| `pol_created_at_with_arrays` | `anomalies.is_polymorphic_structure.trace` у `$.birth_date` | `form=SCALAR` — id из строк `i%2==0`, `form=ARRAY` — из `i%2==1` |
| `obj_json_with_plain` | `has_non_json_strings.trace`, `is_json_string.trace` | `has_non_json_strings` — id только из строк с обычной строкой (`i%2==1`), `is_json_string` — из строк `i%2==0` |

## 4. `crm_combined` и golden-map

`crm_combined` (режим `all`, `count = 100`) — небольшая интеграционная таблица, а не
полная матрица. Режим `all` детерминирован: группа выбирается по `index`.

| Группа | Условие | Мутация |
|---|---|---|
| `created_at_local` | `i%4==0` | без изменений (`2026-07-23T01:15:00`) |
| `created_at_plain` | `i%4==1` | `2026-07-23` |
| `created_at_unix_millis` | `i%4==2` | `1784769300000` |
| `created_at_offset` | `i%4==3` | `2026-07-23T01:15:00+03:00` |
| `birth_date_parts` | `i%2==1` | `["1990","05","15"]` (в чётных строках остаётся скаляр: неоднородное поле) |
| `payment_dates_empty` | `i%3==0` | `[]` |
| `customer_rating` | `i%5 < 2` | `DOUBLE` |
| `surrogate_pk` | `i%2==0` | `sp-…` |
| `counterparties_shape` | `i%3==0` — массив `[{"share":…},…]`; иначе — объект `{"name":"…"}` | поле `counterparties` (неоднородное поле: объект / массив объектов) |

Expectation-map (обычный отчёт, `combined.rx.json` на стенде):

| Путь | `type` | `observed_formats` | `anomalies` |
|---|---|---|---|
| `$.created_at` | `VARCHAR` | `DATE_ONLY`, `LOCAL_DATETIME`, `OFFSET_DATETIME`, `UNIX_MILLIS` | `is_polymorphic_format` |
| `$.birth_date` | `VARCHAR` | `DATE_ONLY` | `is_polymorphic_structure` |
| `$.birth_date[*]` | `VARCHAR` | — | `is_date_part_array`, `is_flat_string_array` |
| `$.payment_dates[*]` | `VARCHAR` | `DATE_ONLY` | `is_array_empty`, `is_flat_string_array` |
| `$.customer_rating` | `DOUBLE` | — | — |
| `$.updated_at` | `VARCHAR` | `OFFSET_DATETIME` | — |
| `$.promo_expiry_date` | `VARCHAR` | `DATE_ONLY` | — |
| `$.surrogate_pk` | `VARCHAR` | — | — |
| `$.counterparties` | `OBJECT` | — | `is_polymorphic_structure` |
| `$.counterparties.name` | `VARCHAR` | — | — |
| `$.counterparties[*]` | `ARRAY` | — | — |
| `$.counterparties[*].share` | `INTEGER` | — | — |
| `$.version.$numberLong` | `INTEGER` | — | — |

Trace-отчёт (`combined.trace.rx.json` на стенде): у каждой аномалии из таблицы выше
есть `trace`; у `is_polymorphic_format` в `trace` встречаются все четыре значения
`format`; все пары имеют `id_key = "_id.$oid"`; `path_trace` есть у каждого пути.

Исполняет `FixtureMatrixTest` (полная карта путей и trace-форма по `fixtures.toml`) и
e2e (распределённый отчёт = одноузловой). Docker-стенд (`mise run lc-verify`/`lc-demo`)
отдельных golden-проверок не содержит: он для онбординга, проверки глазами и примеров
отчётов в документацию; обратная связь для агента — `./gradlew verify` (`AGENTS.md`).

## 5. Где проверяется

| Уровень | Что | Источник ожиданий |
|---|---|---|
| unit `FormatDetectorTest` | сканер, числа, hints | value-formats.md, раздел 6 |
| unit `FormatSettingsTest` | `[format]` + env | value-formats.md, раздел 3.1 |
| unit `JsonSchemaAnalyzerTest` | переходы и merge на отдельных строках режимов | этот документ |
| unit `FixtureMatrixTest` | разделы 0–4 (включая `obj_*`) на каждой таблице `fixtures.toml` (состав реестра, отчёт, trace) | этот документ |
| unit `RxDataSchemaTest` | отчёт валидируется схемой 3.0 (`OBJECT` в enum, `form` в trace, `schema_version` `3.x`) | docs/contracts/rx-data.schema.json |
| unit `SchemaStateSerializerTest` | алгебра merge, round-trip, пропуск чужих путей | docs/testing/tracing.md, docs/patterns/plugin.md (паттерн 5) |
| E2E `src/e2e/` | раздел 3 (`trace_mixed_sources` после шаффла; отчёт = одноузловой), раздел 4 in-process | этот документ |
| E2E `installSmokeTest` | загрузка плагина PluginManager-ом реального образа Trino (Docker) | docs/testing/reliability-matrix.md |
| Docker `lc-verify` (глазами) | раздел 4 через `fixtures.toml → lc-gen → lc-load → trino-cli` | этот документ |

## 6. Добавление нового формата или аномалии

Пакет обязателен целиком: элемент enum/константа → positive/negative cases detector-а
→ assertion в analyzer report → merge/serializer round-trip (если поле в state) →
атомарная таблица (если нужен ручной repro) → комбинация (только при взаимодействии
осей) → строка в этом документе → value-formats.md / контракт при изменении семантики
(ADR — только для нового значимого решения).

Смена версии контракта (major) делает красными все сравнения «набор путей / `schema_version`»:
ожидания разделов 0–4 обновляются синхронно с контрактом и схемой. Новая структурная форма
(например, массив массивов) — новая атомарная таблица оси `obj_`.
