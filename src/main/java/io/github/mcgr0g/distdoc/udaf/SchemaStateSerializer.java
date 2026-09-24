package io.github.mcgr0g.distdoc.udaf;

import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.function.AccumulatorStateSerializer;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

/**
 * Сериализатор промежуточного состояния, обеспечивающий сетевой маршалинг между узлами кластера Trino.
 *
 * <p>В распределенной архитектуре Trino вычисление агрегатных функций происходит параллельно.
 * Данный класс реализует контракт {@link AccumulatorStateSerializer}, трансформируя накопленное
 * в оперативной памяти воркера дерево метрик {@link JsonSchemaAnalyzer} в плоский VARCHAR-текст
 * ({@link JsonSchemaAnalyzer#buildStateJson()}) для передачи по сети (шаффл) и восстанавливая его
 * на принимающей стороне (координаторе или промежуточном узле).</p>
 *
 * <p><b>Whitelist-десериализация</b> ({@link JsonSchemaAnalyzer#fromStateJson(String)}):</p>
 * <p>Служебные поля пути разбираются по явному списку: {@code type}, {@code max_length},
 * {@code observed_formats}, {@code anomalies}, {@code path_trace}, {@code format_trace}. Новая
 * аномалия — новое имя внутри {@code anomalies}, правки сериализатора не требует; новая <i>форма</i>
 * поля — правка whitelist. Выводимая аномалия {@code is_polymorphic_format} пропускается: она
 * восстанавливается из форматов. Ключи-пути — данные и не проверяются.</p>
 *
 * <p><b>Fault tolerance (паттерн 5, docs/patterns/plugin.md):</b> сбой разбора не роняет SQL-запрос.
 * Путь со служебным полем или форматом вне whitelist (воркер другой версии плагина при поэтапной
 * раскатке) пропускается, остальные пути сохраняются; невалидный JSON — пустое состояние воркера.
 * Каждый пропуск пишется {@code WARNING} в server.log узла, чтобы потеря не была тихой.</p>
 *
 * @see AccumulatorStateSerializer
 * @see SchemaState
 * @see PathMetrics
 * @see JsonSchemaAnalyzer
 */
public class SchemaStateSerializer implements AccumulatorStateSerializer<SchemaState> {

    /**
     * Определяет SQL-тип данных, используемый Trino для транспортировки состояния по сети.
     *
     * @return {@link VarcharType#VARCHAR}, так как промежуточная схема передается в виде текстового JSON.
     */
    @Override
    public Type getSerializedType() {
        return VarcharType.VARCHAR;
    }

    /**
     * СЕРИАЛИЗАЦИЯ (Вызывается на воркере перед отправкой данных по сети).
     *
     * @param state текущее мутабельное состояние интроспекции на воркере
     * @param out   выходной буфер Trino (BlockBuilder) для записи VARCHAR-значения
     */
    @Override
    public void serialize(SchemaState state, BlockBuilder out) {
        if (state.getAnalyzer() == null) {
            out.appendNull();
        } else {
            VarcharType.VARCHAR.writeString(out, state.getAnalyzer().buildStateJson());
        }
    }

    /**
     * ДЕСЕРИАЛИЗАЦИЯ (Вызывается на принимающем узле кластера/координаторе).
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
        state.setAnalyzer(JsonSchemaAnalyzer.fromStateJson(VarcharType.VARCHAR.getSlice(block, index).toStringUtf8()));
    }
}
