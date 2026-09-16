# 0005 E2E sourceSet isolation

Status: accepted  
Date: 2026-09-16

## Context and Problem Statement

E2E-тесты требуют `trino-testing` (реальный движок Trino) для проверки интеграции с координатором, воркерами и распределённым combine. Но `trino-testing` подтягивает:
- guava 33.6.0-jre (вместо продовой 33.4.0-jre)
- jackson 2.21.3 (вместо продовой 2.18.2)
- 387 jar в classpath (вместо 30 jar в unit-тестах)

Если добавить `trino-testing` в `testImplementation`, unit-тесты начнут выполняться на Trino-версиях guava/jackson — это тихая смена рантайма под уже зелёными тестами. Unit-тесты должны проверять продовой плагин, а не плагин на чужом classpath.

## Decision Drivers

- Unit-тесты (`src/test/`) должны выполняться на продовых версиях библиотек (guava 33.4.0, jackson 2.18.2)
- E2E-тесты (`src/e2e/`) требуют `trino-testing` для in-process кластера
- Изоляция classpath между unit и e2e
- Разные JVM-флаги (unit: без флагов, e2e: `--add-modules=jdk.incubator.vector`, `-Xmx3g`)

## Considered Options

### 1. Теги `@Tag("e2e")` в `src/test/`
**Плюсы**: Стандартный подход JUnit 5, нет отдельного каталога  
**Минусы**: Classpath общий — `trino-testing` попадёт в `testRuntimeClasspath`, unit-тесты увидят guava 33.6.0  
**Вердикт**: ❌ Отвергнуто — не решает проблему изоляции classpath

### 2. Отдельный подпроект (multi-module Gradle)
**Плюсы**: Полная изоляция, каждый подпроект — отдельный build.gradle  
**Минусы**: Избыточно для 4 тестовых классов (13 тестов), усложняет навигацию  
**Вердикт**: ❌ Отвергнуто — инженерный оверкилл

### 3. Отдельный sourceSet `e2e` (выбрано)
**Плюсы**:
- Полная изоляция classpath: `testRuntimeClasspath` (30 jar, guava 33.4.0) vs `e2eRuntimeClasspath` (387 jar, guava 33.6.0)
- Разные JVM-флаги через `tasks.register('e2eTest', Test)`
- Один build.gradle, простая навигация
- Gradle convention: `src/e2e/java`, `src/e2e/resources`

**Минусы**:
- Дополнительная конфигурация в build.gradle (~30 строк)
- Нестандартная структура каталогов (но документирована)

**Вердикт**: ✅ Принято

## Decision

Создать отдельный sourceSet `e2e` с изоляцией classpath.

Ниже — сокращённый фрагмент, полный код см. в `build.gradle` (секция "E2E SOURCE SET"):

```gradle
sourceSets {
    e2e {
        compileClasspath += sourceSets.main.output + sourceSets.testFixtures.output
        runtimeClasspath += sourceSets.main.output + sourceSets.testFixtures.output
        resources {
            srcDir 'docs/contracts'  // rx-data.schema.json
        }
    }
}

configurations {
    e2eImplementation.extendsFrom(testFixturesImplementation)
}

dependencies {
    e2eImplementation "io.trino:trino-testing:${trinoVersion}"
    e2eImplementation "io.trino:trino-memory:${trinoVersion}"
    e2eImplementation "io.trino:trino-spi:${trinoVersion}"
    e2eImplementation platform('org.junit:junit-bom:5.10.2')
    e2eImplementation 'org.junit.jupiter:junit-jupiter-api'
    e2eRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine'
    e2eRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    e2eImplementation "com.networknt:json-schema-validator:1.5.6"
    e2eImplementation 'org.testcontainers:testcontainers:1.20.4'
    e2eImplementation 'org.testcontainers:junit-jupiter:1.20.4'
}

tasks.register('e2eTest', Test) {
    group = 'verification'
    description = 'E2E-тесты in-process: реальный движок Trino + реальный шаффл + упаковка'
    testClassesDirs = sourceSets.e2e.output.classesDirs
    classpath = sourceSets.e2e.runtimeClasspath
    jvmArgs '--add-modules=jdk.incubator.vector', '-Xmx3g'
    systemProperty 'java.io.tmpdir', layout.buildDirectory.dir('tmp/e2e').get().asFile.absolutePath
    dependsOn tasks.named('deployPlugin')
}

tasks.named('check') {
    dependsOn tasks.named('e2eTest')
}
```

## Consequences

### Положительные
- Unit-тесты остаются изолированными: guava 33.4.0, jackson 2.18.2, 30 jar
- E2E-тесты получают `trino-testing`: guava 33.6.0, jackson 2.21.3, 387 jar
- Разные JVM-флаги и временные каталоги
- Reliability matrix работает: матрица 481/482/483 проверена локально и в CI

### Отрицательные
- Дополнительная конфигурация в build.gradle
- Разработчик должен знать про `src/e2e/` (документировано в docs/testing/reliability-matrix.md)

### Нейтральные
- E2E-тесты используют in-memory генерацию (`ChaosDataGenerator.generateSingleLine()`), а не файловый путь через `lc-gen → lc-load` — это осознанный выбор для скорости (~8-10 сек vs ~60 сек)

## References

- docs/testing/reliability-matrix.md — полное описание структуры тестов
- docs/testing/fixtures.md — секция про E2E-тесты (строки 18-42)
