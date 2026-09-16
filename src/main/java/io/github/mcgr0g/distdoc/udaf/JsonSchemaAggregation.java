package io.github.mcgr0g.distdoc.udaf;

import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.*;
import io.trino.spi.type.StandardTypes;
import io.airlift.slice.Slice;
import io.trino.spi.type.VarcharType;

/**
 * Связывает SQL функцию `analyze_json_schema` c методами анализа схемы.
 * Распределенная агрегатная функция (UDAF) для интроспекции полиморфных JSON-документов.
 * Собирает уникальные jsonpath, вычисляет типы данных и выявляет dbt-специфичные аномалии.
 */
@AggregationFunction("analyze_json_schema") // Имя функции в вашем DWH SQL
public final class JsonSchemaAggregation {

    private JsonSchemaAggregation() {} // Запрещаем инстанцирование

    /**
     * Фаза input для воркеров:
     * Каждый сервер параллельно читает свои миллионы строк из хранилища и
     * наполняет свой локальный экземпляр JsonSchemaAnalyzer.
     */
    @InputFunction
    public static void input(
            SchemaState state,
            @SqlType(StandardTypes.VARCHAR) Slice jsonSlice) {

        if (jsonSlice == null) {
            return;
        }

        // Ленивая инициализация анализатора на воркере
        if (state.getAnalyzer() == null) {
            state.setAnalyzer(new JsonSchemaAnalyzer());
        }

        // Передаем поток байт напрямую в Jackson стример (максимальная скорость)
        state.getAnalyzer().analyze(jsonSlice.getInput());
    }

    /**
     * Фаза combine (Сеть/Координатор):
     * Серверы сериализуют (через SchemaStateSerializer) свои промежуточные деревья путей и пересылают их друг другу.
     * Метод combine сливает эти деревья вместе с помощью логики OR для флагов и max() для длин.
     */
    @CombineFunction
    public static void combine(SchemaState state, SchemaState otherState) {
        if (otherState.getAnalyzer() == null) {
            return;
        }
        if (state.getAnalyzer() == null) {
            state.setAnalyzer(new JsonSchemaAnalyzer());
        }

        // Объединяем карты путей, собранные на разных серверах кластера
        state.getAnalyzer().merge(otherState.getAnalyzer());
    }


    /**
     * Фаза output (Финал):
     * Когда все данные со всех серверов объединены в один итоговый SchemaState,
     * вызывается output, который превращает дерево в финальную JSON-строку для dbt.
     */
    @OutputFunction(StandardTypes.VARCHAR)
    public static void output(SchemaState state, BlockBuilder out) {
        if (state.getAnalyzer() == null) {
            out.appendNull();
            return;
        }

        // Генерируем финальный JSON-отчет с типами и флагами аномалий для dbt
        String finalReportJson = state.getAnalyzer().buildJsonReport();

        // Записываем результат в выходной поток Trino
        VarcharType.VARCHAR.writeString(out, finalReportJson);
    }
}
