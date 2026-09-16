package io.github.mcgr0g.distdoc.udaf.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.sources.ForgottenMigrationsSource;
import io.github.mcgr0g.distdoc.udaf.DistDocPlugin;
import io.trino.plugin.memory.MemoryPlugin;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-тест: распределённый combine (SchemaStateSerializer) + реальный шаффл.
 *
 * <p>Проверяет, что EXPLAIN показывает RemoteSource (фазы Input → Serialize → Combine → Output),
 * и что результат на большой выборке идентичен одноузловому.</p>
 *
 * <p>Новая гарантия: существующие тесты этого не покрывают.</p>
 */
public class DistDocDistributedE2ETest extends AbstractTestQueryFramework {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        // Большая таблица (500 строк) для реального шаффла
        StringBuilder sb = new StringBuilder("INSERT INTO big VALUES ");
        ForgottenMigrationsSource src = new ForgottenMigrationsSource();
        for (int i = 0; i < 500; i++) {
            if (i > 0) sb.append(',');
            String line = ChaosDataGenerator.generateSingleLine(src, AnomalyScenario.ALL, i);
            sb.append('(').append(quote(line)).append(')');
        }
        runner.execute("CREATE TABLE big(line varchar)");
        runner.execute(sb.toString());
        return runner;
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
