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

        assertEquals("2.0", root.get("schema_version").asText());

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
