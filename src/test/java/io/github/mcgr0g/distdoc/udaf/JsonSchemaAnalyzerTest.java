package io.github.mcgr0g.distdoc.udaf;

import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.sources.*;
import io.github.mcgr0g.distdoc.udaf.TraceEvidence.Pair;
import io.github.mcgr0g.distdoc.udaf.anomalies.AnomalyDetector;
import io.github.mcgr0g.distdoc.udaf.formats.ValueFormat;
import io.airlift.slice.Slices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import static io.github.mcgr0g.distdoc.udaf.PathMetrics.ANOMALY_JSON_STRING;
import static io.github.mcgr0g.distdoc.udaf.PathMetrics.ANOMALY_POLYMORPHIC_FORMAT;
import static io.github.mcgr0g.distdoc.udaf.formats.ValueFormat.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты анализатора: типы, форматы (docs/testing/fixture-matrix.md, разделы 0–1),
 * аномалии и trace (docs/testing/tracing.md).
 */
public class JsonSchemaAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ForgottenMigrationsSource SOURCE = new ForgottenMigrationsSource();
    private static final String PRESET = "";

    // ------------------------------------------------------------------ helpers

    /** Строка фикстуры; checked-исключение генератора оборачивается (тест падает с причиной). */
    private static String line(AnomalyScenario scenario, long index) {
        try {
            return ChaosDataGenerator.generateSingleLine(SOURCE, scenario, index);
        } catch (Exception e) {
            throw new IllegalStateException("Генератор фикстур не выдал строку " + scenario + "/" + index, e);
        }
    }

    private static void feed(JsonSchemaAnalyzer analyzer, AnomalyScenario scenario, long index, String traceArg) {
        feed(analyzer, line(scenario, index), traceArg);
    }

    private static void feed(JsonSchemaAnalyzer analyzer, String json, String traceArg) {
        ByteArrayInputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        if (traceArg == null) {
            analyzer.analyze(in);
        } else {
            analyzer.analyze(in, Slices.utf8Slice(traceArg));
        }
    }

    private static JsonSchemaAnalyzer analyzed(AnomalyScenario scenario, long index, String traceArg) {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, scenario, index, traceArg);
        return analyzer;
    }

    private static PathMetrics path(JsonSchemaAnalyzer analyzer, String path) {
        PathMetrics metrics = analyzer.getSchemaMap().get(path);
        assertNotNull(metrics, "Путь " + path + " отсутствует");
        return metrics;
    }

    private static List<Pair> pathTrace(JsonSchemaAnalyzer analyzer, String path) {
        TraceEvidence evidence = path(analyzer, path).getPathTrace();
        assertNotNull(evidence, "path_trace для " + path + " не собран");
        return evidence.items();
    }

    private static List<Pair> anomalyTrace(JsonSchemaAnalyzer analyzer, String path, String anomaly) {
        TraceEvidence evidence = path(analyzer, path).getAnomalyTrace().get(anomaly);
        assertNotNull(evidence, "trace аномалии " + anomaly + " для " + path + " не собран");
        return evidence.items();
    }

    private static String oid(long index) {
        return String.format("60b8d29f1a4c8b%010d", index);
    }

    // ------------------------------------------------------------------ типы и структура

    @Test
    public void testCleanDataAndDeEscaping() {
        Map<String, PathMetrics> schema = analyzed(AnomalyScenario.CLEAN, 1, null).getSchemaMap();

        assertEquals("VARCHAR", schema.get("$._id.$oid").getFinalType());
        assertEquals("VARCHAR", schema.get("$.metadata_encoded.user_agent").getFinalType(),
                "Путь внутри экранированной строки не распарсился");
        assertEquals("INTEGER", schema.get("$.metadata_encoded.retry_count").getFinalType());
        assertEquals("INTEGER", schema.get("$.version.$numberLong").getFinalType());
        assertEquals("VARCHAR", schema.get("$.doc_meta.@type").getFinalType());
        assertEquals("INTEGER", schema.get("$.doc_meta.@version").getFinalType());
        assertEquals("INTEGER", schema.get("$.customer_rating").getFinalType());
    }

    @Test
    public void testEmptyArrayAnomaly() {
        PathMetrics metrics = path(analyzed(AnomalyScenario.EMPTY_ARRAY, 1, null), "$.payment_dates[*]");
        assertTrue(metrics.hasAnomaly(AnomalyDetector.METRIC_IS_EMPTY), "Аномалия пустого массива не зафиксирована");
        assertFalse(metrics.hasAnomaly(AnomalyDetector.METRIC_IS_DATE_PART));
        assertEquals("ARRAY", metrics.getFinalType());
        assertTrue(metrics.getFormats().isEmpty(), "Пустой массив не даёт форматов");
    }

    @Test
    public void testDateAsArrayAnomaly() {
        JsonSchemaAnalyzer analyzer = analyzed(AnomalyScenario.DATE_AS_ARRAY, 1, null);
        PathMetrics metrics = path(analyzer, "$.birth_date[*]");
        assertTrue(metrics.hasAnomaly(AnomalyDetector.METRIC_IS_DATE_PART));
        assertTrue(metrics.hasAnomaly(AnomalyDetector.METRIC_IS_STRING_ARRAY));
        assertFalse(metrics.hasAnomaly(AnomalyDetector.METRIC_IS_EMPTY));
        assertTrue(metrics.getFormats().isEmpty(), "'1990' — частица, а не формат");
        assertFalse(analyzer.getSchemaMap().containsKey("$.birth_date"), "скалярный путь birth_date в DATE_AS_ARRAY не появляется");
    }

    @Test
    public void testAllChaosAggregation() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        for (int i = 0; i < 100; i++) {
            feed(analyzer, AnomalyScenario.ALL, i, null);
        }
        assertTrue(path(analyzer, "$.payment_dates[*]").hasAnomaly(AnomalyDetector.METRIC_IS_EMPTY));
        assertTrue(path(analyzer, "$.birth_date[*]").hasAnomaly(AnomalyDetector.METRIC_IS_DATE_PART));

        // Числовой подъём INTEGER + DOUBLE -> DOUBLE; это не format anomaly
        PathMetrics rating = path(analyzer, "$.customer_rating");
        assertEquals("DOUBLE", rating.getFinalType());
        assertTrue(rating.getFormats().isEmpty());
        assertFalse(rating.hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT));

        // created_at: группы i%4 (local / date-only / unix millis / offset) -> полиморфизм
        PathMetrics createdAt = path(analyzer, "$.created_at");
        assertEquals(EnumSet.of(DATE_ONLY, LOCAL_DATETIME, OFFSET_DATETIME, UNIX_MILLIS), createdAt.getFormats());
        assertTrue(createdAt.hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT));
    }

    // ------------------------------------------------------------------ форматы (fixture-matrix.md, разделы 0–1)

    @Test
    public void testCleanBackgroundFormats() {
        JsonSchemaAnalyzer analyzer = analyzed(AnomalyScenario.CLEAN, 1, null);

        assertEquals(Set.of(LOCAL_DATETIME), path(analyzer, "$.created_at").getFormats());
        assertEquals(Set.of(OFFSET_DATETIME), path(analyzer, "$.updated_at").getFormats(), "+03:00 — OFFSET, не UTC");
        assertEquals(Set.of(DATE_ONLY), path(analyzer, "$.promo_expiry_date").getFormats(), "строковому формату hint не нужен");
        assertEquals(Set.of(DATE_ONLY), path(analyzer, "$.birth_date").getFormats());
        assertEquals(Set.of(DATE_ONLY), path(analyzer, "$.payment_dates[*]").getFormats(), "формат элементов пишется на путь массива");

        // Числа без контекста датами не становятся
        for (String p : List.of("$.customer_rating", "$.version.$numberLong", "$.doc_meta.@version",
                "$.metadata_encoded.retry_count", "$._id.$oid", "$.doc_meta.@type", "$.metadata_encoded.user_agent")) {
            assertTrue(path(analyzer, p).getFormats().isEmpty(), "ложный формат на " + p);
        }
        analyzer.getSchemaMap().forEach((p, m) ->
                assertFalse(m.hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT), "ложный полиморфизм на " + p));
    }

    @Test
    public void testDateAtUnixScenario() {
        PathMetrics metrics = path(analyzed(AnomalyScenario.DATE_AT_UNIX, 1, null), "$.created_at");
        assertEquals("INTEGER", metrics.getFinalType(), "без ложного VARCHAR");
        assertEquals(0, metrics.getMaxLength());
        assertEquals(Set.of(UNIX_MILLIS), metrics.getFormats());
    }

    @Test
    public void testDateAsPlainScenario() {
        PathMetrics metrics = path(analyzed(AnomalyScenario.DATE_AS_PLAIN, 1, null), "$.created_at");
        assertEquals("VARCHAR", metrics.getFinalType());
        assertEquals(Set.of(DATE_ONLY), metrics.getFormats());
        assertFalse(metrics.hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT));
    }

    @Test
    public void testPolymorphicTransitionIsMonotonic() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();

        feed(analyzer, AnomalyScenario.CLEAN, 0, null);
        assertFalse(path(analyzer, "$.created_at").hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT), "один формат — не аномалия");

        feed(analyzer, AnomalyScenario.DATE_AS_PLAIN, 1, null);
        assertTrue(path(analyzer, "$.created_at").hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT), "второй формат зажигает аномалию");

        feed(analyzer, AnomalyScenario.CLEAN, 2, null);
        feed(analyzer, AnomalyScenario.DATE_AT_UNIX, 3, null);
        PathMetrics metrics = path(analyzer, "$.created_at");
        assertTrue(metrics.hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT), "однородные строки аномалию не сбрасывают");
        assertEquals(List.of(DATE_ONLY, LOCAL_DATETIME, UNIX_MILLIS), new ArrayList<>(metrics.getFormats()),
                "порядок форматов — порядок объявления enum");
    }

    @Test
    public void testPlainStringDoesNotAddOrRemoveFormat() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, "{\"created_at\": \"2026-07-23\"}", null);
        feed(analyzer, "{\"created_at\": \"not-a-date\"}", null);
        feed(analyzer, "{\"created_at\": \"2026-02-30\"}", null);
        PathMetrics metrics = path(analyzer, "$.created_at");
        assertEquals(Set.of(DATE_ONLY), metrics.getFormats());
        assertFalse(metrics.hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT));
    }

    @Test
    public void testNumbersNeedContext() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, "{\"order_count\": 1784769300000, \"amount\": 1784769300.5, \"sync_utc\": 1784769300.5,"
                + " \"closed_at\": 1784769300, \"big_at\": 123456789012345678901234567890}", null);

        assertTrue(path(analyzer, "$.order_count").getFormats().isEmpty(), "счётчик без hint");
        assertTrue(path(analyzer, "$.amount").getFormats().isEmpty(), "деньги без hint");
        assertEquals(Set.of(UNIX_SECONDS), path(analyzer, "$.sync_utc").getFormats());
        assertEquals(Set.of(UNIX_SECONDS), path(analyzer, "$.closed_at").getFormats());
        assertTrue(path(analyzer, "$.big_at").getFormats().isEmpty(), "вне диапазона long формат не получает");
    }

    @Test
    public void testBsonDateIsScalarLeaf() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, "{\"doc\": {\"$date\": {\"$numberLong\": \"1784769300000\"}},"
                + " \"legacy\": {\"$date\": 1784769300000}, \"relaxed\": {\"$date\": \"2026-07-23T01:15:00Z\"}}", null);

        PathMetrics canonical = path(analyzer, "$.doc.$date.$numberLong");
        assertEquals("VARCHAR", canonical.getFinalType());
        assertEquals(Set.of(UNIX_MILLIS), canonical.getFormats(), "canonical Extended JSON без hint");
        assertEquals(Set.of(UNIX_MILLIS), path(analyzer, "$.legacy.$date").getFormats());
        assertEquals(Set.of(UTC_DATETIME), path(analyzer, "$.relaxed.$date").getFormats());
        assertFalse(analyzer.getSchemaMap().containsKey("$.doc"), "формат не поднимается к родителю");
        assertFalse(analyzer.getSchemaMap().containsKey("$.doc.$date"), "BSON-обёртка не оставляет промежуточных путей");
    }

    @Test
    public void testScalarAndArrayOnSamePathAreSeparate() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, AnomalyScenario.CLEAN, 0, null);
        feed(analyzer, AnomalyScenario.DATE_AS_ARRAY, 1, null);
        assertEquals(Set.of(DATE_ONLY), path(analyzer, "$.birth_date").getFormats());
        assertTrue(path(analyzer, "$.birth_date[*]").getFormats().isEmpty());
    }

    @Test
    public void testPolymorphicAppearsOnlyAfterMerge() {
        JsonSchemaAnalyzer worker1 = analyzed(AnomalyScenario.CLEAN, 0, null);
        JsonSchemaAnalyzer worker2 = analyzed(AnomalyScenario.DATE_AS_PLAIN, 1, null);
        assertFalse(path(worker1, "$.created_at").hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT));
        assertFalse(path(worker2, "$.created_at").hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT));

        worker1.merge(worker2);
        assertTrue(path(worker1, "$.created_at").hasAnomaly(ANOMALY_POLYMORPHIC_FORMAT));
    }

    // ------------------------------------------------------------------ форма отчёта 2.0

    @Test
    public void testReportShapeNormalMode() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, AnomalyScenario.CLEAN, 0, null);
        feed(analyzer, AnomalyScenario.EMPTY_ARRAY, 1, null);
        feed(analyzer, AnomalyScenario.DATE_AS_PLAIN, 2, null);
        JsonNode root = MAPPER.readTree(analyzer.buildJsonReport());

        assertEquals("3.0", root.get("schema_version").asText());

        JsonNode createdAt = root.get("$.created_at");
        assertEquals("[\"DATE_ONLY\",\"LOCAL_DATETIME\"]", createdAt.get("observed_formats").toString());
        assertEquals("{\"is_polymorphic_format\":{\"detected\":true}}", createdAt.get("anomalies").toString());

        JsonNode payment = root.get("$.payment_dates[*]");
        assertEquals("{\"is_array_empty\":{\"detected\":true},\"is_flat_string_array\":{\"detected\":true}}",
                payment.get("anomalies").toString(), "аномалии — объекты, отсортированы по имени");

        JsonNode rating = root.get("$.customer_rating");
        assertFalse(rating.has("observed_formats"), "0 форматов — ключа нет");
        assertFalse(rating.has("anomalies"), "0 аномалий — ключа нет");

        String report = analyzer.buildJsonReport();
        for (String legacy : List.of("path_trace", "\"trace\"", "trace_ids", "trace_id_key", "format_trace")) {
            assertFalse(report.contains(legacy), "в обычном режиме нет " + legacy);
        }
    }

    @Test
    public void testReportPathsAreLexicographic() throws Exception {
        JsonNode root = MAPPER.readTree(analyzed(AnomalyScenario.ALL, 0, null).buildJsonReport());
        List<String> keys = new ArrayList<>();
        Iterator<String> it = root.fieldNames();
        it.next(); // schema_version — первый
        it.forEachRemaining(keys::add);
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(null);
        assertEquals(sorted, keys);
    }

    // ------------------------------------------------------------------ формы пути (контракт rx-data 3.0, раздел 2b)

    private static JsonSchemaAnalyzer analyzedJson(String... rows) {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        for (String row : rows) {
            feed(analyzer, row, null);
        }
        return analyzer;
    }

    private static JsonNode reportOf(JsonSchemaAnalyzer analyzer) {
        try {
            return MAPPER.readTree(analyzer.buildJsonReport());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Пути отчёта без служебного schema_version. */
    private static Set<String> reportPaths(JsonSchemaAnalyzer analyzer) {
        Set<String> paths = new TreeSet<>();
        reportOf(analyzer).fieldNames().forEachRemaining(paths::add);
        paths.remove("schema_version");
        return paths;
    }

    private static Set<String> reportAnomalies(JsonSchemaAnalyzer analyzer, String path) {
        Set<String> names = new TreeSet<>();
        reportOf(analyzer).path(path).path("anomalies").fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String reportType(JsonSchemaAnalyzer analyzer, String path) {
        return reportOf(analyzer).path(path).path("type").asText();
    }

    @Test
    public void testPureObjectsHaveNoRecords() {
        JsonSchemaAnalyzer analyzer = analyzedJson(
                "{\"a\": {\"b\": {\"c\": 1}, \"e\": {}}, \"arr\": [{\"m\": {\"k\": 1}, \"s\": \"x\"}]}");
        assertEquals(Set.of("$.a.b.c", "$.arr[*]", "$.arr[*].m.k", "$.arr[*].s"), reportPaths(analyzer),
                "в отчёте только листья и массивы: ни одной записи объекта (в том числе {} и объект внутри элемента)");
        assertEquals("ARRAY", reportType(analyzer, "$.arr[*]"));
        // Форма «объект» при этом зафиксирована во внутреннем состоянии (нужна слиянию воркеров)
        assertEquals("OBJECT", path(analyzer, "$.a").getFinalType());
        assertEquals("OBJECT", path(analyzer, "$.arr[*].m").getFinalType());
    }

    @Test
    public void testRootArrayOfObjects() {
        JsonSchemaAnalyzer analyzer = analyzedJson("[{\"a\": 1}, {\"a\": 2}]");
        assertEquals(Set.of("$[*]", "$[*].a"), reportPaths(analyzer));
        assertEquals("ARRAY", reportType(analyzer, "$[*]"));
    }

    @Test
    public void testBsonWrapperIsNotAnObject() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"_id\": {\"$oid\": \"x\"}, \"d\": {\"$date\": {\"$numberLong\": \"1\"}}}");
        assertEquals(Set.of("$._id.$oid", "$.d.$date.$numberLong"), reportPaths(analyzer));
        assertFalse(analyzer.getSchemaMap().containsKey("$._id"), "обёртка не даёт формы OBJECT");
        assertFalse(analyzer.getSchemaMap().containsKey("$.d.$date"), "вложенная обёртка тоже");
    }

    @Test
    public void testBsonWrapperAndPlainScalarAreTwoLeaves() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"_id\": {\"$oid\": \"x\"}}", "{\"_id\": \"y\"}");
        assertEquals(Set.of("$._id", "$._id.$oid"), reportPaths(analyzer));
        assertEquals(Set.of(), reportAnomalies(analyzer, "$._id"), "обёртка — не вторая форма: неоднородности нет");
    }

    @Test
    public void testObjectAndArrayOnSamePathArePolymorphic() {
        String asObject = "{\"p\": {\"name\": \"n\"}}";
        String asArray = "{\"p\": [{\"id\": \"i\", \"share\": 50}]}";
        JsonSchemaAnalyzer forward = analyzedJson(asObject, asArray);
        JsonSchemaAnalyzer backward = analyzedJson(asArray, asObject);

        assertEquals(Set.of("$.p", "$.p.name", "$.p[*]", "$.p[*].id", "$.p[*].share"), reportPaths(forward));
        assertEquals("OBJECT", reportType(forward, "$.p"), "объект + массив → тип записи OBJECT");
        assertEquals(Set.of(PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE), reportAnomalies(forward, "$.p"));
        assertEquals(Set.of("ARRAY", "OBJECT"), forward.structureForms("$.p"));
        assertEquals("ARRAY", reportType(forward, "$.p[*]"));
        assertEquals(Set.of(), reportAnomalies(forward, "$.p[*]"), "массив объектов не пуст, аномалий нет");
        assertEquals(forward.buildJsonReport(), backward.buildJsonReport(), "порядок строк не влияет на отчёт");
    }

    @Test
    public void testObjectAndScalarArePolymorphic() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"x\": {\"a\": 1}}", "{\"x\": \"text\"}");
        assertEquals("VARCHAR", reportType(analyzer, "$.x"), "OBJECT со скаляром → VARCHAR");
        assertEquals(4, reportOf(analyzer).path("$.x").path("max_length").asInt());
        assertEquals(Set.of(PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE), reportAnomalies(analyzer, "$.x"));
        assertEquals(Set.of("OBJECT", "SCALAR"), analyzer.structureForms("$.x"));
        assertEquals("INTEGER", reportType(analyzer, "$.x.a"));
    }

    @Test
    public void testScalarAndArrayArePolymorphic() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"b\": \"1990-05-15\"}", "{\"b\": [\"1990\", \"05\", \"15\"]}");
        assertEquals(Set.of(PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE), reportAnomalies(analyzer, "$.b"));
        assertEquals(Set.of("ARRAY", "SCALAR"), analyzer.structureForms("$.b"));
    }

    @Test
    public void testThreeForms() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"x\": {\"a\": 1}}", "{\"x\": \"s\"}", "{\"x\": [1]}");
        assertEquals(Set.of("ARRAY", "OBJECT", "SCALAR"), analyzer.structureForms("$.x"));
        assertEquals("VARCHAR", reportType(analyzer, "$.x"));
    }

    @Test
    public void testEmptyObjectIsAFormButLeavesNoRecord() {
        assertEquals(Set.of(), reportPaths(analyzedJson("{\"x\": {}}")), "чистый пустой объект ничего не оставляет");
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"x\": {}}", "{\"x\": [1]}");
        assertEquals(Set.of("ARRAY", "OBJECT"), analyzer.structureForms("$.x"), "но формой OBJECT является");
        assertEquals(Set.of(PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE), reportAnomalies(analyzer, "$.x"));
    }

    @Test
    public void testObjectNodeMergeIsCommutative() {
        JsonSchemaAnalyzer asObject = analyzedJson("{\"x\": {\"a\": 1}}");
        JsonSchemaAnalyzer asScalar = analyzedJson("{\"x\": \"s\"}");
        JsonSchemaAnalyzer ab = analyzedJson("{\"x\": {\"a\": 1}}");
        ab.merge(asScalar);
        JsonSchemaAnalyzer ba = analyzedJson("{\"x\": \"s\"}");
        ba.merge(asObject);
        assertEquals("VARCHAR", reportType(ab, "$.x"));
        assertEquals(ab.buildJsonReport(), ba.buildJsonReport());
    }

    // ------------------------------------------------------------------ jsonstring

    @Test
    public void testJsonStringObject() {
        String inner = "{\"u\": \"a\", \"n\": 1}";
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"m\": " + quote(inner) + "}");
        assertEquals("VARCHAR", reportType(analyzer, "$.m"));
        assertEquals(inner.length(), reportOf(analyzer).path("$.m").path("max_length").asInt(), "длина исходной строки");
        assertEquals(Set.of(ANOMALY_JSON_STRING), reportAnomalies(analyzer, "$.m"), "одна форма — неоднородности нет");
        assertEquals(Set.of(PathMetrics.TYPE_JSON_STRING, PathMetrics.TYPE_JSON_OBJECT), path(analyzer, "$.m").getTypes(),
                "содержимое — форма JSON_OBJECT, а не нативный OBJECT");
        assertEquals("VARCHAR", reportType(analyzer, "$.m.u"));
        assertEquals("INTEGER", reportType(analyzer, "$.m.n"));
    }

    @Test
    public void testJsonStringArray() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"m\": " + quote("[{\"k\": 1}, {\"k\": 2}]") + "}");
        assertEquals(Set.of(ANOMALY_JSON_STRING), reportAnomalies(analyzer, "$.m"));
        assertEquals("ARRAY", reportType(analyzer, "$.m[*]"));
        assertEquals(Set.of(), reportAnomalies(analyzer, "$.m[*]"), "массив объектов не пуст");
        assertEquals("INTEGER", reportType(analyzer, "$.m[*].k"));
    }

    @Test
    public void testJsonStringObjectOrArrayIsPolymorphic() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"m\": " + quote("{\"u\": \"a\"}") + "}", "{\"m\": " + quote("[{\"k\": 1}]") + "}");
        assertEquals(Set.of(ANOMALY_JSON_STRING, PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE), reportAnomalies(analyzer, "$.m"));
        assertEquals(Set.of("ARRAY", "JSON_OBJECT"), analyzer.structureForms("$.m"));
        assertEquals("VARCHAR", reportType(analyzer, "$.m"));
    }

    @Test
    public void testJsonStringWithPlainStrings() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"m\": " + quote("{\"u\": \"a\"}") + "}", "{\"m\": \"\"}", "{\"m\": \"n/a\"}");
        assertEquals(Set.of(ANOMALY_JSON_STRING, PathMetrics.ANOMALY_NON_JSON_STRINGS, PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE),
                reportAnomalies(analyzer, "$.m"));
        assertEquals(Set.of("JSON_OBJECT", "SCALAR"), analyzer.structureForms("$.m"));
    }

    @Test
    public void testJsonStringWithNativeObject() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"m\": {\"u\": \"a\"}}", "{\"m\": " + quote("{\"u\": \"a\"}") + "}");
        assertEquals(Set.of(ANOMALY_JSON_STRING, PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE), reportAnomalies(analyzer, "$.m"),
                "нативный объект рядом с JSON-строкой различим по формам; обычных строк нет");
        assertEquals(Set.of("JSON_OBJECT", "OBJECT"), analyzer.structureForms("$.m"));
    }

    @Test
    public void testTextLookingLikeJsonIsPlainString() {
        for (String text : List.of("{abc}", "{\"a\":}", "[1,", "[x]", "[TEST]", "{\"a\": 1} {\"b\": 2}")) {
            JsonSchemaAnalyzer analyzer = analyzedJson("{\"t\": " + quote(text) + "}");
            assertEquals(Set.of("$.t"), reportPaths(analyzer), "текст не оставляет путей: " + text);
            assertEquals(Set.of(), reportAnomalies(analyzer, "$.t"), text);
            assertEquals(text.length(), reportOf(analyzer).path("$.t").path("max_length").asInt(), text);
        }
    }

    @Test
    public void testJsonStringInsideArray() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"l\": [" + quote("{\"a\": 1}") + "]}");
        assertEquals(Set.of(ANOMALY_JSON_STRING), reportAnomalies(analyzer, "$.l[*]"),
                "не пуст, не плоский строковый массив, строка с JSON");
        assertEquals("INTEGER", reportType(analyzer, "$.l[*].a"));
    }

    private static String quote(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ пустота массива

    @Test
    public void testOnlyArrayWithoutElementsIsEmpty() {
        JsonSchemaAnalyzer analyzer = analyzedJson(
                "{\"o\": [{\"a\": 1}], \"f\": [1.5], \"b\": [true], \"n\": [null], \"e\": []}");
        for (String notEmpty : List.of("$.o[*]", "$.f[*]", "$.b[*]", "$.n[*]")) {
            assertFalse(path(analyzer, notEmpty).hasAnomaly(AnomalyDetector.METRIC_IS_EMPTY), notEmpty + " не пуст");
        }
        assertTrue(path(analyzer, "$.e[*]").hasAnomaly(AnomalyDetector.METRIC_IS_EMPTY));
        assertTrue(path(analyzer, "$.n[*]").getAnomalies().isEmpty(), "[null] — не плоский строковый массив");
    }

    @Test
    public void testEmptyAndNonEmptyArraysOnSamePath() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"o\": []}", "{\"o\": [{\"a\": 1}]}");
        assertTrue(path(analyzer, "$.o[*]").hasAnomaly(AnomalyDetector.METRIC_IS_EMPTY),
                "встреченный пустой массив фиксируется монотонно");
    }

    // ------------------------------------------------------------------ вложенные массивы

    /**
     * Регрессия: вложенный массив путь не меняет ({@code $.a[*]}) и маркер {@code [*]} со стека не снимает — снимает только массив,
     * который его поставил. Раньше первый внутренний {@code END_ARRAY} снимал маркер внешнего массива, и второй и следующие
     * внутренние массивы писались на корневой путь {@code $[*]}.
     */
    @Test
    public void testNestedArraysKeepPathBalance() {
        JsonSchemaAnalyzer analyzer = analyzedJson("{\"a\": [[1, 2], [3]], \"b\": \"x\", \"c\": {\"d\": 1}}");
        assertTrue(reportPaths(analyzer).contains("$.b"), "путь $.b после вложенных массивов: " + reportPaths(analyzer));
        assertTrue(reportPaths(analyzer).contains("$.c.d"), "путь $.c.d после вложенных массивов: " + reportPaths(analyzer));
        assertEquals(Set.of("$.a[*]", "$.b", "$.c.d"), reportPaths(analyzer));
    }

    @Test
    public void testNestedArraysOfObjectsAndRootNesting() {
        JsonSchemaAnalyzer objects = analyzedJson("{\"a\": [[{\"k\": 1}], [{\"k\": 2}], []], \"b\": 1}");
        assertEquals(Set.of("$.a[*]", "$.a[*].k", "$.b"), reportPaths(objects), "массивы объектов во вложенных массивах");
        assertTrue(path(objects, "$.a[*]").hasAnomaly(AnomalyDetector.METRIC_IS_EMPTY), "внутренний [] встретился");

        JsonSchemaAnalyzer root = analyzedJson("[[1, 2], [3]]");
        assertEquals(Set.of("$[*]"), reportPaths(root), "корневой массив массивов: один путь");
    }

    // ------------------------------------------------------------------ trace форм и JSON-строк

    @Test
    public void testStructureTraceHasFirstDocumentOfEveryForm() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, "{\"_id\": {\"$oid\": \"" + oid(1) + "\"}, \"p\": {\"a\": 1}}", PRESET);
        feed(analyzer, "{\"_id\": {\"$oid\": \"" + oid(2) + "\"}, \"p\": [{\"b\": 1}]}", PRESET);
        feed(analyzer, "{\"_id\": {\"$oid\": \"" + oid(3) + "\"}, \"p\": \"s\"}", PRESET);
        JsonNode trace = reportOf(analyzer).at("/$.p/anomalies/is_polymorphic_structure/trace");
        Set<String> seen = new TreeSet<>();
        trace.forEach(item -> seen.add(item.get("form").asText() + ":" + item.get("id").asText()));
        assertEquals(Set.of("ARRAY:" + oid(2), "OBJECT:" + oid(1), "SCALAR:" + oid(3)), seen);
    }

    @Test
    public void testJsonStringTraces() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, "{\"_id\": {\"$oid\": \"" + oid(1) + "\"}, \"m\": " + quote("{\"u\": 1}") + "}", PRESET);
        feed(analyzer, "{\"_id\": {\"$oid\": \"" + oid(2) + "\"}, \"m\": \"n/a\"}", PRESET);
        JsonNode m = reportOf(analyzer).get("$.m");
        assertEquals(oid(1), m.at("/anomalies/is_json_string/trace/0/id").asText(), "первая JSON-строка");
        assertEquals(oid(2), m.at("/anomalies/has_non_json_strings/trace/0/id").asText(), "первая обычная строка");
        assertTrue(m.has("path_trace"));
    }

    // ------------------------------------------------------------------ trace: источник id

    @Test
    public void testTraceExplicitByFieldName() {
        JsonSchemaAnalyzer analyzer = analyzed(AnomalyScenario.CLEAN, 1, "created_at");
        assertEquals(List.of(new Pair("2026-07-23T01:15:00", "created_at")), pathTrace(analyzer, "$.created_at"));
    }

    @Test
    public void testTraceExplicitMissingFieldGetsEmptyMarker() {
        JsonSchemaAnalyzer analyzer = analyzed(AnomalyScenario.CLEAN, 1, "doc_code");
        assertEquals(List.of(TraceEvidence.MARKER), pathTrace(analyzer, "$.customer_rating"));
        assertTrue(analyzer.buildJsonReport().contains("\"path_trace\":[{\"id\":\"\",\"id_key\":\"\"}]"));
    }

    @Test
    public void testTracePresetByOid() {
        JsonSchemaAnalyzer analyzer = analyzed(AnomalyScenario.CLEAN, 1, PRESET);
        assertEquals(List.of(new Pair(oid(1), "_id.$oid")), pathTrace(analyzer, "$.customer_rating"));
    }

    @Test
    public void testTraceSuffixFallback() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, "{\"user_id\": \"u1\", \"x\": 1}", PRESET);
        assertEquals(List.of(new Pair("u1", "user_id")), pathTrace(analyzer, "$.x"));
    }

    // ------------------------------------------------------------------ trace: независимые scopes

    @Test
    public void testAnomaliesHaveIndependentTrace() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, AnomalyScenario.CLEAN, 1, PRESET);        // payment_dates непуст: is_flat_string_array
        feed(analyzer, AnomalyScenario.EMPTY_ARRAY, 2, PRESET);  // payment_dates пуст: is_array_empty

        String p = "$.payment_dates[*]";
        assertEquals(List.of(new Pair(oid(1), "_id.$oid")), pathTrace(analyzer, p), "path_trace — первое появление пути");
        assertEquals(List.of(new Pair(oid(1), "_id.$oid")), anomalyTrace(analyzer, p, AnomalyDetector.METRIC_IS_STRING_ARRAY));
        assertEquals(List.of(new Pair(oid(2), "_id.$oid")), anomalyTrace(analyzer, p, AnomalyDetector.METRIC_IS_EMPTY),
                "аномалия получает id своей строки, а не строки появления пути");
    }

    @Test
    public void testOneRowFillsSeveralScopes() {
        JsonSchemaAnalyzer analyzer = analyzed(AnomalyScenario.DATE_AS_ARRAY, 7, PRESET);
        String p = "$.birth_date[*]";
        Pair pair = new Pair(oid(7), "_id.$oid");
        assertEquals(List.of(pair), pathTrace(analyzer, p));
        assertEquals(List.of(pair), anomalyTrace(analyzer, p, AnomalyDetector.METRIC_IS_DATE_PART));
        assertEquals(List.of(pair), anomalyTrace(analyzer, p, AnomalyDetector.METRIC_IS_STRING_ARRAY));
    }

    @Test
    public void testPolymorphicTraceCarriesFormat() throws Exception {
        JsonSchemaAnalyzer worker1 = analyzed(AnomalyScenario.CLEAN, 0, PRESET);
        JsonSchemaAnalyzer worker2 = analyzed(AnomalyScenario.DATE_AS_PLAIN, 1, PRESET);
        worker1.merge(worker2);

        JsonNode trace = MAPPER.readTree(worker1.buildJsonReport())
                .get("$.created_at").get("anomalies").get(ANOMALY_POLYMORPHIC_FORMAT).get("trace");
        assertEquals("[{\"id\":\"" + oid(1) + "\",\"id_key\":\"_id.$oid\",\"format\":\"DATE_ONLY\"},"
                        + "{\"id\":\"" + oid(0) + "\",\"id_key\":\"_id.$oid\",\"format\":\"LOCAL_DATETIME\"}]",
                trace.toString(), "полиморфизм, возникший при merge, имеет evidence каждого написания");
    }

    // ------------------------------------------------------------------ trace: merge

    @Test
    public void testTraceMergeSortingAndLimit() {
        JsonSchemaAnalyzer worker2 = analyzed(AnomalyScenario.CLEAN, 2, PRESET);
        worker2.merge(analyzed(AnomalyScenario.CLEAN, 1, PRESET));
        worker2.merge(analyzed(AnomalyScenario.CLEAN, 3, PRESET));

        assertEquals(List.of(new Pair(oid(1), "_id.$oid"), new Pair(oid(2), "_id.$oid")),
                pathTrace(worker2, "$.customer_rating"), "union, сортировка, лимит max_ids=2");
    }

    @Test
    public void testTraceMergeKeepsPairsAtomic() {
        JsonSchemaAnalyzer workerA = new JsonSchemaAnalyzer();
        feed(workerA, "{\"x\": 1, \"_id\": {\"$oid\": \"o1\"}}", PRESET);
        JsonSchemaAnalyzer workerB = new JsonSchemaAnalyzer();
        feed(workerB, "{\"id\": \"i1\", \"x\": 1}", PRESET);

        workerA.merge(workerB);

        assertEquals(List.of(new Pair("i1", "id"), new Pair("o1", "_id.$oid")), pathTrace(workerA, "$.x"),
                "каждый id сохраняет свой ключ-источник — ложной пары нет");
    }

    @Test
    public void testTraceMergeEmptyMarkerDoesNotEvictRealIds() {
        JsonSchemaAnalyzer workerA = new JsonSchemaAnalyzer();
        feed(workerA, "{\"x\": 1}", PRESET);
        JsonSchemaAnalyzer workerB = new JsonSchemaAnalyzer();
        feed(workerB, "{\"x\": 1, \"_id\": {\"$oid\": \"60b8d29f1a4c8b0000000001\"}}", PRESET);

        workerA.merge(workerB);
        assertEquals(List.of(new Pair(oid(1), "_id.$oid"), TraceEvidence.MARKER), pathTrace(workerA, "$.x"),
                "непустые первыми, маркер — в хвост при свободном слоте");

        JsonSchemaAnalyzer workerC = new JsonSchemaAnalyzer();
        feed(workerC, "{\"x\": 1, \"_id\": {\"$oid\": \"60b8d29f1a4c8b0000000002\"}}", PRESET);
        workerA.merge(workerC);
        assertEquals(List.of(new Pair(oid(1), "_id.$oid"), new Pair(oid(2), "_id.$oid")), pathTrace(workerA, "$.x"),
                "при заполнении слотов маркер вытесняется");
    }

    @Test
    public void testNormalModeHasNoTrace() {
        JsonSchemaAnalyzer analyzer = analyzed(AnomalyScenario.EMPTY_ARRAY, 1, null);
        PathMetrics metrics = path(analyzer, "$.payment_dates[*]");
        assertNull(metrics.getPathTrace());
        assertTrue(metrics.getAnomalyTrace().isEmpty());
        assertTrue(metrics.getFormatTrace().isEmpty());
    }

    @Test
    public void testFormatSetIsUsedForTraceOnlyOnce() {
        // Формат, уже доказанный на пути, не добавляет id повторно (trace — первое появление)
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        feed(analyzer, AnomalyScenario.CLEAN, 5, PRESET);
        feed(analyzer, AnomalyScenario.CLEAN, 3, PRESET);
        assertEquals(List.of(new Pair(oid(5), "_id.$oid")),
                path(analyzer, "$.created_at").getFormatTrace().get(ValueFormat.LOCAL_DATETIME).items());
    }
}
