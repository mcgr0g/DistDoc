package io.github.mcgr0g.distdoc.udaf;

import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.sources.ForgottenMigrationsSource;
import io.airlift.slice.Slices;
import io.github.mcgr0g.distdoc.udaf.formats.ValueFormat;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip внутреннего состояния, алгебра merge (docs/testing/tracing.md) и fault tolerance
 * разбора (паттерн 5, docs/patterns/plugin.md): analyzer → state JSON → analyzer → report.
 */
public class SchemaStateSerializerTest {

    private static final ForgottenMigrationsSource SOURCE = new ForgottenMigrationsSource();

    /** Строка фикстуры; checked-исключение генератора оборачивается (тест падает с причиной). */
    private static String line(AnomalyScenario scenario, long index) {
        try {
            return ChaosDataGenerator.generateSingleLine(SOURCE, scenario, index);
        } catch (Exception e) {
            throw new IllegalStateException("Генератор фикстур не выдал строку " + scenario + "/" + index, e);
        }
    }

    /** Воркер: строки заданных сценариев с индексами {@code from..to-1} в trace-пресет режиме. */
    private static JsonSchemaAnalyzer worker(AnomalyScenario scenario, int from, int to) {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        for (int i = from; i < to; i++) {
            String json = line(scenario, i);
            analyzer.analyze(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), Slices.utf8Slice(""));
        }
        return analyzer;
    }

    /** Глубокая копия через сериализацию состояния (merge мутирует левый операнд). */
    private static JsonSchemaAnalyzer copy(JsonSchemaAnalyzer analyzer) {
        return JsonSchemaAnalyzer.fromStateJson(analyzer.buildStateJson());
    }

    private static String merged(JsonSchemaAnalyzer... parts) {
        JsonSchemaAnalyzer acc = copy(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            acc.merge(copy(parts[i]));
        }
        return acc.buildJsonReport();
    }

    private static JsonSchemaAnalyzer a() { return worker(AnomalyScenario.CLEAN, 0, 3); }
    private static JsonSchemaAnalyzer b() { return worker(AnomalyScenario.DATE_AS_PLAIN, 3, 6); }
    private static JsonSchemaAnalyzer c() { return worker(AnomalyScenario.EMPTY_ARRAY, 6, 9); }
    private static JsonSchemaAnalyzer d() { return worker(AnomalyScenario.DATE_AT_UNIX, 9, 12); }

    @Test
    public void testRoundTripPreservesReport() {
        JsonSchemaAnalyzer original = worker(AnomalyScenario.ALL, 0, 30);
        original.merge(d());
        String report = original.buildJsonReport();
        assertTrue(report.contains("is_polymorphic_format"), "проверяется полная форма отчёта");
        assertTrue(report.contains("\"format\":"), "проверяется trace полиморфизма");

        JsonSchemaAnalyzer restored = copy(original);
        assertEquals(report, restored.buildJsonReport(), "analyzer → state → analyzer не меняет отчёт");
        assertEquals(original.buildStateJson(), restored.buildStateJson(), "состояние стабильно при повторном round-trip");
    }

    @Test
    public void testStateOmitsDerivedAndKeepsFormatTrace() {
        JsonSchemaAnalyzer analyzer = a();
        analyzer.merge(b());
        String state = analyzer.buildStateJson();
        assertFalse(state.contains("is_polymorphic_format"), "вычисляемая аномалия в состояние не пишется");
        assertTrue(state.contains("\"format_trace\""), "trace форматов передаётся между воркерами");
        assertFalse(analyzer.buildJsonReport().contains("format_trace"), "в отчёт format_trace не попадает");
    }

    @Test
    public void testMergeIsCommutativeAndAssociative() {
        JsonSchemaAnalyzer a = a(), b = b(), c = c(), d = d();
        String expected = merged(a, b, c, d);

        assertEquals(expected, merged(d, c, b, a), "коммутативность");
        assertEquals(expected, merged(b, d, a, c), "коммутативность");

        // Ассоциативность: (a⊕b)⊕(c⊕d) и a⊕(b⊕(c⊕d))
        JsonSchemaAnalyzer ab = copy(a);
        ab.merge(copy(b));
        JsonSchemaAnalyzer cd = copy(c);
        cd.merge(copy(d));
        ab.merge(cd);
        assertEquals(expected, ab.buildJsonReport(), "ассоциативность (a⊕b)⊕(c⊕d)");

        JsonSchemaAnalyzer bcd = copy(b);
        bcd.merge(copy(c));
        bcd.merge(copy(d));
        JsonSchemaAnalyzer right = copy(a);
        right.merge(bcd);
        assertEquals(expected, right.buildJsonReport(), "ассоциативность a⊕(b⊕c⊕d)");
    }

    @Test
    public void testMergeIsIdempotent() {
        JsonSchemaAnalyzer a = a();
        a.merge(b());
        String once = a.buildJsonReport();
        a.merge(copy(a));
        assertEquals(once, a.buildJsonReport(), "a⊕a = a");
    }

    @Test
    public void testUnknownFieldSkipsOnlyThatPath() {
        // Плоский флаг 1.x (воркер старой версии) — путь пропускается, соседние пути сохраняются
        String state = "{\"schema_version\":\"3.0\","
                + "\"$.x\":{\"types\":[\"INTEGER\"],\"max_length\":0,\"is_array_empty\":true},"
                + "\"$.y\":{\"types\":[\"VARCHAR\"],\"max_length\":3,\"observed_formats\":[\"DATE_ONLY\"]}}";
        JsonSchemaAnalyzer restored = JsonSchemaAnalyzer.fromStateJson(state);
        assertFalse(restored.getSchemaMap().containsKey("$.x"), "путь с полем вне whitelist пропущен");
        PathMetrics y = restored.getSchemaMap().get("$.y");
        assertNotNull(y, "соседний путь сохранён");
        assertEquals(Set.of(ValueFormat.DATE_ONLY), y.getFormats());
        assertTrue(y.getAnomalies().isEmpty(), "чужое поле не превращается в ложную аномалию");
    }

    @Test
    public void testUnknownFormatSkipsOnlyThatPath() {
        String state = "{\"$.x\":{\"types\":[\"VARCHAR\"],\"max_length\":1,\"observed_formats\":[\"ISO8601\"]},"
                + "\"$.y\":{\"types\":[\"INTEGER\"],\"max_length\":0}}";
        JsonSchemaAnalyzer restored = JsonSchemaAnalyzer.fromStateJson(state);
        assertEquals(Set.of("$.y"), restored.getSchemaMap().keySet());
    }

    @Test
    public void testBusinessPathsAreNotValidated() {
        // Ключи-пути — данные: объект, ставший массивом объектов с другими полями, — просто разные пути
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        for (String json : List.of("{\"x\": {\"a\": 1}}", "{\"x\": [{\"b\": \"s\"}, {\"c\": true}]}", "{\"x\": \"str\"}")) {
            analyzer.analyze(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        }
        String report = analyzer.buildJsonReport();
        assertEquals(report, copy(analyzer).buildJsonReport(), "полиморфизм объектов переживает round-trip");
        assertEquals(Set.of("$.x", "$.x.a", "$.x[*]", "$.x[*].b", "$.x[*].c"), copy(analyzer).getSchemaMap().keySet());
        assertEquals("VARCHAR", copy(analyzer).getSchemaMap().get("$.x").getFinalType(), "OBJECT + строка → VARCHAR");
        assertEquals("ARRAY", copy(analyzer).getSchemaMap().get("$.x[*]").getFinalType());
    }

    @Test
    public void testUnknownTypeSkipsOnlyThatPath() {
        // Старое поле type (воркер другой версии) и неизвестное имя типа — путь пропускается, соседний сохраняется
        String state = "{\"$.x\":{\"type\":\"VARCHAR\",\"max_length\":1},"
                + "\"$.w\":{\"types\":[\"WEIRD\"],\"max_length\":0},"
                + "\"$.y\":{\"types\":[\"INTEGER\"],\"max_length\":0}}";
        assertEquals(Set.of("$.y"), JsonSchemaAnalyzer.fromStateJson(state).getSchemaMap().keySet());
    }

    @Test
    public void testStateCarriesTypeSetNotCollapsedType() {
        JsonSchemaAnalyzer analyzer = analyzed("{\"o\": {\"a\": 1}}", "{\"o\": \"s\"}");
        String state = analyzer.buildStateJson();
        assertTrue(state.contains("\"types\":[\"OBJECT\",\"VARCHAR\"]"), "состояние несёт набор типов: " + state);
        assertEquals(Set.of("OBJECT", "VARCHAR"), copy(analyzer).getSchemaMap().get("$.o").getTypes());
    }

    @Test
    public void testJsonStringAndObjectSurviveRoundTrip() {
        JsonSchemaAnalyzer analyzer = analyzed("{\"o\": {\"a\": 1}, \"m\": \"{\\\"u\\\": 1}\"}");
        assertEquals(analyzer.buildJsonReport(), copy(analyzer).buildJsonReport(), "формы и is_json_string переживают round-trip");
        assertEquals(Set.of(PathMetrics.TYPE_JSON_STRING, PathMetrics.TYPE_JSON_OBJECT),
                copy(analyzer).getSchemaMap().get("$.m").getTypes());
        assertTrue(copy(analyzer).getSchemaMap().get("$.m").isJsonString());
    }

    @Test
    public void testFormSeenByOneWorkerIsNotLostOnMerge() {
        // Регрессия: воркер, видевший и объект, и скаляр, не должен терять форму «объект» при слиянии
        // с воркером, видевшим массив (раньше состояние передавало только свёрнутый VARCHAR)
        JsonSchemaAnalyzer both = analyzed("{\"x\": {\"a\": 1}}", "{\"x\": \"s\"}");
        JsonSchemaAnalyzer arrays = analyzed("{\"x\": [1]}");
        for (String report : List.of(merged(both, arrays), merged(arrays, both))) {
            assertTrue(report.contains("\"is_polymorphic_structure\""), "неоднородность потеряна: " + report);
        }
        assertEquals(merged(both, arrays), merged(arrays, both), "слияние производных аномалий коммутативно");
        JsonSchemaAnalyzer acc = copy(both);
        acc.merge(copy(arrays));
        assertEquals(Set.of("ARRAY", "OBJECT", "SCALAR"), acc.structureForms("$.x"));
    }

    @Test
    public void testPlainStringOnOtherWorkerGivesNonJsonStringsAfterMerge() {
        JsonSchemaAnalyzer json = analyzed("{\"m\": \"{\\\"u\\\": 1}\"}");
        JsonSchemaAnalyzer plain = analyzed("{\"m\": \"n/a\"}");
        String report = merged(json, plain);
        assertTrue(report.contains("\"has_non_json_strings\""), report);
        assertEquals(report, merged(plain, json));
        assertFalse(merged(json, json).contains("has_non_json_strings"), "без обычных строк аномалии нет");
    }

    @Test
    public void testStructureTraceSurvivesRoundTrip() {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        for (String row : List.of("{\"_id\": {\"$oid\": \"a1\"}, \"p\": {\"k\": 1}}",
                "{\"_id\": {\"$oid\": \"a2\"}, \"p\": [{\"k\": 1}]}")) {
            analyzer.analyze(new ByteArrayInputStream(row.getBytes(StandardCharsets.UTF_8)), Slices.utf8Slice(""));
        }
        String report = analyzer.buildJsonReport();
        assertTrue(report.contains("\"form\":\"OBJECT\"") && report.contains("\"form\":\"ARRAY\""), report);
        assertEquals(report, copy(analyzer).buildJsonReport(), "type_trace переживает round-trip");
    }

    private static JsonSchemaAnalyzer analyzed(String... rows) {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        for (String row : rows) {
            analyzer.analyze(new ByteArrayInputStream(row.getBytes(StandardCharsets.UTF_8)));
        }
        return analyzer;
    }

    @Test
    public void testCorruptedStateGivesEmptyWorker() {
        JsonSchemaAnalyzer restored = assertDoesNotThrow(() -> JsonSchemaAnalyzer.fromStateJson("{\"$.x\": {"));
        assertTrue(restored.getSchemaMap().isEmpty(), "невалидный JSON — пустое состояние, запрос продолжается");
    }

    @Test
    public void testTraceSurvivesRoundTrip() {
        JsonSchemaAnalyzer analyzer = c();
        JsonSchemaAnalyzer restored = copy(analyzer);
        PathMetrics metrics = restored.getSchemaMap().get("$.payment_dates[*]");
        assertEquals(List.of(new TraceEvidence.Pair("60b8d29f1a4c8b0000000006", "_id.$oid")),
                metrics.getAnomalyTrace().get("is_array_empty").items());
        assertEquals(List.of(new TraceEvidence.Pair("60b8d29f1a4c8b0000000006", "_id.$oid")),
                metrics.getPathTrace().items());
    }
}
