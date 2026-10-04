package io.github.mcgr0g.distdoc.chaos;


/**
 * Сценарии генерации структурных аномалий для проверки DQ (Data Quality) в DWH.
 *
 * <p>Значение {@code type} в {@code fixtures.toml} — имя константы в нижнем регистре.
 * Что каждая таблица должна показать в отчёте — docs/testing/fixture-matrix.md.
 * Группы строк внутри режима выбираются по {@code index}, не случайно.</p>
 */
public enum AnomalyScenario {
    CLEAN,             // Идеальное состояние данных (базовая запись без мутаций)
    EMPTY_ARRAY,       // Атомарный: только пустые массивы payment_dates
    DATE_AS_ARRAY,     // Атомарный: только разорванные компоненты дат в birth_date
    DATE_AT_UNIX,      // Атомарный: только Unix-timestamp в миллисекундах в created_at
    DATE_AS_PLAIN,     // Атомарный: дата как обычная строка YYYY-MM-DD в created_at
    RATING_PROMOTION,  // Атомарный: customer_rating INTEGER (чётные) / DOUBLE (нечётные)
    POL_CREATED_AT_FORMATS,     // Комбинация: три написания created_at по i%3
    POL_CREATED_AT_WITH_ARRAYS, // Комбинация: написания created_at + аномалии массивов
    POL_CREATED_AT_WITH_RATING, // Комбинация: написания created_at + подъём customer_rating
    OBJ_NESTED_PLAIN,           // Структура: вложенные объекты profile и пустой {}
    OBJ_ARRAY_OF_OBJECTS,       // Структура: массив объектов items (с вложенным объектом dims)
    OBJ_OBJECT_OR_ARRAY,        // Структура: party — объект (i%2==0) или массив объектов (i%2==1)
    OBJ_OBJECT_OR_SCALAR,       // Структура: contact — объект (i%2==0) или строка "n/a"
    OBJ_OBJECT_OR_ARRAY_FORMATS, // Структура × формат: party как выше + signed_at разного написания по ветке
    TRACE_MIXED_SOURCES,        // Trace: id-источник по i%4 (_id.$oid / id / order_id / нет)
    ALL                // Интеграционный crm_combined: все группы вместе, по index
}
