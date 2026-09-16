package io.github.mcgr0g.distdoc.udaf;

import io.github.mcgr0g.distdoc.udaf.config.TraceSettings;
import java.util.*;

/**
 * Мутабельный контейнер метрик и метаданных, собранных для конкретного JSONPath.
 *
 * <p>Класс аккумулирует информацию о типах данных, максимальной длине значений
 * и обнаруженных структурных аномалиях. Экземпляр {@code PathMetrics} создается
 * для каждого уникального пути в рамках одного сеанса интроспекции.</p>
 *
 * <p><b>Полиморфизм и сведение типов:</b></p>
 * <p>Если в процессе обработки документов на одном и том же пути встречаются разные
 * типы данных (например, число {@code 42} и строка {@code "active"}), класс сохраняет
 * оба типа в структуре {@code Set}. При вызове {@link #getFinalType()} числовое
 * смешение {INTEGER, DOUBLE} сводится к {@code DOUBLE} (расширение без потери данных),
 * любое смешение с {@code VARCHAR}/{@code BOOLEAN}/{@code ARRAY} — к {@code VARCHAR}.</p>
 *
 * <p><b>Параллельная агрегация (Thread Safety & Cluster Merge):</b></p>
 * <p>Метод {@link #merge(PathMetrics)} обеспечивает корректное объединение результатов,
 * посчитанных параллельно на разных воркерах Trino. Длины объединяются через функцию
 * {@code max()}, множества типов сливаются через {@code addAll()}, а флаги аномалий
 * объединяются по правилу логического {@code OR} (если аномалия найдена хотя бы в одной
 * строке на любом сервере, она попадет в финальный отчет).</p>
 *
 * @see io.github.mcgr0g.distdoc.udaf.anomalies.ArrayAnomalyDetector
 * @see JsonSchemaAnalyzer
 */
public class PathMetrics {

    /** Набор всех уникальных типов данных, зафиксированных на данном пути. */
    private final Set<String> types = new TreeSet<>();

    /** Максимальная длина строкового представления значения в байтах/символах. */
    private long maxLength = 0;

    /** Динамическая карта флагов аномалий (Имя аномалии -> Наличие). */
    private final Map<String, Boolean> anomalies = new HashMap<>();

    /**
     * Идентификаторы строк-источников (trace_ids): строка, где путь встретился впервые,
     * и строки с зафиксированными аномалиями. Лимит и длина — из {@link TraceSettings}.
     */
    private final List<String> traceIds = new ArrayList<>();

    /**
     * Имя поля-источника trace_id (пресет/суффикс/explicit); {@code null} — не установлен,
     * {@code ""} — источник не найден (маркер неизвестного). Выбор при слиянии —
     * {@link #mergeTraceIdKey(String)}.
     */
    private String traceIdKey = null;

    /**
     * Регистрирует тип данных, встреченный на текущем пути.
     *
     * @param type наименование типа в формате SQL (например, {@code "VARCHAR"}, {@code "INTEGER"})
     */
    public void addType(String type) {
        if (type != null) {
            this.types.add(type);
        }
    }

    /**
     * Вычисляет итоговый тип данных для генерации dbt-модели с учетом полиморфизма.
     *
     * @return {@code "UNKNOWN"} — если данных не было;<br>
     *         строгое имя типа (например, {@code "INTEGER"}) — если тип однороден;<br>
     *         {@code "DOUBLE"} — если зафиксировано числовое смешение {INTEGER, DOUBLE}
     *         (расширение без потери данных);<br>
     *         {@code "VARCHAR"} — при любом другом смешении типов (полиморфизм).
     */
    public String getFinalType() {
        if (types.isEmpty()) return "UNKNOWN";
        if (types.size() == 1) return types.iterator().next();
        boolean allNumeric = types.stream().allMatch(t -> "INTEGER".equals(t) || "DOUBLE".equals(t));
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
     * Выставляет значение флага для конкретной аномалии.
     *
     * @param key   уникальное имя аномалии в стиле {@code snake_case}
     * @param value {@code true}, если аномалия обнаружена в текущем контейнере
     */
    public void setAnomaly(String key, boolean value) {
        this.anomalies.put(key, value);
    }

    /**
     * Проверяет, была ли зафиксирована конкретная аномалия.
     *
     * @param key уникальное имя аномалии
     * @return {@code true}, если аномалия была найдена хотя бы один раз, иначе {@code false}
     */
    public boolean getAnomaly(String key) {
        return this.anomalies.getOrDefault(key, false);
    }

    /**
     * Возвращает полную карту обнаруженных аномалий для данного пути.
     * Используется сериализатором и генератором JSON-отчетов.
     *
     * @return немодифицируемый вид или прямая ссылка на карту аномалий
     */
    public Map<String, Boolean> getAnomalies() {
        return anomalies;
    }

    /**
     * Добавляет идентификатор строки-источника в трассировочный список пути.
     *
     * <ul>
     *   <li>{@code null} — игнорируется (никаких мутаций);</li>
     *   <li>{@code ""} (пустой маркер) — добавляется не более одного на путь и не занимает
     *       слот лимита {@link TraceSettings#getMaxIds()};</li>
     *   <li>непустой — добавляется, только если непустых ещё меньше {@code maxIds};
     *       длина усекается до {@link TraceSettings#getMaxIdLength()}.</li>
     * </ul>
     *
     * @param id идентификатор строки-источника (может быть {@code null} или пустым маркером)
     */
    public void addTraceId(String id) {
        if (id == null) {
            return;
        }
        if (id.isEmpty()) {
            if (!traceIds.contains("")) {
                traceIds.add("");
            }
            return;
        }
        if (countNonEmpty() >= TraceSettings.getInstance().getMaxIds()) {
            return;
        }
        String truncated = id;
        int maxLength = TraceSettings.getInstance().getMaxIdLength();
        if (truncated.length() > maxLength) {
            truncated = truncated.substring(0, maxLength);
        }
        traceIds.add(truncated);
    }

    /** Количество непустых id в текущем списке (маркер {@code ""} не считается). */
    private int countNonEmpty() {
        int count = 0;
        for (String id : traceIds) {
            if (id != null && !id.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Возвращает собранные идентификаторы строк-источников для этого пути.
     *
     * @return список идентификаторов (непустые первыми, маркер {@code ""} — в хвосте)
     */
    public List<String> getTraceIds() {
        return traceIds;
    }

    /**
     * Возвращает имя поля-источника trace_id ({@code null} — не установлен,
     * {@code ""} — источник не найден).
     *
     * @return ключ трассировки
     */
    public String getTraceIdKey() {
        return traceIdKey;
    }

    /**
     * Прямая установка ключа трассировки (используется десериализатором сетевого пакета).
     *
     * @param key имя поля-источника trace_id
     */
    public void setTraceIdKey(String key) {
        this.traceIdKey = key;
    }

    /**
     * Детерминированный выбор ключа трассировки при слиянии воркеров.
     *
     * <p>Правило ассоциативно и коммутативно (корректно при многократном merge):</p>
     * <ol>
     *   <li>пустой ({@code null}/{@code ""}) уступает непустому;</li>
     *   <li>оба в пресете {@link TraceSettings#getPreset()} — ключ с меньшим индексом (выше ранг);</li>
     *   <li>ровно один в пресете — он побеждает (суффикс/explicit — fallback);</li>
     *   <li>оба вне пресета — лексикографически меньший.</li>
     * </ol>
     *
     * @param otherKey ключ трассировки, прилетевший с другого воркера
     */
    public void mergeTraceIdKey(String otherKey) {
        String a = this.traceIdKey;
        String b = otherKey;
        boolean aEmpty = (a == null || a.isEmpty());
        boolean bEmpty = (b == null || b.isEmpty());

        if (aEmpty && bEmpty) {
            this.traceIdKey = ("".equals(a) || "".equals(b)) ? "" : null;
            return;
        }
        if (aEmpty) {
            this.traceIdKey = b;
            return;
        }
        if (bEmpty) {
            return; // непустой a побеждает
        }

        List<String> preset = TraceSettings.getInstance().getPreset();
        int rankA = preset.indexOf(a);
        int rankB = preset.indexOf(b);
        if (rankA >= 0 && rankB >= 0) {
            this.traceIdKey = (rankA <= rankB) ? a : b;
        } else if (rankA < 0 && rankB >= 0) {
            this.traceIdKey = b; // b в пресете — побеждает суффиксный/explicit a
        } else if (rankA >= 0) {
            // a в пресете — остаётся
        } else {
            this.traceIdKey = (a.compareTo(b) <= 0) ? a : b;
        }
    }

    /**
     * Объединяет текущие метрики с метриками, прилетевшими с другого узла кластера.
     * Реализует математику ассоциативного слияния для распределенной агрегации.
     *
     * @param other метрики того же JSONPath, собранные на удаленном воркере Trino
     */
    public void merge(PathMetrics other) {
        if (other == null) return;

        // Слияние множеств типов (накапливаем полиморфизм)
        this.types.addAll(other.types);

        // Вычисление абсолютного максимума длины
        this.maxLength = Math.max(this.maxLength, other.maxLength);

        // Слияние аномалий по правилу логического OR (дизъюнкция)
        other.anomalies.forEach((key, val) -> {
            if (val) {
                this.anomalies.put(key, true);
            }
        });

        // Детерминированное слияние trace_ids:
        // 1. union непустых из this/other в TreeSet (лексикографическая сортировка);
        // 2. непустые занимают слоты max_ids первыми;
        // 3. маркер "" (если был в любом из воркеров) добавляется в хвост при свободном слоте.
        boolean hasEmptyMarker = this.traceIds.contains("") || other.traceIds.contains("");

        TreeSet<String> union = new TreeSet<>();
        for (String id : this.traceIds) {
            if (id != null && !id.isEmpty()) {
                union.add(id);
            }
        }
        for (String id : other.traceIds) {
            if (id != null && !id.isEmpty()) {
                union.add(id);
            }
        }

        this.traceIds.clear();
        int maxIds = TraceSettings.getInstance().getMaxIds();
        for (String id : union) {
            if (this.traceIds.size() >= maxIds) {
                break;
            }
            this.traceIds.add(id);
        }
        if (hasEmptyMarker && this.traceIds.size() < maxIds) {
            this.traceIds.add("");
        }

        // Детерминированный выбор ключа trace_id (см. mergeTraceIdKey)
        this.mergeTraceIdKey(other.traceIdKey);
    }
}
