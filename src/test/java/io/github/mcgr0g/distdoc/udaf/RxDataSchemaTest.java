package io.github.mcgr0g.distdoc.udaf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.airlift.slice.Slices;
import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.sources.ForgottenMigrationsSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Валидация выхода UDAF (rx-data) против канонического контракта
 * {@code docs/contracts/rx-data.schema.json} (JSON Schema Draft 2020-12).
 *
 * <p>Схема подключена к тестовым ресурсам через {@code sourceSets.test.resources.srcDir}
 * в build.gradle — валидируется тот же файл, что является контрактом, без копий.</p>
 */
public class RxDataSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Канонический контракт: парсится один раз на класс теста. */
    private static final JsonSchema SCHEMA = loadSchema();

    @Test
    public void testRxDataValidInNormalMode() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        String json = ChaosDataGenerator.generateSingleLine(new ForgottenMigrationsSource(), AnomalyScenario.CLEAN, 1);

        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        String report = analyzer.buildJsonReport();

        Set<ValidationMessage> errors = validate(report);
        assertTrue(errors.isEmpty(), "rx-data обычного режима не соответствует контракту: " + errors);

        // Сверка версии с единственным источником значения (DISTDOC_VERSION);
        // при запуске вне mise переменной нет — формат проверяет pattern самой схемы
        String fullVersion = System.getenv("DISTDOC_VERSION");
        if (fullVersion != null && fullVersion.split("\\.").length >= 2) {
            String[] parts = fullVersion.split("\\.");
            assertEquals(
                    parts[0] + "." + parts[1],
                    MAPPER.readTree(report).get("schema_version").asText(),
                    "schema_version не совпадает с major.minor DISTDOC_VERSION");
        }
    }

    @Test
    public void testRxDataValidInTracePresetMode() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        String json = ChaosDataGenerator.generateSingleLine(new ForgottenMigrationsSource(), AnomalyScenario.CLEAN, 1);

        analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice(""));
        String report = analyzer.buildJsonReport();

        // Режим действительно trace-овый: проверяем именно trace-форму отчёта
        assertTrue(report.contains("trace_ids"), "trace-режим не вывел trace_ids — валидируется не та форма отчёта");

        Set<ValidationMessage> errors = validate(report);
        assertTrue(errors.isEmpty(), "rx-data trace-режима не соответствует контракту: " + errors);
    }

    @Test
    public void testRxDataValidationCatchesMalformed() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        String json = ChaosDataGenerator.generateSingleLine(new ForgottenMigrationsSource(), AnomalyScenario.CLEAN, 1);

        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        ObjectNode root = (ObjectNode) MAPPER.readTree(analyzer.buildJsonReport());

        // Ломаем гарантированный контрактом ключ первого jsonpath-узла (schema_version пропускаем)
        ObjectNode metricsNode = null;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getKey().equals("schema_version")) {
                metricsNode = (ObjectNode) field.getValue();
                break;
            }
        }
        assertNotNull(metricsNode, "В отчёте нет ни одного jsonpath-узла");
        metricsNode.remove("type");

        assertFalse(
                validate(MAPPER.writeValueAsString(root)).isEmpty(),
                "Валидатор не поймал узел без обязательного ключа type");
    }

    private static JsonSchema loadSchema() {
        try (InputStream is = RxDataSchemaTest.class.getResourceAsStream("/rx-data.schema.json")) {
            assertNotNull(is, "Контракт /rx-data.schema.json не найден: docs/contracts не подключён к тестовым ресурсам");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(is);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось загрузить контракт /rx-data.schema.json", e);
        }
    }

    private Set<ValidationMessage> validate(String reportJson) throws Exception {
        return SCHEMA.validate(MAPPER.readTree(reportJson));
    }
}
