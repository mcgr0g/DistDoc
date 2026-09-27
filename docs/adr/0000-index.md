# Индекс ADR

ADR фиксируют **почему** было принято решение на момент принятия и со временем устаревают.
Текущее поведение описывают актуальные документы (колонка «Актуально в»). Порядок чтения
для агентов — в `AGENTS.md`, раздел «Структура документации»: ADR читаются в последнюю очередь.

В индексе — только действующие решения. Полностью заменённый ADR из индекса удаляется
(файл остаётся для истории).

| ADR | Решение | Статус | Актуально в |
|---|---|---|---|
| [0001](0001-streaming-udaf.md) | Стриминг-UDAF без DOM, O(1) памяти на строку | accepted | docs/patterns/plugin.md (паттерны 1–2) |
| [0002](0002-anomaly-strategy.md) | Аномалии массивов — стратегия `AnomalyDetector` | accepted; фиксация аномалии — `markAnomaly` (0006) | docs/patterns/plugin.md (паттерн 3) |
| [0003](0003-open-closed-serialization.md) | Сериализация состояния между воркерами | частично заменён 0006: правило «неизвестное поле — boolean-аномалия» отменено | docs/patterns/plugin.md (паттерны 4–5) |
| [0004](0004-trace-and-shadowdoc.md) | Трассировка id и shadow-кластер ShadowDoc | accepted; форма trace в rx-data заменена 0006 | docs/testing/tracing.md, docs/testing/local-cluster.md |
| [0005](0005-e2e-sourceSet.md) | Отдельный sourceSet `e2e` | accepted | docs/testing/reliability-matrix.md |
| [0006](0006-format-detection.md) | Форматы значений, `anomalies[name]`, `path_trace`, rx-data 2.0 | accepted | docs/contracts/value-formats.md, docs/contracts/rx-data-contract.md, docs/testing/tracing.md, docs/patterns/plugin.md (паттерны 4–5) |
