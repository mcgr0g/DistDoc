package io.github.mcgr0g.distdoc.udaf.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.sources.ForgottenMigrationsSource;
import io.github.mcgr0g.distdoc.udaf.DistDocPlugin;
import io.github.mcgr0g.distdoc.udaf.JsonSchemaAnalyzer;
import io.trino.plugin.memory.MemoryPlugin;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-тест: распределённый combine (SchemaStateSerializer) + реальный шаффл.
 *
 * <p>Проверяет, что EXPLAIN показывает RemoteSource (фазы Input → Serialize → Combine → Output),
 * и что результат на большой выборке идентичен одноузловому.</p>
 *
 * <p>Результат сверяется с одноузловым прогоном того же анализатора; trace на смешанных
 * id-источниках ({@code trace_mixed_sources}) — по инвариантам пар {@code {id, id_key}}.</p>
 */
public class DistDocDistributedE2ETest extends AbstractTestQueryFramework {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Строки таблиц — для одноузлового эталона тем же анализатором. */
    private static final Map<String, List<String>> LINES = new HashMap<>();

    @Override
    protected QueryRunner createQueryRunner() throws Exception {
        QueryRunner runner = DistributedQueryRunner.builder(
                testSessionBuilder().setCatalog("memory").setSchema("default").build()).build();
        runner.installPlugin(new MemoryPlugin());
        runner.createCatalog("memory", "memory", ImmutableMap.of());
        runner.installPlugin(new DistDocPlugin());

        // Небольшая таблица для проверки плана
        runner.execute("CREATE TABLE t(line varchar)");
        runner.execute("INSERT INTO t VALUES ('{\"a\":\"x\"}'), ('{\"a\":\"yy\"}')");

        // Большие таблицы для реального шаффла: режим all (500 строк) и смешанные id-источники
        // trace_mixed_sources (400 строк, fixture-matrix.md §3)
        createTable(runner, "big", AnomalyScenario.ALL, 500);
        createTable(runner, "mixed", AnomalyScenario.TRACE_MIXED_SOURCES, 400);
        return runner;
    }

    private void createTable(QueryRunner runner, String table, AnomalyScenario scenario, int count) throws Exception {
        StringBuilder sb = new StringBuilder("INSERT INTO " + table + " VALUES ");
        ForgottenMigrationsSource src = new ForgottenMigrationsSource();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            String line = ChaosDataGenerator.generateSingleLine(src, scenario, i);
            lines.add(line);
            sb.append('(').append(quote(line)).append(')');
        }
        runner.execute("CREATE TABLE " + table + "(line varchar)");
        runner.execute(sb.toString());
        LINES.put(table, lines);
    }

    @Test
    public void clusterHasMultipleNodes() {
        long nodes = (long) computeActual("SELECT count(*) FROM system.runtime.nodes").getOnlyValue();
        System.out.println("NODES: " + nodes);
        assertTrue(nodes >= 3, "кластер должен содержать минимум 3 ноды, получено: " + nodes);
    }

    @Test
    public void distributedPlanContainsRemoteSource() {
        String plan = (String) computeActual("EXPLAIN (TYPE DISTRIBUTED) SELECT analyze_json_schema(line) FROM t").getOnlyValue();
        System.out.println("PLAN:\n" + plan);

        assertTrue(plan.contains("RemoteSource"), "план не содержит RemoteSource — шаффл отсутствует: " + plan);
        assertTrue(plan.contains("Fragment"), "план не содержит Fragment: " + plan);
    }

    @Test
    public void combinePhaseProducesCorrectResult() throws Exception {
        // Большая таблица → несколько сплитов → реальный шаффл частичных состояний
        String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM big").getOnlyValue();
        System.out.println("RX-BIG (first 500 chars): " + rx.substring(0, Math.min(500, rx.length())));

        JsonNode root = MAPPER.readTree(rx);
        assertTrue(root.has("schema_version"), "отсутствует schema_version");

        // Проверяем ключевые поля из фикстур
        assertTrue(root.has("$._id.$oid"), "отсутствует $._id.$oid");
        assertTrue(root.has("$.customer_rating"), "отсутствует $.customer_rating");

        // max_length должен быть корректно объединён через combine
        JsonNode oid = root.get("$._id.$oid");
        assertTrue(oid != null && oid.has("max_length"), "отсутствует max_length для $._id.$oid");
        int maxLength = oid.get("max_length").asInt();
        assertTrue(maxLength > 0, "max_length должен быть > 0, получено: " + maxLength);
    }

    @Test
    public void resultOnBigTableMatchesContract() throws Exception {
        String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM big").getOnlyValue();
        JsonNode root = MAPPER.readTree(rx);

        // Тип customer_rating должен быть DOUBLE (числовая решётка: INTEGER + DOUBLE → DOUBLE)
        JsonNode rating = root.get("$.customer_rating");
        assertTrue(rating != null, "отсутствует $.customer_rating");
        assertEquals("DOUBLE", rating.get("type").asText(), "тип customer_rating должен быть DOUBLE");

        // Узлы объектов и обе ветки полиморфного поля переживают шаффл (контракт 3.0, раздел 2b)
        assertEquals("OBJECT", root.at("/$.counterparties/type").asText(), "объектная ветка после combine");
        assertEquals("ARRAY", root.at("/$.counterparties[*]/type").asText(), "массивная ветка после combine");
        assertEquals("OBJECT", root.at("/$._id/type").asText(), "узел $._id после combine");

        // Полиморфизм форматов переживает шаффл: множество форматов — union частичных состояний
        JsonNode createdAt = root.get("$.created_at");
        assertEquals("[\"DATE_ONLY\",\"LOCAL_DATETIME\",\"OFFSET_DATETIME\",\"UNIX_MILLIS\"]",
                createdAt.get("observed_formats").toString(),
                "observed_formats created_at после combine");
        assertTrue(createdAt.at("/anomalies/is_polymorphic_format/detected").asBoolean(),
                "is_polymorphic_format после combine");
    }

    @Test
    public void distributedReportEqualsSingleNode() throws Exception {
        // Обычный отчёт не зависит от разбиения на сплиты и порядка combine:
        // типы, длины, форматы и аномалии — объединения частичных состояний
        for (String table : List.of("big", "mixed")) {
            String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM " + table).getOnlyValue();
            JsonSchemaAnalyzer single = new JsonSchemaAnalyzer();
            LINES.get(table).forEach(line -> single.analyze(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8))));
            if (!MAPPER.readTree(single.buildJsonReport()).equals(MAPPER.readTree(rx))) {
                throw new AssertionError("распределённый отчёт " + table + " отличается от одноузлового:\n"
                        + rx + "\n" + single.buildJsonReport());
            }
        }
    }

    @Test
    public void mixedSourcesTracePairsSurviveShuffle() throws Exception {
        // trace-id зависят от того, какая строка первой встретилась на воркере, поэтому
        // сравниваются не значения, а инварианты: каждая пара согласована по префиксу id,
        // путь одного источника несёт id только этого источника (fixture-matrix.md §3)
        String rx = (String) computeActual("SELECT analyze_json_schema(line, trace()) FROM mixed").getOnlyValue();
        JsonNode root = MAPPER.readTree(rx);
        root.properties().forEach(entry -> {
            if ("schema_version".equals(entry.getKey())) {
                return;
            }
            assertConsistent(entry.getKey(), entry.getValue().get("path_trace"), rx);
            entry.getValue().path("anomalies").properties().forEach(a ->
                    assertConsistent(entry.getKey() + "/" + a.getKey(), a.getValue().get("trace"), rx));
        });
        assertOnlyKey(root.at("/$.id/path_trace"), "id", rx);
        assertOnlyKey(root.at("/$.order_id/path_trace"), "order_id", rx);
        assertOnlyKey(root.at("/$._id.$oid/path_trace"), "_id.$oid", rx);
    }

    private static void assertConsistent(String scope, JsonNode trace, String rx) {
        assertTrue(trace != null && trace.isArray() && !trace.isEmpty(), "нет trace в " + scope + ": " + rx);
        trace.forEach(item -> {
            String id = item.get("id").asText();
            String key = item.get("id_key").asText();
            String expected = id.isEmpty() ? "" : id.startsWith("60b8") ? "_id.$oid"
                    : id.startsWith("id-") ? "id" : id.startsWith("ord-") ? "order_id" : "?";
            assertEquals(expected, key, "ложная пара в " + scope + ": " + item + " / " + rx);
        });
    }

    private static void assertOnlyKey(JsonNode trace, String key, String rx) {
        assertTrue(trace.isArray() && !trace.isEmpty(), "нет path_trace для источника " + key + ": " + rx);
        trace.forEach(item -> assertEquals(key, item.get("id_key").asText(), "чужой источник на пути " + key + ": " + rx));
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message);
        }
    }
}
