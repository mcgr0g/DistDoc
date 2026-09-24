package io.github.mcgr0g.distdoc.udaf.formats;

import io.github.mcgr0g.distdoc.udaf.formats.FormatDetector.Context;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.stream.Stream;

import static io.github.mcgr0g.distdoc.udaf.formats.ValueFormat.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Table-driven тесты {@link FormatDetector}. Таблицы повторяют приложение А
 * docs/adr/0006-format-detection.md (А.1 строки, А.2 числа, А.3 path hints):
 * при изменении правила меняются ADR и эта таблица одновременно.
 */
public class FormatDetectorTest {

    private static final List<String> HINTS = List.of("_at", "_utc");

    private record Case(String input, ValueFormat expected, String why) {}

    private static Case c(String input, ValueFormat expected, String why) {
        return new Case(input, expected, why);
    }

    // ------------------------------------------------------------------ А.1 строки

    @TestFactory
    Stream<DynamicTest> strings() {
        return Stream.of(
                c("2026-07-23", DATE_ONLY, "базовый positive"),
                c("2024-02-29", DATE_ONLY, "високосный год"),
                c("2000-02-29", DATE_ONLY, "високосный вековой (%400)"),
                c("1900-02-29", null, "невисокосный вековой (%100)"),
                c("2025-02-29", null, "невисокосный год"),
                c("2026-02-30", null, "несуществующий день"),
                c("2026-04-31", null, "30-дневный месяц"),
                c("2026-12-31", DATE_ONLY, "последний день года"),
                c("2026-00-10", null, "месяц 00"),
                c("2026-13-10", null, "месяц 13"),
                c("2026-07-00", null, "день 00"),
                c("2026-7-23", null, "нет ведущего нуля"),
                c("20260723", null, "basic ISO без разделителей"),
                c("2026/07/23", null, "чужой разделитель"),
                c("2026-07-2a", null, "буква в дне"),
                c("1990", null, "частица даты"),

                c("2026-07-23T01:15:00", LOCAL_DATETIME, "базовый positive"),
                c("2026-07-23T00:00:00", LOCAL_DATETIME, "граница 00:00:00"),
                c("2026-07-23T23:59:59", LOCAL_DATETIME, "граница 23:59:59"),
                c("2026-07-23T01:15:00.1", LOCAL_DATETIME, "дробь 1 цифра"),
                c("2026-07-23T01:15:00.123", LOCAL_DATETIME, "дробь 3 цифры"),
                c("2026-07-23T01:15:00.123456789", LOCAL_DATETIME, "дробь 9 цифр"),
                c("2026-07-23T01:15:00.", null, "точка без цифр"),
                c("2026-07-23T01:15:00.1234567890", null, "дробь 10 цифр"),
                c("2026-07-23T24:00:00", null, "час 24"),
                c("2026-07-23T01:60:00", null, "минута 60"),
                c("2026-07-23T01:15:60", null, "leap second"),
                c("2026-13-23T01:15:00", null, "календарь в datetime"),
                c("2025-02-29T01:15:00", null, "29 февраля невисокосного в datetime"),
                c("2026-07-23 01:15:00", null, "пробел вместо T"),
                c("2026-07-23t01:15:00", null, "строчная t"),
                c("2026-07-23T01:15", null, "время без секунд"),
                c("2026-07-23T01:15:00X", null, "мусорный суффикс"),

                c("2026-07-23T01:15:00+03:00", OFFSET_DATETIME, "базовый positive"),
                c("2026-07-23T01:15:00-05:30", OFFSET_DATETIME, "отрицательный offset"),
                c("2026-07-23T01:15:00+00:00", OFFSET_DATETIME, "нулевой offset ≠ Z"),
                c("2026-07-23T01:15:00+18:00", OFFSET_DATETIME, "граница +18:00"),
                c("2026-07-23T01:15:00-18:00", OFFSET_DATETIME, "граница -18:00"),
                c("2026-07-23T01:15:00+18:30", null, "|offset| > 18:00"),
                c("2026-07-23T01:15:00+99:00", null, "offset-час вне диапазона"),
                c("2026-07-23T01:15:00+03:60", null, "offset-минута 60"),
                c("2026-07-23T01:15:00+0300", null, "offset без двоеточия"),
                c("2026-07-23T01:15:00+03", null, "offset без минут"),
                c("2026-07-23T01:15:00+03:00:00", null, "offset с секундами"),
                c("2026-07-23T01:15:00.5+03:00", OFFSET_DATETIME, "дробь + offset"),
                c("2026-07-23T01:15:00.123456789+03:00", OFFSET_DATETIME, "максимальная длина 35"),

                c("2026-07-23T01:15:00Z", UTC_DATETIME, "базовый positive"),
                c("2026-07-23T01:15:00.123Z", UTC_DATETIME, "дробь + Z"),
                c("2026-07-23T24:00:00Z", null, "час 24"),
                c("2026-07-23T01:15:00z", null, "строчная z"),
                c("2026-07-23T01:15:00+00:00Z", null, "двойной суффикс"),
                c("2026-07-23T01:15:00ZZ", null, "двойной Z"),

                c("", null, "пустая строка"),
                c("deal", null, "обычная строка"),
                c("Mozilla", null, "обычная строка"),
                c("sp-0000000001", null, "surrogate_pk"),
                c("60b8d29f1a4c8b0000000001", null, "$oid"),
                c("1784769300000", null, "цифровая строка без $numberLong")
        ).map(tc -> dynamicTest(tc.input() + " → " + tc.expected() + " (" + tc.why() + ")",
                () -> assertEquals(tc.expected(), FormatDetector.detectString(tc.input()), tc.why())));
    }

    @Test
    void nullStringIsNotFormat() {
        assertNull(FormatDetector.detectString(null));
    }

    // ------------------------------------------------------------------ А.2 числа

    private record NumCase(String name, ValueFormat actual, ValueFormat expected) {}

    @TestFactory
    Stream<DynamicTest> numbers() {
        return Stream.of(
                new NumCase("1784769300 hint → seconds", FormatDetector.detectInteger(1784769300L, Context.HINT), UNIX_SECONDS),
                new NumCase("1784769300000 hint → millis", FormatDetector.detectInteger(1784769300000L, Context.HINT), UNIX_MILLIS),
                new NumCase("1784769300 без контекста — id/счётчик", FormatDetector.detectInteger(1784769300L, Context.NONE), null),
                new NumCase("1784769300000 без контекста", FormatDetector.detectInteger(1784769300000L, Context.NONE), null),
                new NumCase("-86400 hint → 1969-12-31", FormatDetector.detectInteger(-86400L, Context.HINT), UNIX_SECONDS),
                new NumCase("-1262304000 hint → 1930-01-01", FormatDetector.detectInteger(-1262304000L, Context.HINT), UNIX_SECONDS),
                new NumCase("0 hint → эпоха", FormatDetector.detectInteger(0L, Context.HINT), UNIX_SECONDS),
                new NumCase("99999999999 hint → граница < 1e11", FormatDetector.detectInteger(99_999_999_999L, Context.HINT), UNIX_SECONDS),
                new NumCase("100000000000 hint → граница ≥ 1e11", FormatDetector.detectInteger(100_000_000_000L, Context.HINT), UNIX_MILLIS),
                new NumCase("-100000000000 hint → граница с минусом", FormatDetector.detectInteger(-100_000_000_000L, Context.HINT), UNIX_MILLIS),
                new NumCase("-99999999999 hint → seconds", FormatDetector.detectInteger(-99_999_999_999L, Context.HINT), UNIX_SECONDS),
                new NumCase("Long.MIN_VALUE hint → millis без переполнения", FormatDetector.detectInteger(Long.MIN_VALUE, Context.HINT), UNIX_MILLIS),
                // Слепая зона (ADR-0006 §3): millis 1971-01-01 распознаётся как seconds — поведение документировано
                new NumCase("31536000000 hint → СЛЕПАЯ ЗОНА: millis 1971 как seconds", FormatDetector.detectInteger(31_536_000_000L, Context.HINT), UNIX_SECONDS),
                new NumCase("1784769300.5 hint → дробные секунды", FormatDetector.detectDouble(1784769300.5, Context.HINT), UNIX_SECONDS),
                new NumCase("1784769300000.0 hint → дробные millis не поддержаны", FormatDetector.detectDouble(1784769300000.0, Context.HINT), null),
                new NumCase("-1.5 hint → дробные секунды до эпохи", FormatDetector.detectDouble(-1.5, Context.HINT), UNIX_SECONDS),
                new NumCase("NaN hint", FormatDetector.detectDouble(Double.NaN, Context.HINT), null),
                new NumCase("Infinity hint", FormatDetector.detectDouble(Double.POSITIVE_INFINITY, Context.HINT), null),
                new NumCase("4.5 без контекста — рейтинг/деньги", FormatDetector.detectDouble(4.5, Context.NONE), null),
                new NumCase("1784769300000.0 в $date — дробные millis", FormatDetector.detectDouble(1784769300000.0, Context.BSON_DATE), null),
                new NumCase("12345 без контекста — счётчик", FormatDetector.detectInteger(12345L, Context.NONE), null),
                new NumCase("7 без контекста — $numberLong версии", FormatDetector.detectInteger(7L, Context.NONE), null),
                new NumCase("1784769300000 в $date → millis", FormatDetector.detectInteger(1784769300000L, Context.BSON_DATE), UNIX_MILLIS),
                new NumCase("1784769300 в $date → millis, порог не применяется", FormatDetector.detectInteger(1784769300L, Context.BSON_DATE), UNIX_MILLIS)
        ).map(tc -> dynamicTest(tc.name(), () -> assertEquals(tc.expected(), tc.actual(), tc.name())));
    }

    // ------------------------------------------------------------------ строки с путём ($numberLong, $date)

    private record PathCase(String value, String path, ValueFormat expected, String why) {}

    @TestFactory
    Stream<DynamicTest> stringsWithPath() {
        return Stream.of(
                new PathCase("1784769300000", "$.created_at.$date.$numberLong", UNIX_MILLIS, "canonical Extended JSON"),
                new PathCase("-86400000", "$.created_at.$date.$numberLong", UNIX_MILLIS, "отрицательный canonical"),
                new PathCase("1784769300000", "$.doc.$date.$numberLong", UNIX_MILLIS, "$date без hint"),
                new PathCase("1784769300000", "$.updated_at.$numberLong", UNIX_MILLIS, "цифровая строка под $numberLong + hint"),
                new PathCase("1784769300", "$.updated_at.$numberLong", UNIX_SECONDS, "seconds под $numberLong + hint"),
                new PathCase("7", "$.version.$numberLong", null, "$numberLong без контекста"),
                new PathCase("1784769300000", "$.updated_at", null, "цифровая строка вне $numberLong — не число"),
                new PathCase("99999999999999999999", "$.created_at.$date.$numberLong", null, "вне диапазона long"),
                new PathCase("12a", "$.created_at.$date.$numberLong", null, "не цифры под $numberLong"),
                new PathCase("-", "$.created_at.$date.$numberLong", null, "только минус"),
                new PathCase("2026-07-23T01:15:00Z", "$.created_at.$date", UTC_DATETIME, "relaxed Extended JSON"),
                new PathCase("2026-07-23", "$.birth_date", DATE_ONLY, "строковым форматам hint не нужен")
        ).map(tc -> dynamicTest(tc.path() + " = \"" + tc.value() + "\" → " + tc.expected() + " (" + tc.why() + ")",
                () -> assertEquals(tc.expected(), FormatDetector.detectString(tc.value(), tc.path(), HINTS), tc.why())));
    }

    // ------------------------------------------------------------------ А.3 path hints

    private record HintCase(String path, String syllable, Context expected) {}

    @TestFactory
    Stream<DynamicTest> pathHints() {
        return Stream.of(
                new HintCase("$.created_at", "created_at", Context.HINT),
                new HintCase("$.created_at.$date.$numberLong", "created_at", Context.BSON_DATE),
                new HintCase("$.created_at.$date", "created_at", Context.BSON_DATE),
                new HintCase("$.updated_at.$numberLong", "updated_at", Context.HINT),
                new HintCase("$.events_at[*]", "events_at", Context.HINT),
                new HintCase("$.events_at[*][*]", "events_at", Context.HINT),
                new HintCase("$.meta.sync_utc", "sync_utc", Context.HINT),
                new HintCase("$.createdAt", "createdAt", Context.NONE),
                new HintCase("$.created_AT", "created_AT", Context.NONE),
                new HintCase("$.birth_date", "birth_date", Context.NONE),
                new HintCase("$.version.$numberLong", "version", Context.NONE),
                new HintCase("$._id.$oid", "_id", Context.NONE),
                new HintCase("$.format", "format", Context.NONE),
                new HintCase("$.chat", "chat", Context.NONE),
                new HintCase("$.created_at.retry_count", "retry_count", Context.NONE),
                new HintCase("$[*]", null, Context.NONE),
                new HintCase("$", null, Context.NONE)
        ).map(tc -> dynamicTest(tc.path() + " → " + tc.syllable() + " / " + tc.expected(), () -> {
            assertEquals(tc.syllable(), FormatDetector.lastSignificantSyllable(tc.path()));
            assertEquals(tc.expected(), FormatDetector.numericContext(tc.path(), HINTS));
        }));
    }

    @Test
    void customHintsReplacePreset() {
        // env-override (DISTDOC_FORMAT_PATH_HINTS) полностью заменяет пресет: camelCase включается явно
        List<String> custom = List.of("At", "_date");
        assertEquals(Context.HINT, FormatDetector.numericContext("$.createdAt", custom));
        assertEquals(Context.HINT, FormatDetector.numericContext("$.birth_date", custom));
        assertEquals(Context.NONE, FormatDetector.numericContext("$.created_at", custom));
        assertEquals(Context.NONE, FormatDetector.numericContext("$.created_at", List.of()));
    }

    @Test
    void parseLongBounds() {
        assertEquals(Long.MAX_VALUE, FormatDetector.parseLong("9223372036854775807"));
        assertEquals(Long.MIN_VALUE, FormatDetector.parseLong("9223372036854775808"), "переполнение — не разобрано");
        assertEquals(-42L, FormatDetector.parseLong("-42"));
        assertEquals(Long.MIN_VALUE, FormatDetector.parseLong(""));
        assertEquals(Long.MIN_VALUE, FormatDetector.parseLong("+42"), "знак + не поддерживается");
    }
}
