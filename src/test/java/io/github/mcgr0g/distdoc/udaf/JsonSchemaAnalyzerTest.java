package io.github.mcgr0g.distdoc.udaf;

import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.sources.*;
import io.github.mcgr0g.distdoc.udaf.anomalies.AnomalyDetector;
import io.airlift.slice.Slices;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class JsonSchemaAnalyzerTest {

    @Test
    public void testCleanDataAndDeEscaping() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 1);

        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        Map<String, PathMetrics> schema = analyzer.getSchemaMap();

        // Проверяем корректность сборки BSON-пути верхнего уровня
        assertTrue(schema.containsKey("$._id.$oid"), "Базовый BSON-путь $._id.$oid не найден");
        assertEquals("VARCHAR", schema.get("$._id.$oid").getFinalType());

        // Проверяем успешное деэкранирование и изоляцию рекурсивного стека путей
        assertTrue(schema.containsKey("$.metadata_encoded.user_agent"), "Путь $.metadata_encoded.user_agent внутри экранированной строки не распарсился");
        assertEquals("VARCHAR", schema.get("$.metadata_encoded.user_agent").getFinalType());
        assertTrue(schema.containsKey("$.metadata_encoded.retry_count"), "Путь $.metadata_encoded.retry_count не найден");
        assertEquals("INTEGER", schema.get("$.metadata_encoded.retry_count").getFinalType());

        // Спецсимвольные BSON/@-поля
        assertTrue(schema.containsKey("$.version.$numberLong"), "Путь $.version.$numberLong не найден");
        assertEquals("INTEGER", schema.get("$.version.$numberLong").getFinalType());
        assertTrue(schema.containsKey("$.doc_meta.@type"), "Путь $.doc_meta.@type не найден");
        assertEquals("VARCHAR", schema.get("$.doc_meta.@type").getFinalType());
        assertTrue(schema.containsKey("$.doc_meta.@version"), "Путь $.doc_meta.@version не найден");
        assertEquals("INTEGER", schema.get("$.doc_meta.@version").getFinalType());

        // Чистый сценарий: customer_rating — однородный INTEGER (без подмешивания)
        assertEquals("INTEGER", schema.get("$.customer_rating").getFinalType());
    }

    @Test
    public void testEmptyArrayAnomaly() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // Включаем сценарий пустых массивов
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.EMPTY_ARRAY, 1);


        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        PathMetrics arrayMetrics = analyzer.getSchemaMap().get("$.payment_dates[*]");

        assertNotNull(arrayMetrics, "Путь $.payment_dates[*] вернул null");
        assertTrue(arrayMetrics.getAnomaly(AnomalyDetector.METRIC_IS_EMPTY), "Аномалия пустого массива не зафиксирована");
        assertFalse(arrayMetrics.getAnomaly(AnomalyDetector.METRIC_IS_DATE_PART), "Ложное срабатывание флага is_date_part_array на пустом массиве");
    }

    @Test
    public void testDateAsArrayAnomaly() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // Включаем сценарий разорванных компонентов дат
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.DATE_AS_ARRAY, 1);

        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        PathMetrics arrayMetrics = analyzer.getSchemaMap().get("$.birth_date[*]");

        assertNotNull(arrayMetrics, "Путь $.birth_date[*] вернул null");
        assertTrue(arrayMetrics.getAnomaly(AnomalyDetector.METRIC_IS_DATE_PART), "Аномалия частиц дат в массиве не зафиксирована");
        assertFalse(arrayMetrics.getAnomaly(AnomalyDetector.METRIC_IS_EMPTY), "Ложное срабатывание флага is_array_empty на заполненном массиве");
    }

    @Test
    public void testDateAtUnixScenario() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.DATE_AT_UNIX, 1);

        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        PathMetrics metrics = analyzer.getSchemaMap().get("$.created_at");

        assertNotNull(metrics, "Путь $.created_at вернул null");
        assertEquals("INTEGER", metrics.getFinalType());
    }

    @Test
    public void testDateAsPlainScenario() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.DATE_AS_PLAIN, 1);

        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        PathMetrics metrics = analyzer.getSchemaMap().get("$.created_at");

        assertNotNull(metrics, "Путь $.created_at вернул null");
        assertEquals("VARCHAR", metrics.getFinalType());
    }

    @Test
    public void testAllChaosAggregation() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // Имитируем распределенный поток данных из DWH (100 строк в режиме ALL)
        for (int i = 0; i < 100; i++) {
            String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.ALL, i);
            analyzer.analyze(new ByteArrayInputStream(json.getBytes()));
        }

        PathMetrics paymentMetrics = analyzer.getSchemaMap().get("$.payment_dates[*]");
        assertNotNull(paymentMetrics, "Путь $.payment_dates[*] вернул null при агрегации");
        assertTrue(paymentMetrics.getAnomaly(AnomalyDetector.METRIC_IS_EMPTY), "Флаг is_array_empty не взлетел при агрегации хаоса");

        PathMetrics birthMetrics = analyzer.getSchemaMap().get("$.birth_date[*]");
        assertNotNull(birthMetrics, "Путь $.birth_date[*] вернул null при агрегации");
        assertTrue(birthMetrics.getAnomaly(AnomalyDetector.METRIC_IS_DATE_PART), "Флаг is_date_part_array не взлетел при агрегации хаоса");

        // Числовой подъём: INTEGER + DOUBLE на одном пути -> DOUBLE (без потери данных)
        assertEquals("DOUBLE", analyzer.getSchemaMap().get("$.customer_rating").getFinalType());
    }

    // ------------------------------------------------------------------
    // Трассировка идентификаторов (trace_ids)
    // ------------------------------------------------------------------

    @Test
    public void testTraceExplicitByFieldName() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // trace('created_at'): явный режим — id = значение поля created_at из строки-первопроходца
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 1);
        analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice("created_at"));

        assertEquals(
                List.of("2026-07-23T01:15:00"),
                analyzer.getSchemaMap().get("$.created_at").getTraceIds(),
                "trace_ids узла $.created_at не содержат значение поля из строки-источника");
    }

    @Test
    public void testTraceExplicitMissingFieldGetsEmptyMarker() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // trace('doc_code'): поля нет в документе — маркер "" (литеральный фолбэк удалён)
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 1);
        analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice("doc_code"));

        PathMetrics metrics = analyzer.getSchemaMap().get("$.customer_rating");
        assertEquals(
                List.of(""),
                metrics.getTraceIds(),
                "Новый узел не получил пустой маркер при отсутствии явного поля");
        assertEquals(
                "",
                metrics.getTraceIdKey(),
                "trace_id_key должен быть пустым маркером при отсутствии явного поля");

        String report = analyzer.buildJsonReport();
        assertTrue(report.contains("\"trace_ids\":[\"\"]"), "trace_ids не содержит пустой маркер");
        assertTrue(report.contains("\"trace_id_key\":\"\""), "trace_id_key не содержит пустой маркер");
    }

    @Test
    public void testTracePresetByOid() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // trace(): пресет-режим — id = значение _id.$oid (первый ранг пресета)
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 1);
        analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice(""));

        assertEquals(
                List.of("60b8d29f1a4c8b0000000001"),
                analyzer.getSchemaMap().get("$.customer_rating").getTraceIds(),
                "Пресет-режим не подобрал BSON-id _id.$oid для нового узла");
    }

    @Test
    public void testTraceIdsLimit() throws Exception {
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // Имитация кластера: три воркера, каждый видит свою строку-первопроходца (разные BSON-id по index)
        JsonSchemaAnalyzer worker1 = new JsonSchemaAnalyzer();
        JsonSchemaAnalyzer worker2 = new JsonSchemaAnalyzer();
        JsonSchemaAnalyzer worker3 = new JsonSchemaAnalyzer();
        for (int i = 0; i < 3; i++) {
            String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, i);
            JsonSchemaAnalyzer worker = (i == 0) ? worker1 : (i == 1) ? worker2 : worker3;
            worker.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice(""));
        }

        // Сливаем воркеров: union непустых сортируется, лимит max_ids=2 оставляет первые два
        worker1.merge(worker2);
        worker1.merge(worker3);

        assertEquals(
                List.of("60b8d29f1a4c8b0000000000", "60b8d29f1a4c8b0000000001"),
                worker1.getSchemaMap().get("$.customer_rating").getTraceIds(),
                "Лимит trace_ids (max_ids) и лексикографическая сортировка не применены при слиянии воркеров");
    }

    @Test
    public void testTraceAnomalyGetsId() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // EMPTY_ARRAY в пресет-режиме: путь с аномалией получает id строки-источника
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.EMPTY_ARRAY, 1);
        analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice(""));

        assertEquals(
                List.of("60b8d29f1a4c8b0000000001"),
                analyzer.getSchemaMap().get("$.payment_dates[*]").getTraceIds(),
                "Путь с аномалией не получил id строки-источника в trace-режиме");
    }

    @Test
    public void testNormalModeHasNoTraceIds() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // Обычный analyze(): контракт rx-data без trace не меняется — ключа trace_ids нет
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 1);
        analyzer.analyze(new ByteArrayInputStream(json.getBytes()));

        assertFalse(
                analyzer.buildJsonReport().contains("trace_ids"),
                "Ключ trace_ids не должен присутствовать в отчёте обычного (не trace) режима");
        assertFalse(
                analyzer.buildJsonReport().contains("trace_id_key"),
                "Ключ trace_id_key не должен присутствовать в отчёте обычного (не trace) режима");
    }

    @Test
    public void testTraceMergeSortingAndLimit() throws Exception {
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // Три воркера с index 2,1,3: id = 60b8d29f1a4c8b%010d лексикографически упорядочены как индексы
        JsonSchemaAnalyzer worker2 = new JsonSchemaAnalyzer();
        JsonSchemaAnalyzer worker1 = new JsonSchemaAnalyzer();
        JsonSchemaAnalyzer worker3 = new JsonSchemaAnalyzer();

        worker2.analyze(new ByteArrayInputStream(
                ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 2).getBytes()), Slices.utf8Slice(""));
        worker1.analyze(new ByteArrayInputStream(
                ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 1).getBytes()), Slices.utf8Slice(""));
        worker3.analyze(new ByteArrayInputStream(
                ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 3).getBytes()), Slices.utf8Slice(""));

        worker2.merge(worker1);
        worker2.merge(worker3);

        assertEquals(
                List.of("60b8d29f1a4c8b0000000001", "60b8d29f1a4c8b0000000002"),
                worker2.getSchemaMap().get("$.customer_rating").getTraceIds(),
                "merge должен сортировать trace_ids лексикографически и соблюдать лимит max_ids");
    }

    @Test
    public void testTraceMergeEmptyMarkerDoesNotEvictRealIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Воркер A: нет ни пресет-поля, ни суффикса -> маркер ""
        JsonSchemaAnalyzer workerA = new JsonSchemaAnalyzer();
        workerA.analyze(new ByteArrayInputStream(mapper.writeValueAsBytes(
                mapper.createObjectNode().put("x", 1))), Slices.utf8Slice(""));

        // Воркер B: BSON-id по пресету
        JsonSchemaAnalyzer workerB = new JsonSchemaAnalyzer();
        workerB.analyze(new ByteArrayInputStream(mapper.writeValueAsBytes(
                mapper.createObjectNode().put("x", 1)
                        .set("_id", mapper.createObjectNode().put("$oid", "60b8d29f1a4c8b0000000001")))),
                Slices.utf8Slice(""));

        workerA.merge(workerB);

        PathMetrics metrics = workerA.getSchemaMap().get("$.x");
        assertEquals(
                List.of("60b8d29f1a4c8b0000000001", ""),
                metrics.getTraceIds(),
                "непустые id должны идти первыми, маркер \"\" — в хвост при свободном слоте");

        // Воркер C: другой BSON-id -> заполняет второй слот, маркер "" вытесняется
        JsonSchemaAnalyzer workerC = new JsonSchemaAnalyzer();
        workerC.analyze(new ByteArrayInputStream(mapper.writeValueAsBytes(
                mapper.createObjectNode().put("x", 1)
                        .set("_id", mapper.createObjectNode().put("$oid", "60b8d29f1a4c8b0000000002")))),
                Slices.utf8Slice(""));

        workerA.merge(workerC);

        assertEquals(
                List.of("60b8d29f1a4c8b0000000001", "60b8d29f1a4c8b0000000002"),
                metrics.getTraceIds(),
                "при заполнении слотов маркер \"\" вытесняется непустыми id");
        assertEquals("_id.$oid", metrics.getTraceIdKey(), "ключ пресета должен победить");
    }

    @Test
    public void testTraceIdKeyPreset() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ForgottenMigrationsSource source = new ForgottenMigrationsSource();

        // trace(): пресет-режим — ключ = путь пресета без "$."
        String json = ChaosDataGenerator.generateSingleLine(source, AnomalyScenario.CLEAN, 1);
        analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice(""));

        assertEquals(
                "_id.$oid",
                analyzer.getSchemaMap().get("$.customer_rating").getTraceIdKey(),
                "Пресет-режим не зафиксировал ключ _id.$oid");
    }

    @Test
    public void testTraceIdKeySuffixFallback() throws Exception {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        ObjectMapper mapper = new ObjectMapper();

        // Ручной JSON без пресет-полей: суффикс-правило по полю user_id
        String json = mapper.writeValueAsString(
                mapper.createObjectNode().put("user_id", "u1").put("x", 1));
        analyzer.analyze(new ByteArrayInputStream(json.getBytes()), Slices.utf8Slice(""));

        PathMetrics metrics = analyzer.getSchemaMap().get("$.user_id");
        assertEquals(List.of("u1"), metrics.getTraceIds(), "Суффикс-режим не дал значение поля user_id");
        assertEquals("user_id", metrics.getTraceIdKey(), "Суффикс-режим не зафиксировал имя поля user_id");
    }

    @Test
    public void testTraceIdKeyMergePresetPriority() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Воркер A: пресет _id.$oid (ранг 0); воркер B: пресет id (ранг 2)
        JsonSchemaAnalyzer workerA = new JsonSchemaAnalyzer();
        workerA.analyze(new ByteArrayInputStream(mapper.writeValueAsBytes(
                mapper.createObjectNode().put("x", 1)
                        .set("_id", mapper.createObjectNode().put("$oid", "o1")))), Slices.utf8Slice(""));

        JsonSchemaAnalyzer workerB = new JsonSchemaAnalyzer();
        workerB.analyze(new ByteArrayInputStream(mapper.writeValueAsBytes(
                mapper.createObjectNode().put("id", "i1").put("x", 1))), Slices.utf8Slice(""));

        workerA.merge(workerB);

        PathMetrics metrics = workerA.getSchemaMap().get("$.x");
        assertEquals("_id.$oid", metrics.getTraceIdKey(), "пресет-ключ с большим рангом должен победить");
        assertEquals(List.of("i1", "o1"), metrics.getTraceIds(), "trace_ids должны быть отсортированы лексикографически");
    }

    @Test
    public void testTraceIdKeyMergeSuffixLexicographic() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // Оба воркера — суффикс-хиты: побеждает лексикографически меньший ключ
        JsonSchemaAnalyzer workerA = new JsonSchemaAnalyzer();
        workerA.analyze(new ByteArrayInputStream(mapper.writeValueAsBytes(
                mapper.createObjectNode().put("a_id", "1").put("x", 1))), Slices.utf8Slice(""));

        JsonSchemaAnalyzer workerB = new JsonSchemaAnalyzer();
        workerB.analyze(new ByteArrayInputStream(mapper.writeValueAsBytes(
                mapper.createObjectNode().put("b_id", "2").put("x", 1))), Slices.utf8Slice(""));

        workerA.merge(workerB);

        assertEquals("a_id", workerA.getSchemaMap().get("$.x").getTraceIdKey(),
                "при суффиксных ключах побеждает лексикографически меньший");
    }
}
