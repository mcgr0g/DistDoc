package io.github.mcgr0g.distdoc.udaf;

import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.AccumulatorStateSerializer;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.Map;

/**
 * Сериализатор промежуточного состояния, обеспечивающий сетевой маршалинг между узлами кластера Trino.
 *
 * <p>В распределенной архитектуре Trino вычисление агрегатных функций происходит параллельно.
 * Данный класс реализует контракт {@link AccumulatorStateSerializer}, трансформируя накопленное
 * в оперативной памяти воркера дерево метрик {@link JsonSchemaAnalyzer} в плоский VARCHAR-текст
 * для передачи по сети (шаффл) и восстанавливая его на принимающей стороне (координаторе или промежуточном узле).</p>
 *
 * <p><b>Динамическая десериализация аномалий:</b></p>
 * <p>Класс спроектирован по принципу открытости к изменениям. При разборе JSON-строки метаданных
 * он жестко обрабатывает только системные свойства ({@code "type"} и {@code "max_length"}). Корневой
 * служебный ключ {@code "schema_version"} пропускается: в карту путей он не попадает. Все остальные
 * обнаруженные ключи в объекте метрик автоматически интерпретируются как пользовательские булевы аномалии
 * dbt и динамически записываются в {@link PathMetrics}. Это позволяет добавлять новые детекторы
 * в конвейер {@link io.github.mcgr0g.distdoc.udaf.anomalies.ArrayAnomalyDetector} без изменения кода сериализатора.</p>
 *
 * <p><b>Обеспечение отказоустойчивости (Fault Tolerance):</b></p>
 * <p>Метод {@link #deserialize} обернут в защитный блок {@code try-catch}. Если промежуточный пакет
 * данных был поврежден при передаче по сети или сериализованный JSON некорректен, метод не генерирует
 * runtime-исключение (что привело бы к аварийной остановке тяжелого аналитического SQL-запроса на всем кластере),
 * а изолированно инициализирует пустой экземпляр анализатора, позволяя запросу успешно завершиться.</p>
 *
 * @see AccumulatorStateSerializer
 * @see SchemaState
 * @see PathMetrics
 * @see JsonSchemaAnalyzer
 */
public class SchemaStateSerializer implements AccumulatorStateSerializer<SchemaState> {

    /** Потокобезопасный экземпляр ObjectMapper для работы с промежуточными структурами метаданных. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Системный маркер типа данных в сериализованном JSON-объекте метрик. */
    private static final String FIELD_TYPE = "type";

    /** Системный маркер максимальной длины значения в сериализованном JSON-объекте метрик. */
    private static final String FIELD_MAX_LENGTH = "max_length";

    /** Системный маркер трассировочных идентификаторов строк-источников (trace-режим). */
    private static final String FIELD_TRACE_IDS = "trace_ids";

    /** Системный маркер имени поля-источника trace_id (trace-режим). */
    private static final String FIELD_TRACE_ID_KEY = "trace_id_key";

    /** Корневой служебный ключ версии схемы rx-data (не является jsonpath-путём). */
    private static final String FIELD_SCHEMA_VERSION = "schema_version";

    /**
     * Определяет SQL-тип данных, используемый Trino для транспортировки состояния по сети.
     *
     * @return {@link VarcharType#VARCHAR}, так как промежуточная схема передается в виде текстового JSON-отчета.
     */
    @Override
    public Type getSerializedType() {
        return VarcharType.VARCHAR;
    }

    /**
     * СЕРИАЛИЗАЦИЯ (Вызывается на воркере перед отправкой данных по сети).
     * Преобразует накопленную карту путей анализатора в текстовую JSON-строку и записывает её в буфер памяти Trino.
     *
     * @param state текущее мутабельное состояние интроспекции на воркере
     * @param out   выходной буфер Trino (BlockBuilder) для записи VARCHAR-значения
     */
    @Override
    public void serialize(SchemaState state, BlockBuilder out) {
        if (state.getAnalyzer() == null) {
            out.appendNull();
        } else {
            String serializedData = state.getAnalyzer().buildJsonReport();
            VarcharType.VARCHAR.writeString(out, serializedData);
        }
    }

    /**
     * ДЕСЕРИАЛИЗАЦИЯ (Вызывается на принимающем узле кластера/координаторе).
     * Вычитывает VARCHAR-строку из блока памяти Trino, восстанавливает из неё карту уникальных
     * путей, системных метрик и динамических аномалий, после чего привязывает новый анализатор к состоянию.
     *
     * @param block Текстовый блок данных, полученный из сети
     * @param index Индекс строки (записи) внутри блока данных
     * @param state Целевой контейнер памяти, в который будет реконструирован анализатор
     */
    @Override
    public void deserialize(Block block, int index, SchemaState state) {
        if (block.isNull(index)) {
            return;
        }

        String serializedData = VarcharType.VARCHAR.getSlice(block, index).toStringUtf8();
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();

        try {
            JsonNode root = MAPPER.readTree(serializedData);
            Map<String, PathMetrics> schemaMap = analyzer.getSchemaMap();

            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String jsonPath = field.getKey();
                JsonNode metricsNode = field.getValue();

                // Корневой служебный ключ версии схемы не является jsonpath-узлом схемы
                if (jsonPath.equals(FIELD_SCHEMA_VERSION)) {
                    continue;
                }

                PathMetrics metrics = new PathMetrics();

                // 1. Десериализация гарантированных системных метаданных пути
                if (metricsNode.has(FIELD_TYPE)) {
                    metrics.addType(metricsNode.get(FIELD_TYPE).asText());
                }
                if (metricsNode.has(FIELD_MAX_LENGTH)) {
                    metrics.updateLength(metricsNode.get(FIELD_MAX_LENGTH).asLong());
                }

                // 2. Свободное динамическое чтение всех пользовательских флагов аномалий dbt
                Iterator<Map.Entry<String, JsonNode>> metricsFields = metricsNode.fields();
                while (metricsFields.hasNext()) {
                    Map.Entry<String, JsonNode> metricProperty = metricsFields.next();
                    String key = metricProperty.getKey();

                    // Системный массив trace_ids: восстанавливаем трассировочные id пути
                    if (key.equals(FIELD_TRACE_IDS)) {
                        JsonNode traceIdsNode = metricProperty.getValue();
                        if (traceIdsNode != null && traceIdsNode.isArray()) {
                            for (JsonNode idNode : traceIdsNode) {
                                metrics.addTraceId(idNode.asText());
                            }
                        }
                    }
                    // Системный ключ trace_id_key: имя поля-источника trace_id (может быть "")
                    else if (key.equals(FIELD_TRACE_ID_KEY)) {
                        metrics.setTraceIdKey(metricProperty.getValue().asText());
                    }
                    // Все прочие поля, не являющиеся системными метаданными, маппятся в карту аномалий
                    else if (!key.equals(FIELD_TYPE) && !key.equals(FIELD_MAX_LENGTH)) {
                        boolean anomalyValue = metricProperty.getValue().asBoolean();
                        metrics.setAnomaly(key, anomalyValue);
                    }
                }

                schemaMap.put(jsonPath, metrics);
            }
        } catch (Exception e) {
            // Защитный механизм: предотвращает падение сессии при сетевых сбоях маршалинга
        }

        state.setAnalyzer(analyzer);
    }
}
