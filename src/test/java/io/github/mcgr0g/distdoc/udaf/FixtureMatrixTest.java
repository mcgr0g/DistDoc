package io.github.mcgr0g.distdoc.udaf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.slice.Slices;
import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.config.FixtureConfigLoader;
import io.github.mcgr0g.distdoc.chaos.sources.ForgottenMigrationsSource;
import io.github.mcgr0g.distdoc.udaf.config.TraceSettings;
import org.junit.jupiter.api.Test;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

import static io.github.mcgr0g.distdoc.udaf.PathMetrics.ANOMALY_POLYMORPHIC_FORMAT;
import static io.github.mcgr0g.distdoc.udaf.anomalies.AnomalyDetector.METRIC_IS_DATE_PART;
import static io.github.mcgr0g.distdoc.udaf.anomalies.AnomalyDetector.METRIC_IS_EMPTY;
import static io.github.mcgr0g.distdoc.udaf.anomalies.AnomalyDetector.METRIC_IS_STRING_ARRAY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Матрица фикстур как исполняемые ожидания: каждая таблица {@code fixtures.toml} генерируется
 * с её {@code type}/{@code count} и сверяется с docs/testing/fixture-matrix.md (разделы 0–4).
 *
 * <p>Таблицы читаются из того же {@code fixtures.toml}, что {@code lc-gen}, — поэтому
 * переименование таблицы, смена режима или новая таблица без ожиданий роняют этот тест.
 * Проверяются смысловые множества (type, форматы, имена аномалий), а не snapshot.</p>
 */
public class FixtureMatrixTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ForgottenMigrationsSource SOURCE = new ForgottenMigrationsSource();
    private static final Map<String, TomlTable> TABLES = loadTables();
    private static final String PRESET = "";
    private static final int MAX_IDS = TraceSettings.getInstance().getMaxIds();

    private static final String LOCAL = "LOCAL_DATETIME";
    private static final String DATE = "DATE_ONLY";
    private static final String OFFSET = "OFFSET_DATETIME";

    /** Смысловая строка отчёта для пути: type, observed_formats (порядок enum), имена аномалий. */
    record Row(String type, List<String> formats, Set<String> anomalies) {
        static Row of(String type, List<String> formats, String... anomalies) {
            return new Row(type, formats, new TreeSet<>(List.of(anomalies)));
        }
    }

    /** fixture-matrix §0: базовая запись без мутаций. */
    private static Map<String, Row> base() {
        Map<String, Row> m = new TreeMap<>();
        m.put("$._id.$oid", Row.of("VARCHAR", List.of()));
        m.put("$.customer_rating", Row.of("INTEGER", List.of()));
        m.put("$.payment_dates[*]", Row.of("VARCHAR", List.of(DATE), METRIC_IS_STRING_ARRAY));
        m.put("$.birth_date", Row.of("VARCHAR", List.of(DATE)));
        m.put("$.created_at", Row.of("VARCHAR", List.of(LOCAL)));
        m.put("$.updated_at", Row.of("VARCHAR", List.of(OFFSET)));
        m.put("$.promo_expiry_date", Row.of("VARCHAR", List.of(DATE)));
        m.put("$.metadata_encoded.user_agent", Row.of("VARCHAR", List.of()));
        m.put("$.metadata_encoded.retry_count", Row.of("INTEGER", List.of()));
        m.put("$.version.$numberLong", Row.of("INTEGER", List.of()));
        m.put("$.doc_meta.@type", Row.of("VARCHAR", List.of()));
        m.put("$.doc_meta.@version", Row.of("INTEGER", List.of()));
        return m;
    }

    private static final Row POLY_CREATED_AT =
            Row.of("VARCHAR", List.of(DATE, LOCAL, OFFSET), ANOMALY_POLYMORPHIC_FORMAT);
    private static final Row BIRTH_DATE_PARTS =
            Row.of("VARCHAR", List.of(), METRIC_IS_DATE_PART, METRIC_IS_STRING_ARRAY);

    /** Ожидаемый отчёт каждой таблицы fixtures.toml (обычный режим). */
    private static Map<String, Row> expected(String table) {
        Map<String, Row> m = base();
        switch (table) {
            case "clean", "fmt_created_at_local", "fmt_updated_at_offset", "fmt_promo_expiry_date" -> { }
            case "fmt_created_at_unix_millis" -> m.put("$.created_at", Row.of("INTEGER", List.of("UNIX_MILLIS")));
            case "fmt_created_at_date_only" -> m.put("$.created_at", Row.of("VARCHAR", List.of(DATE)));
            case "fmt_customer_rating_promotion" -> m.put("$.customer_rating", Row.of("DOUBLE", List.of()));
            case "arr_birth_date_parts" -> {
                m.remove("$.birth_date");
                m.put("$.birth_date[*]", BIRTH_DATE_PARTS);
            }
            case "arr_payment_dates_empty" -> m.put("$.payment_dates[*]", Row.of("ARRAY", List.of(), METRIC_IS_EMPTY));
            case "pol_created_at_formats" -> m.put("$.created_at", POLY_CREATED_AT);
            case "pol_created_at_with_arrays" -> {
                m.put("$.created_at", POLY_CREATED_AT);
                m.put("$.birth_date[*]", BIRTH_DATE_PARTS);
                m.put("$.payment_dates[*]", Row.of("VARCHAR", List.of(DATE), METRIC_IS_EMPTY, METRIC_IS_STRING_ARRAY));
            }
            case "pol_created_at_with_rating" -> {
                m.put("$.created_at", POLY_CREATED_AT);
                m.put("$.customer_rating", Row.of("DOUBLE", List.of()));
            }
            case "trace_mixed_sources" -> {
                m.put("$.id", Row.of("VARCHAR", List.of()));
                m.put("$.order_id", Row.of("VARCHAR", List.of()));
            }
            case "crm_combined" -> {
                // fixture-matrix §4, golden-map
                m.put("$.created_at", Row.of("VARCHAR", List.of(DATE, LOCAL, OFFSET, "UNIX_MILLIS"),
                        ANOMALY_POLYMORPHIC_FORMAT));
                m.put("$.birth_date[*]", BIRTH_DATE_PARTS);
                m.put("$.payment_dates[*]", Row.of("VARCHAR", List.of(DATE), METRIC_IS_EMPTY, METRIC_IS_STRING_ARRAY));
                m.put("$.customer_rating", Row.of("DOUBLE", List.of()));
                m.put("$.surrogate_pk", Row.of("VARCHAR", List.of()));
            }
            default -> fail("Таблица " + table + " из fixtures.toml не описана в fixture-matrix.md и в этом тесте");
        }
        return m;
    }

    // ------------------------------------------------------------------ реестр

    @Test
    public void testRegistryMatchesMatrix() {
        assertEquals(Set.of("crm_combined", "clean",
                        "fmt_created_at_local", "fmt_created_at_unix_millis", "fmt_created_at_date_only",
                        "fmt_updated_at_offset", "fmt_promo_expiry_date", "fmt_customer_rating_promotion",
                        "arr_birth_date_parts", "arr_payment_dates_empty",
                        "pol_created_at_formats", "pol_created_at_with_arrays", "pol_created_at_with_rating",
                        "trace_mixed_sources"),
                TABLES.keySet(), "состав fixtures.toml разошёлся с fixture-matrix.md");
        TABLES.forEach((name, t) -> {
            assertEquals("build/dev-lakehouse/data/" + name + ".jsonl", t.getString("file"), "jsonl — по имени таблицы");
            scenario(t); // type — существующий режим
        });
    }

    // ------------------------------------------------------------------ обычный отчёт (§0–2, §4)

    @Test
    public void testEveryTableMatchesExpectations() {
        for (String table : TABLES.keySet()) {
            assertEquals(expected(table), rows(report(table, null)), "отчёт таблицы " + table);
        }
    }

    @Test
    public void testLengthsFromMatrix() {
        assertEquals(0, report("fmt_created_at_unix_millis", null).at("/$.created_at/max_length").asInt(),
                "millis — число, длины строки нет");
        assertEquals(25, report("pol_created_at_formats", null).at("/$.created_at/max_length").asInt(),
                "самое длинное написание — offset");
    }

    @Test
    public void testNormalModeHasNoTraceOnAnyTable() {
        for (String table : TABLES.keySet()) {
            String rx = report(table, null).toString();
            assertFalse(rx.contains("path_trace") || rx.contains("\"trace\""), "trace в обычном режиме: " + table);
        }
    }

    // ------------------------------------------------------------------ trace (§3)

    @Test
    public void testTraceShapeOnEveryTable() {
        for (String table : TABLES.keySet()) {
            JsonNode root = report(table, PRESET);
            forEachPath(root, (path, node) -> {
                assertTrue(node.has("path_trace"), table + ": нет path_trace у " + path);
                assertScope(table + " " + path + " path_trace", node.get("path_trace"));
                node.path("anomalies").properties().forEach(a ->
                        assertTrue(a.getValue().has("trace"), table + ": нет trace у " + path + "/" + a.getKey()));
            });
        }
    }

    @Test
    public void testMixedSourcesPairsAreConsistent() {
        JsonNode root = report("trace_mixed_sources", PRESET);
        forEachPath(root, (path, node) -> {
            forEachPair(node.get("path_trace"), (id, key) -> assertEquals(expectedKey(id), key,
                    "ложная пара в " + path + ": " + id + " / " + key));
            node.path("anomalies").properties().forEach(a -> forEachPair(a.getValue().get("trace"),
                    (id, key) -> assertEquals(expectedKey(id), key, "ложная пара в " + path + "/" + a.getKey())));
        });
        // Путь, существующий только у одного источника, получает id только этого источника
        forEachPair(root.at("/$.id/path_trace"), (id, key) -> assertEquals("id", key));
        forEachPair(root.at("/$.order_id/path_trace"), (id, key) -> assertEquals("order_id", key));
        forEachPair(root.at("/$._id.$oid/path_trace"), (id, key) -> assertEquals("_id.$oid", key));
    }

    @Test
    public void testArrayAnomalyTraceComesFromOwnGroup() {
        JsonNode root = report("pol_created_at_with_arrays", PRESET);
        assertGroup(root.at("/$.birth_date[*]/anomalies/" + METRIC_IS_DATE_PART + "/trace"), i -> i % 2 == 1);
        assertGroup(root.at("/$.payment_dates[*]/anomalies/" + METRIC_IS_EMPTY + "/trace"), i -> i % 5 == 0);
    }

    @Test
    public void testPolymorphicTraceHasEveryFormatFromOwnGroup() {
        JsonNode trace = report("pol_created_at_formats", PRESET)
                .at("/$.created_at/anomalies/" + ANOMALY_POLYMORPHIC_FORMAT + "/trace");
        Map<String, Integer> perFormat = new TreeMap<>();
        Map<String, Integer> groupOf = Map.of(LOCAL, 0, DATE, 1, OFFSET, 2);
        trace.forEach(item -> {
            String format = item.get("format").asText();
            perFormat.merge(format, 1, Integer::sum);
            assertEquals(groupOf.get(format), (int) (index(item.get("id").asText()) % 3),
                    "id полиморфизма из чужой группы: " + item);
        });
        assertEquals(Set.of(DATE, LOCAL, OFFSET), perFormat.keySet());
        perFormat.forEach((f, n) -> assertTrue(n >= 1 && n <= MAX_IDS, f + ": " + n + " пар"));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, TomlTable> loadTables() {
        try {
            TomlArray scenarios = FixtureConfigLoader.loadTableConfig().getArray("scenarios");
            Map<String, TomlTable> tables = new TreeMap<>();
            for (int i = 0; i < scenarios.size(); i++) {
                TomlTable t = scenarios.getTable(i);
                assertNull(tables.put(t.getString("table"), t), "дубликат таблицы " + t.getString("table"));
            }
            return tables;
        } catch (Exception e) {
            throw new IllegalStateException("fixtures.toml не прочитан", e);
        }
    }

    private static AnomalyScenario scenario(TomlTable t) {
        return AnomalyScenario.valueOf(t.getString("type").toUpperCase(Locale.ROOT));
    }

    /** Генерирует таблицу так же, как ChaosGeneratorApp (index 0…count-1), и возвращает отчёт. */
    private static JsonNode report(String table, String traceArg) {
        TomlTable t = TABLES.get(table);
        AnomalyScenario scenario = scenario(t);
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        try {
            for (long i = 0; i < t.getLong("count"); i++) {
                byte[] line = ChaosDataGenerator.generateSingleLine(SOURCE, scenario, i).getBytes(StandardCharsets.UTF_8);
                if (traceArg == null) {
                    analyzer.analyze(new ByteArrayInputStream(line));
                } else {
                    analyzer.analyze(new ByteArrayInputStream(line), Slices.utf8Slice(traceArg));
                }
            }
            return MAPPER.readTree(analyzer.buildJsonReport());
        } catch (Exception e) {
            throw new IllegalStateException("Таблица " + table + " не сгенерирована", e);
        }
    }

    private static Map<String, Row> rows(JsonNode root) {
        Map<String, Row> m = new TreeMap<>();
        forEachPath(root, (path, node) -> {
            List<String> formats = new ArrayList<>();
            node.path("observed_formats").forEach(f -> formats.add(f.asText()));
            Set<String> anomalies = new TreeSet<>();
            node.path("anomalies").fieldNames().forEachRemaining(anomalies::add);
            m.put(path, new Row(node.get("type").asText(), formats, anomalies));
        });
        return m;
    }

    private static void forEachPath(JsonNode root, BiConsumer<String, JsonNode> action) {
        root.properties().forEach(e -> {
            if (!"schema_version".equals(e.getKey())) {
                action.accept(e.getKey(), e.getValue());
            }
        });
    }

    private static void forEachPair(JsonNode trace, BiConsumer<String, String> action) {
        assertTrue(trace.isArray() && !trace.isEmpty(), "пустой или отсутствующий trace: " + trace);
        trace.forEach(item -> action.accept(item.get("id").asText(), item.get("id_key").asText()));
    }

    /** §3 «любая таблица»: 1…max_ids пар, сортировка по (id, id_key), маркер не более одного и только в хвосте. */
    private static void assertScope(String scope, JsonNode trace) {
        assertTrue(trace.size() >= 1 && trace.size() <= MAX_IDS, scope + ": " + trace);
        List<String> ids = new ArrayList<>();
        forEachPair(trace, (id, key) -> ids.add(id + "\u0000" + key));
        int marker = ids.indexOf("\u0000");
        assertTrue(marker == -1 || marker == ids.size() - 1, scope + ": маркер не в хвосте " + trace);
        List<String> real = ids.subList(0, marker == -1 ? ids.size() : marker);
        List<String> sorted = new ArrayList<>(real);
        sorted.sort(null);
        assertEquals(sorted, real, scope + ": пары не отсортированы " + trace);
    }

    /** Префикс id однозначно задаёт источник (ForgottenMigrationsSource.mixedIdSource). */
    private static String expectedKey(String id) {
        if (id.isEmpty()) return "";
        if (id.startsWith("60b8")) return "_id.$oid";
        if (id.startsWith("id-")) return "id";
        if (id.startsWith("ord-")) return "order_id";
        return fail("id неизвестного источника: " + id);
    }

    private static void assertGroup(JsonNode trace, LongPredicate group) {
        forEachPair(trace, (id, key) -> assertTrue(group.test(index(id)), "id из чужой группы: " + id));
    }

    /** Индекс строки по _id.$oid базовой записи: последние 10 цифр. */
    private static long index(String oid) {
        assertTrue(oid.startsWith("60b8"), "ожидался _id.$oid, получено " + oid);
        return Long.parseLong(oid.substring(oid.length() - 10));
    }
}
