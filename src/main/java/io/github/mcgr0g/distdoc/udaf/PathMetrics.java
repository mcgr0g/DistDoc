package io.github.mcgr0g.distdoc.udaf;

import io.github.mcgr0g.distdoc.udaf.formats.ValueFormat;
import java.util.*;

/**
 * Мутабельный контейнер метрик и метаданных, собранных для конкретного JSONPath.
 *
 * <p>Класс аккумулирует информацию о типах данных, максимальной длине значений,
 * доказанных написаниях значений ({@code observed_formats}) и обнаруженных аномалиях.
 * Экземпляр {@code PathMetrics} создается для каждого уникального пути в рамках одного
 * сеанса интроспекции.</p>
 *
 * <p><b>Полиморфизм и сведение типов:</b></p>
 * <p>Если в процессе обработки документов на одном и том же пути встречаются разные
 * типы данных (например, число {@code 42} и строка {@code "active"}), класс сохраняет
 * оба типа в структуре {@code Set}. При вызове {@link #getFinalType()} числовое
 * смешение {INTEGER, DOUBLE} сводится к {@code DOUBLE} (расширение без потери данных),
 * любое смешение с {@code VARCHAR}/{@code BOOLEAN}/{@code ARRAY}/{@code OBJECT} — к {@code VARCHAR}.</p>
 *
 * <p><b>Монотонность:</b> форматы и аномалии только накапливаются — операции сужения нет.
 * Аномалия {@link #ANOMALY_POLYMORPHIC_FORMAT} вычисляется при детекте разных форматов на одном
 * jsonpath, в том числе на разных воркерах.</p>
 *
 * <p><b>Трассировка:</b> {@code path_trace}, каждая аномалия и каждый формат имеют
 * собственный {@link TraceEvidence} с независимым лимитом (docs/testing/tracing.md).</p>
 *
 * <p><b>Параллельная агрегация (Cluster Merge):</b></p>
 * <p>Метод {@link #merge(PathMetrics)} объединяет результаты воркеров Trino: длины — {@code max()},
 * типы, форматы и аномалии — union, trace evidence — {@link TraceEvidence#merge(TraceEvidence)}.</p>
 *
 * @see io.github.mcgr0g.distdoc.udaf.anomalies.ArrayAnomalyDetector
 * @see JsonSchemaAnalyzer
 */
public class PathMetrics {

    /** Аномалия полиморфизма форматов: на пути доказано ≥ 2 написаний ({@link ValueFormat}). */
    public static final String ANOMALY_POLYMORPHIC_FORMAT = "is_polymorphic_format";

    /**
     * Вычисляемая аномалия: на пути лежит строка с сериализованным JSON (объект или массив), разобранная рекурсивно
     * ({@link #TYPE_JSON_STRING} в наборе типов). Не хранится, вычисляется при сборке отчёта (раздел 3 контракта).
     */
    public static final String ANOMALY_JSON_STRING = "is_json_string";

    /** Вычисляемая аномалия: рядом с JSON-строками на пути есть обычные строки (разбор содержимого небезопасен). */
    public static final String ANOMALY_NON_JSON_STRINGS = "has_non_json_strings";

    /** Вычисляемая аномалия: на пути сосуществуют ≥ 2 форм (скаляр / объект / объект-в-строке / массив), раздел 2b контракта. */
    public static final String ANOMALY_POLYMORPHIC_STRUCTURE = "is_polymorphic_structure";

    /** Нативный объект на пути (не BSON-обёртка). В отчёт попадает типом только у неоднородного поля. */
    public static final String TYPE_OBJECT = "OBJECT";
    /** Внутренний тип: на пути строка с валидным JSON-контейнером (в отчёте — {@code VARCHAR}). */
    public static final String TYPE_JSON_STRING = "JSON_STRING";
    /** Внутренний тип: содержимое JSON-строки на пути — объект (в отчёте — {@code VARCHAR}). */
    public static final String TYPE_JSON_OBJECT = "JSON_OBJECT";
    public static final String TYPE_VARCHAR = "VARCHAR";
    public static final String TYPE_ARRAY = "ARRAY";

    /** Скалярные типы (форма {@code SCALAR}). */
    public static final Set<String> SCALAR_TYPES = Set.of("VARCHAR", "INTEGER", "DOUBLE", "BOOLEAN");

    /** Все имена, допустимые в наборе типов (whitelist состояния между воркерами). */
    public static final Set<String> KNOWN_TYPES =
            Set.of("VARCHAR", "INTEGER", "DOUBLE", "BOOLEAN", "ARRAY", "OBJECT", "JSON_STRING", "JSON_OBJECT");

    /**
     * Набор всех уникальных типов данных, зафиксированных на данном пути. Передаётся между воркерами целиком
     * (а не свёрнутым {@link #getFinalType()}): форма, увиденная одним воркером, не теряется при слиянии.
     */
    private final Set<String> types = new TreeSet<>();

    /** Максимальная длина строкового представления значения в байтах/символах. */
    private long maxLength = 0;

    /** Доказанные написания значений (порядок итерации — порядок объявления enum). */
    private final EnumSet<ValueFormat> formats = EnumSet.noneOf(ValueFormat.class);

    /** Имена обнаруженных аномалий (только зафиксированные, отсортированы). */
    private final TreeSet<String> anomalies = new TreeSet<>();

    /** Документы, в которых путь встретился впервые (trace-режим); {@code null} — trace не собирался. */
    private TraceEvidence pathTrace = null;

    /** Документы, впервые давшие конкретный тип на пути (trace-режим); источник trace производных аномалий. */
    private final TreeMap<String, TraceEvidence> typeTrace = new TreeMap<>();

    /** Документы, на которых впервые зафиксирована конкретная аномалия (trace-режим). */
    private final TreeMap<String, TraceEvidence> anomalyTrace = new TreeMap<>();

    /** Документы, впервые давшие конкретный формат (trace-режим); источник trace полиморфизма. */
    private final EnumMap<ValueFormat, TraceEvidence> formatTrace = new EnumMap<>(ValueFormat.class);

    /**
     * Регистрирует тип данных, встреченный на текущем пути.
     *
     * @param type наименование типа (например, {@code "VARCHAR"}, {@code "INTEGER"}, внутренний {@code "JSON_STRING"})
     * @return {@code true}, если тип на пути новый
     */
    public boolean addType(String type) {
        return type != null && this.types.add(type);
    }

    /** @return неизменяемый вид набора типов пути (включая внутренние) */
    public Set<String> getTypes() {
        return Collections.unmodifiableSet(types);
    }

    /** @return {@code true}, если на пути встретилось скалярное значение (форма {@code SCALAR}) */
    public boolean hasScalar() {
        return types.stream().anyMatch(SCALAR_TYPES::contains);
    }

    /**
     * Вычисляет итоговый тип данных для генерации dbt-модели с учетом полиморфизма.
     *
     * @return {@code "UNKNOWN"} — если данных не было;<br>
     *         строгое имя типа (например, {@code "INTEGER"}) — если тип однороден;<br>
     *         {@code "DOUBLE"} — если зафиксировано числовое смешение {INTEGER, DOUBLE}
     *         (расширение без потери данных);<br>
     *         {@code "VARCHAR"} — при любом другом смешении типов (полиморфизм), в том числе
     *         {@code "OBJECT"} со скаляром на одном пути.
     */
    public String getFinalType() {
        if (types.isEmpty()) return "UNKNOWN";
        // Внутренние типы JSON-строки в отчёте — физический VARCHAR
        Set<String> reported = new TreeSet<>();
        for (String t : types) {
            reported.add(TYPE_JSON_STRING.equals(t) || TYPE_JSON_OBJECT.equals(t) ? TYPE_VARCHAR : t);
        }
        if (reported.size() == 1) return reported.iterator().next();
        boolean allNumeric = reported.stream().allMatch(t -> "INTEGER".equals(t) || "DOUBLE".equals(t));
        return allNumeric ? "DOUBLE" : "VARCHAR";
    }

    /**
     * Обновляет максимальную длину значения. Новое значение фиксируется
     * только в том случае, если оно строго больше предыдущего максимума.
     *
     * @param length длина текущего обработанного значения
     */
    public void updateLength(long length) {
        if (length > this.maxLength) {
            this.maxLength = length;
        }
    }

    /**
     * Возвращает максимальную зафиксированную длину значения для этого пути.
     * Используется dbt для оптимизации выделения памяти под VARCHAR-колонки.
     *
     * @return максимальная длина в виде long
     */
    public long getMaxLength() {
        return maxLength;
    }

    /**
     * Фиксирует доказанное написание значения на пути.
     *
     * @param format формат ({@code null} игнорируется)
     * @return {@code true}, если формат на пути новый
     */
    public boolean addFormat(ValueFormat format) {
        return format != null && formats.add(format);
    }

    /**
     * Доказанные написания пути в порядке объявления {@link ValueFormat}.
     *
     * @return неизменяемый вид множества форматов
     */
    public Set<ValueFormat> getFormats() {
        return Collections.unmodifiableSet(formats);
    }

    /**
     * Фиксирует аномалию. Операции сброса нет: пул аномалий монотонно возрастает.
     *
     * @param name уникальное имя аномалии в стиле {@code snake_case} с префиксом {@code is_}/{@code has_}
     * @return {@code true}, если аномалия на пути новая
     */
    public boolean markAnomaly(String name) {
        return anomalies.add(name);
    }

    /**
     * Проверяет, была ли зафиксирована аномалия (включая вычисляемую {@link #ANOMALY_POLYMORPHIC_FORMAT}).
     *
     * @param name уникальное имя аномалии
     * @return {@code true}, если аномалия зафиксирована
     */
    public boolean hasAnomaly(String name) {
        return switch (name) {
            case ANOMALY_POLYMORPHIC_FORMAT -> isPolymorphicFormat();
            case ANOMALY_JSON_STRING -> isJsonString();
            case ANOMALY_NON_JSON_STRINGS -> hasNonJsonStrings();
            default -> anomalies.contains(name);
        };
    }

    /** @return {@code true}, если на пути встретилась строка с валидным JSON-контейнером */
    public boolean isJsonString() {
        return types.contains(TYPE_JSON_STRING);
    }

    /** @return {@code true}, если рядом с JSON-строками на пути есть обычные строки */
    public boolean hasNonJsonStrings() {
        return types.contains(TYPE_JSON_STRING) && types.contains(TYPE_VARCHAR);
    }

    /**
     * Хранимые аномалии пути (без вычисляемых: {@link #ANOMALY_POLYMORPHIC_FORMAT}, {@link #ANOMALY_JSON_STRING},
     * {@link #ANOMALY_NON_JSON_STRINGS}, {@link #ANOMALY_POLYMORPHIC_STRUCTURE}), отсортированы по имени.
     *
     * @return неизменяемый вид множества имён
     */
    public Set<String> getAnomalies() {
        return Collections.unmodifiableSet(anomalies);
    }

    /** @return {@code true}, если на пути доказано два и более написаний */
    public boolean isPolymorphicFormat() {
        return formats.size() >= 2;
    }

    /**
     * Добавляет пару строки-источника в {@code path_trace}.
     *
     * @param id    идентификатор строки-источника
     * @param idKey имя поля-источника
     */
    public void addPathTrace(String id, String idKey) {
        if (pathTrace == null) {
            pathTrace = new TraceEvidence();
        }
        pathTrace.add(id, idKey);
    }

    /**
     * Добавляет пару строки-источника в trace конкретной аномалии.
     *
     * @param anomaly имя аномалии
     * @param id      идентификатор строки-источника
     * @param idKey   имя поля-источника
     */
    public void addAnomalyTrace(String anomaly, String id, String idKey) {
        anomalyTrace.computeIfAbsent(anomaly, k -> new TraceEvidence()).add(id, idKey);
    }

    /**
     * Добавляет пару строки-источника в trace конкретного формата.
     *
     * @param format формат
     * @param id     идентификатор строки-источника
     * @param idKey  имя поля-источника
     */
    public void addFormatTrace(ValueFormat format, String id, String idKey) {
        formatTrace.computeIfAbsent(format, k -> new TraceEvidence()).add(id, idKey);
    }

    /**
     * Добавляет пару строки-источника в trace конкретного типа пути.
     *
     * @param type  имя типа из набора {@link #getTypes()}
     * @param id    идентификатор строки-источника
     * @param idKey имя поля-источника
     */
    public void addTypeTrace(String type, String id, String idKey) {
        typeTrace.computeIfAbsent(type, k -> new TraceEvidence()).add(id, idKey);
    }

    /** @return trace по типам (тип → evidence), отсортирован по имени типа */
    public Map<String, TraceEvidence> getTypeTrace() {
        return Collections.unmodifiableMap(typeTrace);
    }

    /** @return trace появления пути или {@code null}, если trace не собирался */
    public TraceEvidence getPathTrace() {
        return pathTrace;
    }

    /** @return trace по аномалиям (имя → evidence), отсортирован по имени */
    public Map<String, TraceEvidence> getAnomalyTrace() {
        return Collections.unmodifiableMap(anomalyTrace);
    }

    /** @return trace по форматам (формат → evidence) в порядке объявления enum */
    public Map<ValueFormat, TraceEvidence> getFormatTrace() {
        return Collections.unmodifiableMap(formatTrace);
    }

    /**
     * Объединяет текущие метрики с метриками, прилетевшими с другого узла кластера.
     * Реализует математику ассоциативного, коммутативного и идемпотентного слияния.
     *
     * @param other метрики того же JSONPath, собранные на удаленном воркере Trino
     */
    public void merge(PathMetrics other) {
        if (other == null) return;

        this.types.addAll(other.types);
        this.maxLength = Math.max(this.maxLength, other.maxLength);
        this.formats.addAll(other.formats);
        this.anomalies.addAll(other.anomalies);

        if (other.pathTrace != null) {
            if (this.pathTrace == null) {
                this.pathTrace = new TraceEvidence();
            }
            this.pathTrace.merge(other.pathTrace);
        }
        other.anomalyTrace.forEach((name, evidence) ->
                this.anomalyTrace.computeIfAbsent(name, k -> new TraceEvidence()).merge(evidence));
        other.formatTrace.forEach((format, evidence) ->
                this.formatTrace.computeIfAbsent(format, k -> new TraceEvidence()).merge(evidence));
        other.typeTrace.forEach((type, evidence) ->
                this.typeTrace.computeIfAbsent(type, k -> new TraceEvidence()).merge(evidence));
    }
}
