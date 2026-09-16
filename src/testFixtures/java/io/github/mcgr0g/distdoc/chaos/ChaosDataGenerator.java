package io.github.mcgr0g.distdoc.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Сервисный процессор оркестрации потоковой генерации хаос-данных.
 * Превращает мутированные объекты в валидные JSON Lines (NDJSON) строки.
 */
public class ChaosDataGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Генерирует одну строку JSON на основе источника и выбранного сценария аномалий.
     *
     * @param source   целевой бизнес-источник данных
     * @param scenario выбранный профиль аномалий (CLEAN, EMPTY_ARRAY, DATE_AS_ARRAY, DATE_AT_UNIX, DATE_AS_PLAIN, ALL)
     * @param index    порядковый индекс записи (используется для генерации уникальных ID)
     * @return строка в формате JSON Lines
     * @throws Exception в случае сбоев сериализации Jackson
     */
    public static String generateSingleLine(ChaosSource source, AnomalyScenario scenario, long index) throws Exception {
        // 1. Получаем эталонный, чистый скелет записи
        ObjectNode record = source.generateBaseRecord(index);

        // 2. Накладываем мутации согласно выбранному сценарию аномалий
        ObjectNode chaoticRecord = source.applyChaos(record, scenario, index);

        // 3. Сериализуем в плоскую JSON-строку
        return MAPPER.writeValueAsString(chaoticRecord);
    }
}
