package io.github.mcgr0g.distdoc.udaf;

import io.trino.spi.function.AccumulatorState;
import io.trino.spi.function.AccumulatorStateMetadata;

/**
 * Интерфейс, определяющий промежуточное и финальное состояние (State) распределенной агрегации.
 *
 * <p>В архитектуре Trino SPI этот интерфейс служит контейнером оперативной памяти, в котором
 * движок хранит накопленные данные интроспекции JSON-схем в рамках одного потока выполнения запроса.
 * Жизненным циклом и выделением памяти под этот объект управляет менеджер состояний Trino.</p>
 *
 * <p><b>Связывание с сетевым сериализатором:</b></p>
 * <p>Аннотация {@link AccumulatorStateMetadata} указывает движку Trino на класс
 * {@link SchemaStateSerializer}, который отвечает за преобразование накопленного Java-объекта
 * {@link JsonSchemaAnalyzer} в бинарный или текстовый формат (VARCHAR) для последующей передачи
 * по сети между воркерами и координатором кластера во время фазы распределенного слияния (Shuffle/Combine).</p>
 *
 * <p><b>Принцип работы в UDAF:</b></p>
 * <ul>
 *   <li><b>На воркерах (Фаза Input):</b> Через методы-акцессоры извлекается текущий инстанс
 *       анализатора, в который методом {@code analyze()} непрерывно стримятся новые строки.</li>
 *   <li><b>В сети (Фаза Combine):</b> Экземпляры сериализуются и десериализуются через
 *       {@code SchemaStateSerializer} для объединения карт путей из разных нод кластера.</li>
 * </ul>
 *
 * @see AccumulatorState
 * @see AccumulatorStateMetadata
 * @see SchemaStateSerializer
 * @see JsonSchemaAnalyzer
 */
@AccumulatorStateMetadata(stateSerializerClass = SchemaStateSerializer.class)
public interface SchemaState extends AccumulatorState {

    /**
     * Возвращает текущий экземпляр анализатора схем, привязанный к данному контейнеру памяти.
     * Может возвращать {@code null}, если на текущем узле кластера еще не было обработано
     * ни одной строки данных (требуется ленивая инициализация).
     *
     * @return экземпляр {@link JsonSchemaAnalyzer} или {@code null}
     */
    JsonSchemaAnalyzer getAnalyzer();

    /**
     * Перезаписывает или инициализирует экземпляр анализатора схем в текущем состоянии.
     * Вызывается движком Trino на фазе десериализации данных из сети, а также при
     * первичной ленивой инициализации в коде агрегации.
     *
     * @param analyzer новый экземпляр {@link JsonSchemaAnalyzer} для сохранения в состоянии
     */
    void setAnalyzer(JsonSchemaAnalyzer analyzer);
}
