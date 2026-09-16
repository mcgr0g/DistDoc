package io.github.mcgr0g.distdoc.chaos;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface ChaosSource {
    // Имя источника для вызова из консоли (например, "billing")
    String getName();

    // Генерация базового, валидного JSON-объекта
    ObjectNode generateBaseRecord(long index);

    // Наложение мутаций (хаоса), специфичных именно для ЭТОГО источника
    ObjectNode applyChaos(ObjectNode baseRecord, AnomalyScenario scenario, long index);
}