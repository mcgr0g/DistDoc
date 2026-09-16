package io.github.mcgr0g.distdoc.udaf.anomalies;

import io.github.mcgr0g.distdoc.udaf.PathMetrics;

/**
 * Реализация стратегии детекции аномалий, ориентированная на генерацию staging-моделей в dbt.
 *
 * <p>Этот класс инкапсулирует в себе математические критерии и правила валидации
 * полиморфных списков, позволяя выявлять скрытые проблемы качества данных (Data Quality)
 * на этапе интроспекции JSON-схем.</p>
 *
 * <p><b>Реализованные алгоритмы проверок:</b></p>
 * <ol>
 *   <li><b>Пустой массив ({@code is_array_empty}):</b> Фиксирует факт отсутствия элементов.
 *       Помогает dbt определить, что поле гарантированно является списком, но не содержит
 *       данных для вывода типов в текущей выборке.</li>
 *   <li><b>Плоский текстовый массив ({@code is_flat_string_array}):</b> Позволяет dbt понять,
 *       что массив состоит строго из строк и может быть развернут (flatten) в денормализованную
 *       VARCHAR-колонку без дополнительного парсинга свойств.</li>
 *   <li><b>Частицы дат ({@code is_date_part_array}):</b> Специфичный алгоритм для поиска
 *       разорванных компонентов дат (например, {@code} или {@code ["2026", "06", "13"]}).
 *       Критерии срабатывания:
 *       <ul>
 *         <li>Массив содержит строго 3 элемента.</li>
 *         <li>Все элементы состоят только из цифр.</li>
 *         <li>Длина каждого элемента находится в диапазоне от 2 до 4 символов, при этом
 *             элементы длиной ровно 3 символа исключены (так как валидный год имеет длину 4,
 *             А месяц/день — 2 символа).</li>
 *       </ul>
 *       Этот флаг сигнализирует dbt о необходимости применить макрос конкатенации и cast к типу DATE.
 *   </li>
 * </ol>
 *
 * @see ArrayAnomalyDetector
 * @see ArrayContext
 * @see PathMetrics
 */
public class AnomalyDetector implements ArrayAnomalyDetector {

    /**
     * Ключ аномалии: Массив пуст.
     * Соответствует имени булевой колонки в итоговом JSON-отчете.
     */
    public static final String METRIC_IS_EMPTY = "is_array_empty";

    /**
     * Ключ аномалии: Массив является плоским списком строк.
     */
    public static final String METRIC_IS_STRING_ARRAY = "is_flat_string_array";

    /**
     * Ключ аномалии: Массив представляет собой компоненты даты (Year, Month, Day).
     */
    public static final String METRIC_IS_DATE_PART = "is_date_part_array";

    /**
     * Выполняет потоковый анализ контекста массива. Если аномалия обнаружена,
     * соответствующий флаг со значением {@code true} динамически записывается
     * в мутабельную карту метрик пути.
     *
     * @param context неизменяемый snapshot данных текущего массива
     * @param metrics контейнер метрик, куда будет сохранен результат детекции
     */
    @Override
    public void detect(ArrayContext context, PathMetrics metrics) {
        var elements = context.elements();

        // 1. Проверка на пустой массив
        if (elements.isEmpty()) {
            metrics.setAnomaly(METRIC_IS_EMPTY, true);
            return;
        }

        // 2. Проверка на плоский массив строк
        if (context.allStrings()) {
            metrics.setAnomaly(METRIC_IS_STRING_ARRAY, true);
        }

        // 3. Проверка на компоненты дат (3 элемента, длина 2 или 4, только цифры)
        if (elements.size() == 3 && (context.allStrings() || context.allNumbers())) {
            boolean isDatePart = true;
            for (String el : elements) {
                int len = el.length();
                if (len < 2 || len == 3 || len > 4 || !el.matches("\\d+")) {
                    isDatePart = false;
                    break;
                }
            }
            if (isDatePart) {
                metrics.setAnomaly(METRIC_IS_DATE_PART, true);
            }
        }
    }
}
