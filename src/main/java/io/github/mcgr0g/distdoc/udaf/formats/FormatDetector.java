package io.github.mcgr0g.distdoc.udaf.formats;

import java.util.List;

/**
 * Pure-детектор написаний значений (docs/adr/0006-format-detection.md).
 *
 * <p>Вызывается на hot path для каждого скаляра, поэтому без regex, {@code java.time},
 * исключений и аллокаций: строки — посимвольный сканер с целочисленной проверкой
 * календаря, числа — только при контексте пути ({@link Context}).</p>
 *
 * <p>Путь — строка JSONPath анализатора ({@code $.created_at.$date.$numberLong},
 * {@code $.events_at[*]}): слоги разделены точкой, развёртка массива {@code [*]}
 * приклеена к слогу без точки.</p>
 *
 * <p>Отрицательный результат ({@code null}) означает «формат не доказан», а не «точно не дата».</p>
 */
public final class FormatDetector {

    /** Порог единицы Unix-времени: {@code |v| ≥ 1e11} → миллисекунды, иначе секунды. */
    static final long MILLIS_THRESHOLD = 100_000_000_000L;

    private static final String BSON_DATE = "$date";
    private static final String BSON_NUMBER_LONG = "$numberLong";
    private static final String BSON_OID = "$oid";
    private static final String NUMBER_LONG_SUFFIX = "." + BSON_NUMBER_LONG;
    private static final String ARRAY_MARK = "[*]";

    /** Контекст, дающий право считать число Unix-временем. */
    public enum Context {
        /** Контекста нет — число не является доказательством даты. */
        NONE,
        /** Последний значимый слог пути оканчивается на path hint. */
        HINT,
        /** Значение лежит в {@code $date} или {@code $date.$numberLong} (Extended JSON): всегда миллисекунды. */
        BSON_DATE
    }

    private FormatDetector() {} // Запрещаем инстанцирование

    // ------------------------------------------------------------------ строки

    /**
     * Распознаёт строковое написание даты/времени.
     *
     * <ol>
     *   <li>отсечение по длине: 10 ({@code DATE_ONLY}) или 19–35 (datetime);</li>
     *   <li>посимвольный разбор: разделитель даты и времени — только {@code T}, дробь —
     *       {@code .} + 1–9 цифр, суффикс — пусто / {@code Z} / {@code ±HH:MM};</li>
     *   <li>календарь: месяц 01–12, день по месяцу с учётом високосности, час 00–23,
     *       минута и секунда 00–59, {@code |offset| ≤ 18:00}.</li>
     * </ol>
     *
     * @param s строковый скаляр (может быть {@code null})
     * @return формат или {@code null}, если написание не распознано
     */
    public static ValueFormat detectString(CharSequence s) {
        if (s == null) return null;
        int len = s.length();
        if (len == 10) {
            return isDate(s) ? ValueFormat.DATE_ONLY : null;
        }
        if (len < 19 || len > 35) return null;
        if (!isDate(s) || s.charAt(10) != 'T' || !isTime(s)) return null;

        int pos = 19;
        if (pos < len && s.charAt(pos) == '.') {
            int start = ++pos;
            while (pos < len && isDigit(s.charAt(pos))) pos++;
            int digits = pos - start;
            if (digits < 1 || digits > 9) return null;
        }
        if (pos == len) return ValueFormat.LOCAL_DATETIME;

        char c = s.charAt(pos);
        if (c == 'Z') {
            return pos + 1 == len ? ValueFormat.UTC_DATETIME : null;
        }
        if ((c == '+' || c == '-') && pos + 6 == len && isOffset(s, pos + 1)) {
            return ValueFormat.OFFSET_DATETIME;
        }
        return null;
    }

    /**
     * Распознаёт строковый скаляр с учётом пути: цифровая строка под {@code $numberLong}
     * (canonical Extended JSON, {@code {"$date":{"$numberLong":"1784769300000"}}})
     * трактуется как целое число; остальные строки — {@link #detectString(CharSequence)}.
     *
     * @param value строковый скаляр
     * @param path  JSONPath значения
     * @param hints действующие path hints
     * @return формат или {@code null}
     */
    public static ValueFormat detectString(CharSequence value, String path, List<String> hints) {
        if (value != null && path != null && path.endsWith(NUMBER_LONG_SUFFIX)) {
            long v = parseLong(value);
            if (v != Long.MIN_VALUE) {
                return detectInteger(v, numericContext(path, hints));
            }
        }
        return detectString(value);
    }

    // ------------------------------------------------------------------ числа

    /**
     * Распознаёт целое число как Unix-время. Без контекста формат не добавляется никогда.
     *
     * @param v   значение
     * @param ctx контекст пути
     * @return {@code UNIX_MILLIS} для {@code $date}; на hint — по порогу {@code 1e11}; иначе {@code null}
     */
    public static ValueFormat detectInteger(long v, Context ctx) {
        return switch (ctx) {
            case BSON_DATE -> ValueFormat.UNIX_MILLIS;
            case HINT -> (v >= MILLIS_THRESHOLD || v <= -MILLIS_THRESHOLD)
                    ? ValueFormat.UNIX_MILLIS : ValueFormat.UNIX_SECONDS;
            case NONE -> null;
        };
    }

    /**
     * Распознаёт дробное число как Unix-время: только секунды на пути с hint.
     * Дробные миллисекунды (в том числе внутри {@code $date}) не поддерживаются.
     *
     * @param v   значение
     * @param ctx контекст пути
     * @return {@code UNIX_SECONDS} при hint и {@code |v| < 1e11}; иначе {@code null}
     */
    public static ValueFormat detectDouble(double v, Context ctx) {
        if (ctx != Context.HINT || Double.isNaN(v)) return null;
        return Math.abs(v) < MILLIS_THRESHOLD ? ValueFormat.UNIX_SECONDS : null;
    }

    // ------------------------------------------------------------------ контекст пути

    /**
     * Определяет числовой контекст пути: {@code $date} / {@code $date.$numberLong} —
     * {@link Context#BSON_DATE}; последний значимый слог оканчивается на один из hints
     * (с учётом регистра) — {@link Context#HINT}; иначе {@link Context#NONE}.
     *
     * @param path  JSONPath значения
     * @param hints действующие path hints
     * @return контекст
     */
    public static Context numericContext(String path, List<String> hints) {
        if (path == null) return Context.NONE;
        int end = path.length();
        int start = segmentStart(path, end);
        if (segmentIs(path, start, end, BSON_DATE)) return Context.BSON_DATE;
        if (start > 0 && segmentIs(path, start, end, BSON_NUMBER_LONG)) {
            int prevEnd = start - 1;
            if (segmentIs(path, segmentStart(path, prevEnd), prevEnd, BSON_DATE)) return Context.BSON_DATE;
        }
        return hasHint(path, hints) ? Context.HINT : Context.NONE;
    }

    /**
     * Последний значимый слог пути: справа налево, пропуская служебные {@code $date},
     * {@code $numberLong}, {@code $oid} и суффиксы {@code [*]}. Для отладки и тестов;
     * hot path использует {@link #numericContext(String, List)} без аллокаций.
     *
     * @param path JSONPath
     * @return слог или {@code null}, если значимого слога нет (корень)
     */
    public static String lastSignificantSyllable(String path) {
        long bounds = significantBounds(path);
        return bounds < 0 ? null : path.substring((int) (bounds >>> 32), (int) bounds);
    }

    private static boolean hasHint(String path, List<String> hints) {
        long bounds = significantBounds(path);
        if (bounds < 0) return false;
        int start = (int) (bounds >>> 32);
        int end = (int) bounds;
        for (String hint : hints) {
            int from = end - hint.length();
            if (from >= start && path.regionMatches(from, hint, 0, hint.length())) {
                return true;
            }
        }
        return false;
    }

    /** Границы последнего значимого слога, упакованные {@code (start << 32) | end}; {@code -1} — нет слога. */
    private static long significantBounds(String path) {
        if (path == null) return -1;
        int end = path.length();
        while (end > 0) {
            int start = segmentStart(path, end);
            int e = end;
            while (e - start >= ARRAY_MARK.length() && path.startsWith(ARRAY_MARK, e - ARRAY_MARK.length())) {
                e -= ARRAY_MARK.length();
            }
            if (e - start == 1 && path.charAt(start) == '$') return -1; // дошли до корня
            if (e > start
                    && !segmentIs(path, start, e, BSON_DATE)
                    && !segmentIs(path, start, e, BSON_NUMBER_LONG)
                    && !segmentIs(path, start, e, BSON_OID)) {
                return ((long) start << 32) | e;
            }
            if (start == 0) return -1;
            end = start - 1;
        }
        return -1;
    }

    private static int segmentStart(String path, int end) {
        return end == 0 ? 0 : path.lastIndexOf('.', end - 1) + 1;
    }

    private static boolean segmentIs(String path, int start, int end, String name) {
        return end - start == name.length() && path.startsWith(name, start);
    }

    // ------------------------------------------------------------------ примитивы сканера

    /** {@code YYYY-MM-DD} на позициях 0–9 + календарь. */
    private static boolean isDate(CharSequence s) {
        if (s.charAt(4) != '-' || s.charAt(7) != '-') return false;
        int hi = num2(s, 0);
        int lo = num2(s, 2);
        int month = num2(s, 5);
        int day = num2(s, 8);
        if (hi < 0 || lo < 0 || month < 1 || month > 12 || day < 1) return false;
        return day <= daysInMonth(hi * 100 + lo, month);
    }

    /** {@code HH:MM:SS} на позициях 11–18. Leap second {@code 60} отвергается. */
    private static boolean isTime(CharSequence s) {
        if (s.charAt(13) != ':' || s.charAt(16) != ':') return false;
        int h = num2(s, 11);
        int m = num2(s, 14);
        int sec = num2(s, 17);
        return h >= 0 && h <= 23 && m >= 0 && m <= 59 && sec >= 0 && sec <= 59;
    }

    /** {@code HH:MM} смещения, начиная с позиции {@code p} (после знака); {@code |offset| ≤ 18:00}. */
    private static boolean isOffset(CharSequence s, int p) {
        if (s.charAt(p + 2) != ':') return false;
        int h = num2(s, p);
        int m = num2(s, p + 3);
        if (h < 0 || h > 18 || m < 0 || m > 59) return false;
        return h < 18 || m == 0;
    }

    private static int daysInMonth(int year, int month) {
        return switch (month) {
            case 2 -> (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    /** Двузначное число на позициях {@code i, i+1}; {@code -1}, если не цифры. */
    private static int num2(CharSequence s, int i) {
        char a = s.charAt(i);
        char b = s.charAt(i + 1);
        if (!isDigit(a) || !isDigit(b)) return -1;
        return (a - '0') * 10 + (b - '0');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * Разбор {@code -?[0-9]{1,19}} в {@code long} без исключений.
     *
     * @return значение или {@link Long#MIN_VALUE}, если строка не целое число в диапазоне
     *         (само {@code Long.MIN_VALUE} тоже считается неразобранным — для Unix-времени неважно)
     */
    static long parseLong(CharSequence s) {
        int len = s.length();
        int i = 0;
        boolean negative = len > 0 && s.charAt(0) == '-';
        if (negative) i++;
        if (i == len || len - i > 19) return Long.MIN_VALUE;
        long acc = 0;
        for (; i < len; i++) {
            char c = s.charAt(i);
            if (!isDigit(c)) return Long.MIN_VALUE;
            int d = c - '0';
            if (acc > (Long.MAX_VALUE - d) / 10) return Long.MIN_VALUE;
            acc = acc * 10 + d;
        }
        return negative ? -acc : acc;
    }
}
