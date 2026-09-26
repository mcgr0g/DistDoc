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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Properties;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Валидация выхода UDAF (rx-data) против контракта 2.0 (JSON Schema Draft 2020-12).
 *
 * <p>До фазы 5 task01 канонический {@code docs/contracts/rx-data.schema.json} остаётся 1.1,
 * поэтому тест валидирует проект {@code rx-data.schema.draft-2.0.json}; в фазе 5
 * {@link #SCHEMA_RESOURCE} возвращается к {@code /rx-data.schema.json}.</p>
 *
 * <p>Схема подключена к тестовым ресурсам через {@code sourceSets.test.resources.srcDir}
 * в build.gradle — валидируется тот же файл, что является контрактом, без копий.</p>
 */
public class RxDataSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ресурс контракта (docs/contracts подключён к тестовым ресурсам). */
    private static final String SCHEMA_RESOURCE = "/rx-data.schema.draft-2.0.json";

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

        // Сверка с единственным источником версии (gradle.properties; рабочий каталог теста —
        // корень проекта): ловит и неверное сокращение, и устаревший app-config.toml в сборке
        Properties gradle = new Properties();
        try (InputStream is = Files.newInputStream(Path.of("gradle.properties"))) {
            gradle.load(is);
        }
        String[] parts = gradle.getProperty("distdocVersion").split("\\.");
        assertEquals(
                parts[0] + "." + parts[1],
                MAPPER.readTree(report).get("schema_version").asText(),
                "schema_version не совпадает с major.minor distdocVersion из gradle.properties");
    }

    @Test
    public void testRxDataValidInTracePresetMode() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();
        // ALL: аномалии массивов, полиморфизм форматов и их trace в одном отчёте
        for (int i = 0; i < 30; i++) {
            String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.ALL, i);
            analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice(""));
        }
        String report = analyzer.buildJsonReport();

        // Режим действительно trace-овый: проверяем именно trace-форму отчёта
        assertTrue(report.contains("path_trace"), "trace-режим не вывел path_trace — валидируется не та форма отчёта");
        assertTrue(report.contains("\"format\":"), "нет trace is_polymorphic_format — валидируется не полная форма");

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
        try (InputStream is = RxDataSchemaTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            assertNotNull(is, "Контракт " + SCHEMA_RESOURCE + " не найден: docs/contracts не подключён к тестовым ресурсам");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(is);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось загрузить контракт " + SCHEMA_RESOURCE, e);
        }
    }

    private Set<ValidationMessage> validate(String reportJson) throws Exception {
        return SCHEMA.validate(MAPPER.readTree(reportJson));
    }
}
