package io.github.mcgr0g.distdoc.udaf;

import io.github.mcgr0g.distdoc.udaf.config.TraceSettings;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.*;
import io.trino.spi.type.StandardTypes;
import io.airlift.slice.Slice;
import io.trino.spi.type.VarcharType;

/**
 * Trace-перегрузка UDAF {@code analyze_json_schema} (имя то же — перегрузка по числу аргументов,
 * прецедент {@code max_by(x,y)} / {@code max_by(x,y,n)} в core Trino).
 *
 * <p>Сигнатура {@code analyze_json_schema(json, trace(...))} включает трассировку идентификаторов:
 * в rx-data ({@code .rx.json}) для новых узлов и аномалий записываются id строк-источников
 * (лимит и длина — из {@code app-config.toml} через {@link TraceSettings}).</p>
 *
 * <p>Второй аргумент — результат scalar-функции {@code trace()}:
 * {@code trace('имя_поля')} — явный режим по имени поля, {@code trace()} — поиск id
 * по ранжированному пресету.</p>
 *
 * <p>Фазы Combine/Output дословно повторяют базовый класс {@link JsonSchemaAggregation} —
 * дублирование осознанное: классы тривиальны, общий хелпер не выделяется.</p>
 *
 * @see JsonSchemaAggregation
 * @see TraceFunctions
 * @see JsonSchemaAnalyzer#analyze(java.io.InputStream, Slice)
 */
@AggregationFunction("analyze_json_schema") // То же имя: перегрузка по числу аргументов
public final class JsonSchemaTraceAggregation {

    private JsonSchemaTraceAggregation() {} // Запрещаем инстанцирование

    /**
     * Фаза input для воркеров (trace-режим):
     * каждый сервер параллельно стримит свои строки в локальный {@link JsonSchemaAnalyzer},
     * передавая аргумент трассировки (имя поля / литерал / пустая строка для пресета).
     */
    @InputFunction
    public static void input(
            SchemaState state,
            @SqlType(StandardTypes.VARCHAR) Slice jsonSlice,
            @SqlType(StandardTypes.VARCHAR) Slice traceArg) {

        if (jsonSlice == null) {
            return;
        }

        // Ленивая инициализация анализатора на воркере
        if (state.getAnalyzer() == null) {
            state.setAnalyzer(new JsonSchemaAnalyzer());
        }

        // Передаем поток байт и trace-аргумент напрямую в Jackson стример
        state.getAnalyzer().analyze(jsonSlice.getInput(), traceArg);
    }

    /**
     * Фаза combine (Сеть/Координатор): дословная копия {@link JsonSchemaAggregation#combine}.
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
     * Фаза output (Финал): дословная копия {@link JsonSchemaAggregation#output}.
     */
    @OutputFunction(StandardTypes.VARCHAR)
    public static void output(SchemaState state, BlockBuilder out) {
        if (state.getAnalyzer() == null) {
            out.appendNull();
            return;
        }

        // Генерируем финальный JSON-отчет с типами, флагами аномалий и trace_ids
        String finalReportJson = state.getAnalyzer().buildJsonReport();

        // Записываем результат в выходной поток Trino
        VarcharType.VARCHAR.writeString(out, finalReportJson);
    }
}
