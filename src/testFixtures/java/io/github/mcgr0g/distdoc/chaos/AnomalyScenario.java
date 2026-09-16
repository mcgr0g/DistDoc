package io.github.mcgr0g.distdoc.chaos;


/**
 * Сценарии генерации структурных аномалий для проверки DQ (Data Quality) в DWH.
 */
public enum AnomalyScenario {
    CLEAN,             // Идеальное состояние данных (схема без багов)
    EMPTY_ARRAY,       // Краевой сценарий: только пустые массивы
    DATE_AS_ARRAY,     // Краевой сценарий: только разорванные компоненты дат
    DATE_AT_UNIX,      // Краевой сценарий: только Unix-timestamp в миллисекундах
    DATE_AS_PLAIN,     // Краевой сценарий: дата как обычная строка YYYY-MM-DD
    ALL                // Комплексный полиморфный хаос (все edge-кейсы вместе)
}
