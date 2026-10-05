# Релизы и release candidates

Как публикуется DistDoc: что происходит автоматически, как выпустить release candidate (rc)
и что делать руками, когда автоматика не покрывает случай.

Воркфлоу — `.github/workflows/reliability-matrix.yml`. Актуальные ожидания по тестам —
`docs/testing/reliability-matrix.md`.

## 1. Как устроена публикация

Три job-а последовательно:

| Job | Что делает | Стоимость |
|---|---|---|
| `matrix-test` | `./gradlew test` + `e2eTest` на Trino 481/482/483 (in-process, без Docker) | ~30–40 сек |
| `install-smoke` | `./gradlew installSmokeTest` на каждой версии — реальный образ `trinodb/trino:<ver>` через Testcontainers | ~60–70 сек × 3 |
| `publish` | `releaseZip` на каждую версию → `releaseChecksums` → `gh release create` | ~1 мин |

- `publish` запускается только при push/merge в `main` **или** при ручном запуске
  (`workflow_dispatch`); на pull_request он отсекается условием.
- `permissions: contents: write` выдан точечно job-у `publish`, у остальных — только чтение.
  Секретов не требуется, используется штатный `GITHUB_TOKEN`.
- Тег — `v<distdocVersion>`. Ассеты: `trino-<Trino>-distdoc-<DistDoc>.zip` на каждую версию
  Trino, одна `distdoc-schema-<X.Y>.json` (major.minor от версии DistDoc) и `SHA256SUMS`.

**Версия берётся из `gradle.properties` выбранной ветки, а не из инпута и не из тега.**
Это ключ ко всему остальному: чтобы выпустить другую версию, её нужно закоммитить.

## 2. Стабильный релиз

Обычный путь — ничего вручную:

```bash
git checkout main && git pull
# distdocVersion в gradle.properties — та версия, которую публикуем
git push origin main          # publish отработает сам
```

Проверить результат:

```bash
gh release list --limit 5
gh release view v3.0.0 --json tagName,isPrerelease,targetCommitish,createdAt,assets
```

## 3. Release candidate

rc публикуется вручную из ветки, потому что версия читается из файла, а не приходит
параметром.

**Шаг 1 — бампнуть версию в ветке.** Номер с дефисом, semver-корректный:

```properties
# gradle.properties
distdocVersion=3.1.0-rc1
```

**Шаг 2 — закоммитить и запушить ветку.**

```bash
git push origin polimorph-objects
```

Push в не-main ветку не запускает воркфлоу — это нормально, публикация всё равно будет
только из шага 3.

**Шаг 3 — запустить вручную.**

CLI:

```bash
gh workflow run reliability-matrix.yml --ref polimorph-objects -f prerelease=true
```

WebUI: Actions → **Reliability Matrix & Release** → **Run workflow** → в «Use workflow from»
выбрать ветку → поставить галку `prerelease` → Run.

`-f` — обычная строка (`-F` дополнительно умеет `@file`, для нестроковых значений есть
`--json` с объектом). Галка `prerelease` объявлена как `type: boolean`, поэтому читается в
воркфлоу через `inputs.prerelease` — типизированно; `github.event.inputs.prerelease` всегда
строка, и `"false"` в `if` было бы истиной.

**Шаг 4 — проследить.**

```bash
gh run list --workflow=reliability-matrix.yml --limit 3
gh run watch <run-id>
```

**Шаг 5 — проверить пометку.**

```bash
gh release view v3.1.0-rc1 --json isPrerelease,tagName,targetCommitish
```

`isPrerelease: true` — rc не займёт место стабильного релиза: «Latest» определяется как самый
свежий **non-prerelease, non-draft** релиз, так что пометка автоматически прячет rc из шапки
репозитория.

### Почему пометка — инпут, а не автоопределение

`gh` не выводит prerelease из дефиса в теге: флаг `--prerelease` есть только явный
(в отличие от `--latest`, у которого дефолт — «автоматически по дате и версии»). Поэтому
пометка приходит галкой и пробрасывается в `gh release create`.

## 4. Грабли

**Тег должен не существовать.** Если тег уже есть, `publish` уходит в ветку
`gh release upload --clobber`: ассеты будут **заменены** сборкой из текущего запуска, а тег
останется на прежнем коммите. Проверить заранее:

```bash
git ls-remote --tags origin v3.1.0-rc1     # пусто — тега нет, можно публиковать
```

**Номер rc всегда поднимается.** Перевыпуск — это `-rc2`, а не повторный запуск `-rc1`:
`--clobber` не двигает тег и не меняет пометку.

**Пометку ставит только создание релиза.** `gh release upload --clobber` её не трогает — если
rc создался без галки, поправить руками (раздел 5).

**Версия не читается из git-тега.** Ни `gradle.properties` не знает про тег, ни сборка: тег —
следствие версии, а не источник. Поэтому «перевыпустить под новым тегом» без бампа версии
означает публикацию артефакта со старой версией внутри.

**Версия попадает в артефакт.** `distdocVersion` подставляется Gradle в
`src/main/resources/app-config.toml` (плейсхолдер `@distdocVersion@`, задача
`processResources`) и в имя jar. Значит, сборка rc и финального релиза при одинаковом
исходнике не байт-в-байт идентичны — это ожидаемо, но знать стоит.

**rc минора объявляет новую схему.** `3.0.1-rc1` → `schema_version: "3.0"` и
`distdoc-schema-3.0.json` (та же схема, что у финального 3.0.1). А вот `3.1.0-rc1` объявит
`"3.1"` и опубликует `distdoc-schema-3.1.json`. Потребитель по контракту сверяет только
**major**, так что ещё не финализированную минорную схему он примет.

**Тег rc указывает на коммит ветки.** `--target "${{ github.sha }}"` — это вершина выбранного
ref, поэтому rc-тег может стоять на коммите, которого нет в `main`.

**Воркфлоу должен быть в `main`.** Форма инпутов и кнопка «Run workflow» строятся из копии
файла на default branch; сам запуск выполняется из выбранного ref. Если воркфлоу с инпутом
`prerelease` ещё не в `main`, галки в WebUI не будет. Плюс: если файл в выбранном ref не
объявляет `workflow_dispatch`, API отвечает HTTP 422.

**Разветвление артефакта и ветки не проверяется.** Dispatch допускает любой ref, и версия
берётся из `gradle.properties` именно этого ref — то есть запуск с `main` опубликует версию
из `main`, а не из ветки, где вы правили.

**Стоимость rc полная.** Ручной запуск прогоняет всю матрицу: 3× unit/e2e и 3× Docker-smoke,
~5–7 минут. Пропустить её условием на `matrix-test` нельзя — пропуск прокаскадируется по
`needs` на `install-smoke` и на `publish`.

## 5. Ручные операции

```bash
# пометить / снять пометку у существующего релиза
gh release edit v3.1.0-rc1 --prerelease
gh release edit v3.1.0-rc1 --prerelease=false

# проверить, что получилось
gh release view v3.1.0-rc1 --json isPrerelease,tagName,targetCommitish,createdAt,assets

# перезалить ассеты у существующего релиза (тег не двигается)
gh release upload v3.1.0-rc1 build/libs/release/*.zip build/libs/release/SHA256SUMS --clobber

# собрать релизные архивы локально — то, что уйдёт в ассеты
./gradlew release -PtrinoVersion=481      # → build/libs/release/
```

Доступные JSON-поля `gh release view`: `isPrerelease`, `isDraft`, `tagName`, `targetCommitish`,
`createdAt`, `publishedAt`, `assets`, `body`, `name`, `url` и др.

## 6. Одно место хранения версии

`gradle.properties` → `distdocVersion` — единственный источник. Из него:

| Что | Как |
|---|---|
| `version` Gradle и имя jar | `version = distdocVer`, `trino-<trinoVer>-distdoc-<distdocVer>.jar` |
| `app.distdoc_version` | `processResources` подставляет в `src/main/resources/app-config.toml` |
| Версия схемы rx-data | `CoreSettings` сокращает до major.minor (`docs/contracts/rx-data-contract.md`) |
| Имя ассета схемы | `distdoc-schema-${VERSION%.*}.json` |
| Тег релиза | `v<distdocVersion>` |

## См. также

- `docs/testing/reliability-matrix.md` — что проверяют job-ы, локальные команды
- `AGENTS.md` — правила верификации перед сдачей
- `local/research/schema-versioning-and-release.md` — обоснование N zip + одной схемы
