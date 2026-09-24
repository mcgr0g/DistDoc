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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-тест: trace-перегрузка analyze_json_schema(line, trace(...)) и env-override.
 *
 * <p>Заменяет {@code mise run lc-trace}.</p>
 */
public class DistDocTraceE2ETest extends AbstractTestQueryFramework {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected QueryRunner createQueryRunner() throws Exception {
        QueryRunner runner = DistributedQueryRunner.builder(
                testSessionBuilder().setCatalog("memory").setSchema("default").build()).build();
        runner.installPlugin(new MemoryPlugin());
        runner.createCatalog("memory", "memory", ImmutableMap.of());
        runner.installPlugin(new DistDocPlugin());

        runner.execute("CREATE TABLE crm_combined(line varchar)");
        StringBuilder sb = new StringBuilder("INSERT INTO crm_combined VALUES ");
        ForgottenMigrationsSource src = new ForgottenMigrationsSource();
        for (int i = 0; i < 50; i++) {
            if (i > 0) sb.append(',');
            String line = ChaosDataGenerator.generateSingleLine(src, AnomalyScenario.ALL, i);
            sb.append('(').append(quote(line)).append(')');
        }
        runner.execute(sb.toString());
        return runner;
    }

    @Test
    public void tracePresetReturnsTraceIds() throws Exception {
        String rx = (String) computeActual("SELECT analyze_json_schema(line, trace()) FROM crm_combined").getOnlyValue();
        System.out.println("RX-TRACE-PRESET: " + rx);

        JsonNode root = MAPPER.readTree(rx);
        assertTrue(root.has("schema_version"), "отсутствует schema_version");

        // Пресет из app-config.toml включает _id.$oid: каждая пара во всех scope-ах — с этим ключом
        JsonNode oidPath = root.get("$._id.$oid");
        assertTrue(oidPath != null && oidPath.has("path_trace"), "path_trace отсутствует для $._id.$oid: " + rx);
        root.properties().forEach(entry -> {
            if (!"schema_version".equals(entry.getKey())) {
                assertPairs(entry.getKey(), entry.getValue().get("path_trace"), "_id.$oid", rx);
                JsonNode anomalies = entry.getValue().get("anomalies");
                if (anomalies != null) {
                    anomalies.properties().forEach(a -> {
                        JsonNode trace = a.getValue().get("trace");
                        assertTrue(trace != null, "у аномалии " + a.getKey() + " на " + entry.getKey() + " нет trace: " + rx);
                        assertPairs(entry.getKey() + "/" + a.getKey(), trace, "_id.$oid", rx);
                    });
                }
            }
        });

        // Полиморфизм created_at: trace несёт написание каждого документа
        JsonNode polyTrace = root.at("/$.created_at/anomalies/is_polymorphic_format/trace");
        assertTrue(polyTrace.isArray() && polyTrace.size() > 0, "нет trace is_polymorphic_format: " + rx);
        polyTrace.forEach(item -> assertTrue(item.has("format"), "элемент trace полиморфизма без format: " + rx));
    }

    /** Каждая пара scope-а согласована: id непустой ↔ ожидаемый ключ, маркер "" ↔ "". */
    private static void assertPairs(String scope, JsonNode trace, String expectedKey, String rx) {
        if (trace == null) {
            return;
        }
        trace.forEach(item -> {
            String id = item.get("id").asText();
            String key = item.get("id_key").asText();
            assertEquals(id.isEmpty() ? "" : expectedKey, key, "ложная пара в " + scope + ": " + item + " / " + rx);
        });
    }

    @Test
    public void traceExplicitFieldReturnsTraceIds() throws Exception {
        // surrogate_pk присутствует в ~50% строк фикстур ALL
        String rx = (String) computeActual("SELECT analyze_json_schema(line, trace('surrogate_pk')) FROM crm_combined").getOnlyValue();
        System.out.println("RX-TRACE-EXPLICIT: " + rx);

        JsonNode root = MAPPER.readTree(rx);
        JsonNode surrogate = root.get("$.surrogate_pk");
        assertTrue(surrogate != null && surrogate.has("path_trace"), "path_trace отсутствует для $.surrogate_pk: " + rx);
        JsonNode first = surrogate.get("path_trace").get(0);
        assertEquals("surrogate_pk", first.get("id_key").asText(), "id_key должен быть 'surrogate_pk': " + rx);
        assertTrue(first.get("id").asText().startsWith("sp-"), "id из поля surrogate_pk: " + rx);

        // Строки без поля дают маркер {"", ""}; ключ и id не рвутся ни в одном scope
        root.properties().forEach(entry -> {
            if (!"schema_version".equals(entry.getKey())) {
                assertPairs(entry.getKey(), entry.getValue().get("path_trace"), "surrogate_pk", rx);
            }
        });
    }

    @Test
    public void normalModeHasNoTraceIds() throws Exception {
        String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM crm_combined").getOnlyValue();
        JsonNode root = MAPPER.readTree(rx);

        root.properties().forEach(entry -> {
            if (!"schema_version".equals(entry.getKey())) {
                JsonNode value = entry.getValue();
                assertFalse(value.has("path_trace"), "path_trace в обычном режиме: " + entry.getKey());
                assertFalse(value.toString().contains("\"trace\""), "trace аномалии в обычном режиме: " + entry.getKey());
            }
        });
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

}
