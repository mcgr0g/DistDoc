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

        // Пресет из app-config.toml включает _id.$oid
        JsonNode oidPath = root.get("$._id.$oid");
        assertTrue(oidPath != null && oidPath.has("trace_ids"), "trace_ids отсутствуют для $._id.$oid: " + rx);
        assertTrue(oidPath.has("trace_id_key"), "trace_id_key отсутствует для $._id.$oid: " + rx);
    }

    @Test
    public void traceExplicitFieldReturnsTraceIds() throws Exception {
        // surrogate_pk присутствует в ~50% строк фикстур ALL
        String rx = (String) computeActual("SELECT analyze_json_schema(line, trace('surrogate_pk')) FROM crm_combined").getOnlyValue();
        System.out.println("RX-TRACE-EXPLICIT: " + rx);

        JsonNode root = MAPPER.readTree(rx);
        JsonNode surrogate = root.get("$.surrogate_pk");
        assertTrue(surrogate != null && surrogate.has("trace_ids"), "trace_ids отсутствуют для $.surrogate_pk: " + rx);

        JsonNode traceKey = surrogate.get("trace_id_key");
        assertTrue(traceKey != null, "trace_id_key отсутствует: " + rx);
        assertEquals("surrogate_pk", traceKey.asText(), "trace_id_key должен быть 'surrogate_pk': " + rx);
    }

    @Test
    public void normalModeHasNoTraceIds() throws Exception {
        String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM crm_combined").getOnlyValue();
        JsonNode root = MAPPER.readTree(rx);

        root.properties().forEach(entry -> {
            if (!"schema_version".equals(entry.getKey())) {
                JsonNode value = entry.getValue();
                assertFalse(value.has("trace_ids"), "trace_ids присутствуют в обычном режиме: " + entry.getKey());
            }
        });
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

}
