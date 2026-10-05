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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-тест: UDAF analyze_json_schema над хаос-фикстурами, валидация rx-data
 * против канонического контракта {@code docs/contracts/rx-data.schema.json}.
 *
 * <p>Заменяет {@code mise run lc-schema}.</p>
 */
public class DistDocQueryE2ETest extends AbstractTestQueryFramework {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Канонический контракт rx-data (docs/contracts подключён к ресурсам e2e). */
    private static final String SCHEMA_RESOURCE = "rx-data.schema.json";
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

        // Фикстуры ALL содержат все аномалии; в контракте 3.0 — объекты внутри anomalies
        JsonNode root = readTree(rx);
        assertTrue(root.at("/$.birth_date[*]/anomalies/is_date_part_array/detected").asBoolean(), "нет is_date_part_array: " + rx);
        assertTrue(root.at("/$.payment_dates[*]/anomalies/is_array_empty/detected").asBoolean(), "нет is_array_empty: " + rx);

        // created_at: группы i%4 режима all -> четыре написания (fixture-matrix.md §4)
        JsonNode createdAt = root.get("$.created_at");
        assertEquals("[\"DATE_ONLY\",\"LOCAL_DATETIME\",\"OFFSET_DATETIME\",\"UNIX_MILLIS\"]",
                createdAt.get("observed_formats").toString(), "observed_formats: " + rx);
        assertTrue(createdAt.at("/anomalies/is_polymorphic_format/detected").asBoolean(), "нет is_polymorphic_format: " + rx);
        assertFalse(root.get("$.customer_rating").has("observed_formats"), "ложный формат на customer_rating: " + rx);
    }

    @Test
    public void structurePolymorphismAndJsonStringPresent() {
        String rx = (String) computeActual("SELECT analyze_json_schema(line) FROM crm_combined").getOnlyValue();
        JsonNode root = readTree(rx);

        // Чистые объекты записей не имеют; BSON-обёртка — лист
        assertTrue(root.at("/$._id").isMissingNode() && root.at("/$.doc_meta").isMissingNode(), "запись чистого объекта: " + rx);
        assertTrue(root.has("$._id.$oid"), "нет листа $._id.$oid: " + rx);
        assertTrue(root.at("/$.metadata_encoded/anomalies/is_json_string/detected").asBoolean(), "нет is_json_string: " + rx);

        // counterparties: i%3==0 — массив объектов, иначе объект → неоднородное поле, ветки независимы
        assertEquals("OBJECT", root.at("/$.counterparties/type").asText(), "нет записи неоднородного поля: " + rx);
        assertTrue(root.at("/$.counterparties/anomalies/is_polymorphic_structure/detected").asBoolean(), "нет флага: " + rx);
        assertEquals("VARCHAR", root.at("/$.counterparties.name/type").asText());
        assertEquals("ARRAY", root.at("/$.counterparties[*]/type").asText(), "нет массивной ветки: " + rx);
        assertEquals("INTEGER", root.at("/$.counterparties[*].share/type").asText());
        assertTrue(root.at("/$.counterparties[*]/anomalies").isMissingNode(), "массив объектов не пуст: " + rx);
        assertTrue(root.at("/$.counterparties.share").isMissingNode() && root.at("/$.counterparties[*].name").isMissingNode(),
                "ветки пересеклись: " + rx);

        // birth_date: скаляр в чётных строках, массив частиц в нечётных
        assertTrue(root.at("/$.birth_date/anomalies/is_polymorphic_structure/detected").asBoolean(), "нет флага у birth_date: " + rx);
    }

    private static JsonNode readTree(String rx) {
        try {
            return MAPPER.readTree(rx);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось распарсить rx-data: " + e.getMessage(), e);
        }
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
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " не найден в ресурсах");
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(is);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось загрузить " + SCHEMA_RESOURCE + ": " + e.getMessage(), e);
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
