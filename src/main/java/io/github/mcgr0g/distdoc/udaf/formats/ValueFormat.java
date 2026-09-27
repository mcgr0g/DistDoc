package io.github.mcgr0g.distdoc.udaf.formats;

/**
 * Закрытый словарь написаний значений ({@code observed_formats} в rx-data).
 *
 * <p>Формат фиксирует <b>написание</b> значения, а не момент времени: {@code +00:00} —
 * {@link #OFFSET_DATETIME}, {@code Z} — {@link #UTC_DATETIME}.</p>
 *
 * <p><b>Порядок объявления — часть контракта:</b> это приоритет SQL-подстановок генератора
 * и порядок сериализации {@code observed_formats}. Добавление элемента — minor-версия
 * схемы, изменение семантики существующего — major (docs/contracts/value-formats.md).</p>
 *
 * @see FormatDetector
 */
public enum ValueFormat {
    /** {@code VARCHAR} {@code YYYY-MM-DD}: {@code 2026-07-23}. */
    DATE_ONLY,
    /** {@code VARCHAR} {@code YYYY-MM-DDTHH:MM:SS[.f{1,9}]}: {@code 2026-07-23T01:15:00}. */
    LOCAL_DATETIME,
    /** {@code VARCHAR} local datetime + {@code ±HH:MM}: {@code 2026-07-23T01:15:00+03:00}. */
    OFFSET_DATETIME,
    /** {@code VARCHAR} local datetime + {@code Z}: {@code 2026-07-23T01:15:00Z}. */
    UTC_DATETIME,
    /** {@code INTEGER}/{@code DOUBLE} секунды от эпохи — только при контексте path hint. */
    UNIX_SECONDS,
    /** {@code INTEGER} (или цифровая строка под {@code $numberLong}) миллисекунды — при {@code $date} или path hint. */
    UNIX_MILLIS
}
