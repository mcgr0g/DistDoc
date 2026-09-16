package io.github.mcgr0g.distdoc.udaf.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.sources.ForgottenMigrationsSource;
import io.github.mcgr0g.distdoc.udaf.DistDocPlugin;
import io.trino.plugin.memory.MemoryPlugin;
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Set;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-тест: UDAF analyze_json_schema над хаос-фикстурами, валидация rx-data
 * против канонического контракта {@code docs/contracts/rx-data.schema.json}.
 *
 * <p>Заменяет {@code mise run lc-schema}.</p>
 */
public class DistDocQueryE2ETest extends AbstractTestQueryFramework {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchema SCHEMA = loadSchema();

    @Override
    protected QueryRunner createQueryRunner() throws Exception {
        QueryRunner runner = DistributedQueryRunner.builder(
                testSessionBuilder().setCatalog("memory").setSchema("default").build()).build();
        runner.installPlugin(new MemoryPlugin());
        runner.createCatalog("memory", "memory", ImmutableMap.of());
        runner.installPlugin(new DistDocPlugin());

        // Генерируем хаос-данные тем же генератором, что lc-gen
        runner.execute("CREATE TABLE crm_combined(line varchar)");
        StringBuilder sb = new StringBuilder("INSERT INTO crm_combined VALUES ");
        ForgottenMigrationsSource src = new ForgottenMigrationsSource();
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(',');
            String line = ChaosDataGenerator.generateSingleLine(src, AnomalyScenario.ALL, i);
            sb.append('(').append(quote(line)).append(')');
        }
        runner.execute(sb.toString());
        return runner;
    }

    @Test
    public void rxDataValidAgainstSchema() {
        String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM crm_combined").getOnlyValue();
        System.out.println("RX-DATA: " + rx);

        assertTrue(rx.contains("schema_version"), "отсутствует schema_version: " + rx);
        assertTrue(rx.contains("$._id.$oid"), "отсутствует $._id.$oid: " + rx);
        assertTrue(rx.contains("$.customer_rating"), "отсутствует $.customer_rating: " + rx);

        // Валидация против rx-data.schema.json
        Set<ValidationMessage> errors = validate(rx);
        assertEquals(0, errors.size(), "rx-data не прошёл валидацию: " + errors);
    }

    @Test
    public void anomalyFlagsPresent() {
        String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM crm_combined").getOnlyValue();

        // Фикстуры ALL содержат все аномалии
        assertTrue(rx.contains("is_date_part_array"), "отсутствует is_date_part_array: " + rx);
        assertTrue(rx.contains("is_array_empty"), "отсутствует is_array_empty: " + rx);
    }

    private Set<ValidationMessage> validate(String reportJson) {
        try {
            JsonNode node = MAPPER.readTree(reportJson);
            return SCHEMA.validate(node);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось распарсить rx-data: " + e.getMessage(), e);
        }
    }

    private static JsonSchema loadSchema() {
        try (InputStream is = DistDocQueryE2ETest.class.getClassLoader()
                .getResourceAsStream("rx-data.schema.json")) {
            if (is == null) {
                throw new IllegalStateException("rx-data.schema.json не найден в ресурсах");
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(is);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось загрузить rx-data.schema.json: " + e.getMessage(), e);
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
