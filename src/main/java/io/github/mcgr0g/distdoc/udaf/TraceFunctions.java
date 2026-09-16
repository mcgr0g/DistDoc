package io.github.mcgr0g.distdoc.udaf;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

/**
 * Scalar-обёртки {@code trace()} / {@code trace(v)} — маркер включения трассировки
 * идентификаторов для UDAF {@code analyze_json_schema(line, trace(...))}.
 *
 * <ul>
 *   <li>{@code trace()} — без аргументов: возвращает пустую строку, которая в
 *       {@link JsonSchemaTraceAggregation} интерпретируется как пресет-режим поиска id
 *       (ранжированный пресет + суффикс-правило из {@code app-config.toml}, секция {@code [trace]}).</li>
 *   <li>{@code trace(v)} — передаёт значение {@code v} без изменений: имя поля
 *       ({@code trace('doc_code')}); колоночная семантика {@code trace(doc_code)} без кавычек
 *       не поддерживается.</li>
 * </ul>
 *
 * <p><b>Почему пустая строка, а не {@code NULL}:</b> {@code @InputFunction} UDAF без
 * {@code @SqlNullable} не вызывается на NULL-аргументе — строка была бы молча пропущена
 * из агрегации. Пустой маркер гарантирует вызов input-фазы в пресет-режиме.</p>
 *
 * @see JsonSchemaTraceAggregation
 */
public final class TraceFunctions {

    private TraceFunctions() {} // Запрещаем инстанцирование

    /**
     * Пустой trace(): включает пресет-режим трассировки (пустая строка = маркер пресета).
     *
     * @return пустой Slice (не NULL)
     */
    @ScalarFunction("trace")
    @SqlType(StandardTypes.VARCHAR)
    public static Slice trace() {
        return Slices.EMPTY_SLICE; // пустая строка = режим пресета
    }

    /**
     * trace(value): пробрасывает значение аргумента — имя поля в кавычках.
     *
     * @param value имя поля внутри JSON-документа (в кавычках)
     * @return переданное значение без изменений
     */
    @ScalarFunction("trace")
    @SqlType(StandardTypes.VARCHAR)
    public static Slice trace(@SqlType(StandardTypes.VARCHAR) Slice value) {
        return value;
    }
}
