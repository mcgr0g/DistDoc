package io.github.mcgr0g.distdoc.udaf;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mcgr0g.distdoc.udaf.anomalies.ArrayContext;
import io.github.mcgr0g.distdoc.udaf.anomalies.ArrayAnomalyDetector;
import io.github.mcgr0g.distdoc.udaf.anomalies.AnomalyDetector;
import io.github.mcgr0g.distdoc.udaf.config.CoreSettings;
import io.github.mcgr0g.distdoc.udaf.config.TraceSettings;
import io.airlift.slice.Slice;
import java.io.InputStream;
import java.util.*;

/**
 * Высокопроизводительное ядро интроспекции JSON-документов на базе Jackson Streaming API.
 *
 * <p>Класс выполняет линейное сканирование бинарного потока JSON, формирует уникальные
 * абсолютные пути в нотации JSONPath (ANSI SQL / Trino стандарт) и собирает метрики
 * типов, длин и структурных аномалий данных.</p>
 *
 * <p><b>Критическая архитектурная механика:</b></p>
 * <ol>
 *   <li><b>Производительность и Стриминг:</b> Использование низкоуровневого {@link JsonParser}
 *       позволяет обрабатывать документы без их полной аллокации в памяти (без построения DOM-дерева),
 *       что обеспечивает константное потребление памяти {@code O(1)} и максимальную скорость на терабайтных потоках.</li>
 *   <li><b>Симметрия Стека Путей:</b> Для конструирования путей объектов используется ручной стек
 *       {@code Deque<String>}. Логика спроектирована так, что имя поля (`FIELD_NAME`) помещается на стек,
 *       а удаляется строго в момент завершения обработки соответствующего ему значения (примитива или закрытия контейнера).
 *       Это гарантирует математическую стабильность сборщика путей {@link #buildJsonPath(Deque)}.</li>
 *   <li><b>Изолированная Рекурсия Деэкранирования:</b> При обнаружении текстовой строки, содержащей
 *       валидный JSON-документ (например, сериализованный лог из Kafka), парсер клонирует текущий
 *       стек путей через {@code new ArrayDeque<>(pathStack)} и рекурсивно запускает новый экземпляр
 *       сканера. Под-документ анализируется внутри своего изолированного пространства имен, бесшовно
 *       продолжая родительский путь (например, {@code "$.metadata_encoded.user_agent"}), и полностью
 *       уничтожает свой стек при выходе, никак не нарушая баланс токенов основного документа.</li>
 * </ol>
 *
 * @see PathMetrics
 * @see ArrayAnomalyDetector
 * @see ArrayContext
 */
public class JsonSchemaAnalyzer {
    /** Нативная фабрика Jackson для создания легковесных стриминг-парсеров. */
    private static final JsonFactory FACTORY = new JsonFactory();

    /** Объектный маппер для декларативной сборки результирующего отчета без ручной склейки кавычек. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Подключаемый плагин-детектор dbt-аномалий в массивах. */
    private final ArrayAnomalyDetector anomalyDetector = new AnomalyDetector();

    /** Внутреннее хранилище схемы: Абсолютный JSONPath -> Метрики и аномалии пути. */
    private final Map<String, PathMetrics> schemaMap = new HashMap<>();

    /** Накопитель сырых строковых элементов для текущего обрабатываемого массива. */
    private final Deque<List<String>> arrayElementsStack = new ArrayDeque<>();

    /** Флаг-индикатор: состоит ли текущий массив исключительно из строк. */
    private final Deque<Boolean> arrayAllStringsStack = new ArrayDeque<>();

    /** Флаг-индикатор: состоит ли текущий массив исключительно из целых чисел. */
    private final Deque<Boolean> arrayAllNumbersStack = new ArrayDeque<>();

    /** Стек путей для отслеживания вложенных или параллельных массивов. */
    private final Deque<String> arrayPathStack = new ArrayDeque<>();

    // ------------------------------------------------------------------
    // Состояние трассировки идентификаторов (per-row; сбрасывается в начале analyze)
    // ------------------------------------------------------------------

    /** Имя поля/литерал, переданное в trace(...) как явный аргумент (null = trace выключен либо пресет). */
    private String explicitTraceArg = null;

    /** Режим пресета: пустой trace() — поиск id по ранжированному пресету и суффикс-правилу. */
    private boolean presetTrace = false;

    /** Первое скалярное значение для каждого пресет-пути в текущей строке (путь "$.x" -> значение). */
    private final Map<String, String> presetValues = new HashMap<>();

    /** Первое значение поля, совпавшего с explicitTraceArg, в текущей строке. */
    private String explicitHitValue = null;

    /** Первое скалярное значение верхнеуровневого пути с суффиксом из TraceSettings (порядок появления). */
    private String suffixHitValue = null;

    /** Имя поля верхнего уровня (без "$."), давшего суффикс-хит; {@code null} — хитов не было. */
    private String suffixHitField = null;

    /** Пути, впервые встреченные в текущей строке (новые узлы). */
    private final Set<String> newPathsThisRow = new HashSet<>();

    /** Пути с аномалией, впервые зафиксированной в текущей строке. */
    private final Set<String> anomalyPathsThisRow = new HashSet<>();

    /**
     * Возвращает мутабельную карту собранной схемы.
     * Используется сериализатором для маршалинга промежуточных результатов по сети.
     *
     * @return карта путей и их метрик
     */
    public Map<String, PathMetrics> getSchemaMap() {
        return schemaMap;
    }

    /**
     * ТОЧКА ВХОДА: Запускает потоковый анализ переданного бинарного JSON-документа.
     * Инициализирует корневой элемент символом "$" и передает управление диспетчеру токенов.
     *
     * @param input входящий бинарный поток данных JSON (из Slice или файла)
     */
    public void analyze(InputStream input) {
        if (input == null) return;
        // Обычный режим: трассировка выключена (явно гасим возможный trace-режим от предыдущих вызовов)
        explicitTraceArg = null;
        presetTrace = false;
        clearTraceRowState();
        try (JsonParser parser = FACTORY.createParser(input)) {
            Deque<String> rootPathStack = new ArrayDeque<>();
            rootPathStack.push("$");
            parseTokens(parser, rootPathStack);
        } catch (Exception e) {
            // Стабильность для DWH
        }
    }

    /**
     * ТОЧКА ВХОДА в trace-режиме: разбирает поток и по завершении строки резолвит
     * идентификатор строки-источника (см. {@link #resolveTraceId()}).
     *
     * <p>Пустой аргумент {@code traceArg} (результат {@code trace()}) включает пресет-режим;
     * непустой — явный режим по имени поля в кавычках.</p>
     *
     * @param input    входящий бинарный поток данных JSON (из Slice или файла)
     * @param traceArg аргумент трассировки: {@code trace('имя_поля')} / {@code trace()}
     */
    public void analyze(InputStream input, Slice traceArg) {
        if (input == null) return;
        String arg = (traceArg == null) ? null : traceArg.toStringUtf8();
        if (arg == null || arg.isEmpty()) {
            // Пустой trace() (и NULL-аргумент) = пресет-режим поиска id
            explicitTraceArg = null;
            presetTrace = true;
        } else {
            explicitTraceArg = arg;
            presetTrace = false;
        }
        clearTraceRowState();
        try (JsonParser parser = FACTORY.createParser(input)) {
            Deque<String> rootPathStack = new ArrayDeque<>();
            rootPathStack.push("$");
            parseTokens(parser, rootPathStack);
        } catch (Exception e) {
            // Стабильность для DWH
        }
        resolveTraceId();
    }

    /** Сбрасывает per-row коллекции трассировки перед разбором очередной строки. */
    private void clearTraceRowState() {
        presetValues.clear();
        explicitHitValue = null;
        suffixHitValue = null;
        suffixHitField = null;
        newPathsThisRow.clear();
        anomalyPathsThisRow.clear();
    }

    /** Активен ли trace-режим (явный или пресет) на текущей строке. */
    private boolean isTraceActive() {
        return presetTrace || explicitTraceArg != null;
    }

    /**
     * Собирает id-кандидата из скалярного значения текущего пути (вызывается до мутаций метрик).
     * Значения float/boolean/null не собираются — id бывают строками и целыми числами.
     *
     * @param currentPath абсолютный путь текущего скалярного узла (например, {@code "$.created_at"})
     * @param text        текстовое представление скалярного значения
     */
    private void collectTraceValue(String currentPath, String text) {
        if (!isTraceActive() || text == null || text.isEmpty()) {
            return;
        }
        if (presetTrace) {
            // 1. Пути из ранжированного пресета (первые значения по порядку обхода документа)
            for (String presetPath : TraceSettings.getInstance().getPresetPaths()) {
                if (currentPath.equals(presetPath)) {
                    presetValues.putIfAbsent(currentPath, text);
                    return; // пресет-путь не является кандидатом суффикс-правила
                }
            }
            // 2. Суффикс-правило: поле верхнего уровня, оканчивающееся на суффикс (после пресета)
            if (suffixHitValue == null && isTopLevelPath(currentPath)) {
                String field = currentPath.substring(2); // срезаем "$."
                if (field.endsWith(TraceSettings.getInstance().getSuffix())) {
                    suffixHitValue = text;
                    suffixHitField = field;
                }
            }
        } else {
            // Явный режим: имя поля на любом уровне; запоминаем только первое совпадение
            if (explicitHitValue == null && currentPath.endsWith("." + explicitTraceArg)) {
                explicitHitValue = text;
            }
        }
    }

    /** Путь верхнего уровня: ровно один сегмент после корня {@code "$."}. */
    private boolean isTopLevelPath(String path) {
        return path != null && path.indexOf('.', 2) < 0;
    }

    /**
     * Резолвит идентификатор строки-источника по собранным за строку значениям
     * и раскладывает его по новым узлам и путям с аномалиями (в конце каждого trace-analyze).
     *
     * <ul>
     *   <li><b>Явный режим:</b> traceId = значение поля, совпавшего с аргументом
     *       (traceIdKey = имя поля); если поле в документе не встретилось — маркер
     *       {@code ""} (литеральный фолбэк удалён).</li>
     *   <li><b>Пресет-режим:</b> traceId = первое значение пути из пресета
     *       (traceIdKey = путь пресета без {@code "$."}); иначе — первое значение
     *       верхнеуровневого пути с суффиксом (traceIdKey = имя поля); иначе маркер {@code ""}.</li>
     *   <li><b>Обычный режим:</b> trace выключен — метод ничего не делает.</li>
     * </ul>
     */
    private void resolveTraceId() {
        String traceId;
        String traceIdKey;
        if (explicitTraceArg != null) {
            if (explicitHitValue != null) {
                traceId = explicitHitValue;
                traceIdKey = explicitTraceArg;
            } else {
                traceId = "";
                traceIdKey = "";
            }
        } else if (presetTrace) {
            traceId = null;
            traceIdKey = null;
            for (String presetPath : TraceSettings.getInstance().getPresetPaths()) {
                String value = presetValues.get(presetPath);
                if (value != null) {
                    traceId = value;
                    traceIdKey = presetPath.substring(2); // срезаем "$."
                    break;
                }
            }
            if (traceId == null) {
                if (suffixHitValue != null) {
                    traceId = suffixHitValue;
                    traceIdKey = suffixHitField;
                } else {
                    traceId = "";
                    traceIdKey = "";
                }
            }
        } else {
            return; // обычный режим: trace выключен
        }

        // Новый узел и аномалия в одной строке — тот же id строки; множества объединяем, чтобы не дублировать
        Set<String> targetPaths = new HashSet<>(newPathsThisRow);
        targetPaths.addAll(anomalyPathsThisRow);
        for (String path : targetPaths) {
            PathMetrics metrics = schemaMap.get(path);
            if (metrics != null) {
                metrics.addTraceId(traceId);
                metrics.mergeTraceIdKey(traceIdKey);
            }
        }
    }

    /**
     * Внутренний циклический диспетчер токенов Jackson.
     * Управляет навигацией по документу и координирует рост/сокращение стека путей.
     *
     * @param parser    активный экземпляр парсера Jackson
     * @param pathStack изолированный стек путей текущего уровня вложенности (рекурсии)
     * @throws Exception при системных ошибках чтения потока
     */
    private void parseTokens(JsonParser parser, Deque<String> pathStack) throws Exception {
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
            String currentPath = buildJsonPath(pathStack);

            switch (token) {
                case FIELD_NAME:
                    pathStack.push(parser.currentName());
                    break;

                case START_OBJECT:
                    if (isInsideArray(pathStack)) {
                        invalidateFlatArrayFlags();
                    }
                    break;

                case START_ARRAY:
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        String arrayField = pathStack.pop();
                        pathStack.push(arrayField + "[*]");
                    } else if (pathStack.isEmpty() || pathStack.peek().equals("$")) {
                        pathStack.push("[*]");
                    }

                    String fullArrayPath = buildJsonPath(pathStack);
                    getOrCreateMetrics(fullArrayPath).addType("ARRAY");

                    arrayPathStack.push(fullArrayPath);
                    arrayElementsStack.push(new ArrayList<>());
                    arrayAllStringsStack.push(true);
                    arrayAllNumbersStack.push(true);
                    break;

                case END_OBJECT:
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case END_ARRAY:
                    handleEndArray();
                    if (!pathStack.isEmpty() && pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case VALUE_STRING:
                    handleStringValue(parser, currentPath, pathStack);
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case VALUE_NUMBER_INT:
                    handleIntValue(parser, currentPath, pathStack);
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case VALUE_NUMBER_FLOAT:
                    getOrCreateMetrics(currentPath).addType("DOUBLE");
                    if (isInsideArray(pathStack)) invalidateFlatArrayFlags();
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case VALUE_TRUE:
                case VALUE_FALSE:
                    getOrCreateMetrics(currentPath).addType("BOOLEAN");
                    if (isInsideArray(pathStack)) invalidateFlatArrayFlags();
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case VALUE_NULL:
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;
            }
        }
    }

    /**
     * Обработчик строковых значений. Реализует быструю детекцию экранированных под-документов JSON
     * и ветвление в изолированную рекурсию.
     */
    private void handleStringValue(JsonParser parser, String currentPath, Deque<String> pathStack) throws Exception {
        String text = parser.getText();

        // Быстрая проверка маркеров начала и конца структуры JSON объекта/массива
        if (text != null && ((text.startsWith("{") && text.endsWith("}")) || (text.startsWith("[") && text.endsWith("]")))) {
            try (JsonParser subParser = FACTORY.createParser(text)) {
                Deque<String> subPathStack = new ArrayDeque<>(pathStack);
                parseTokens(subParser, subPathStack);
                return; // Успешно вышли из рекурсии под-документа, прерываем обработку обычной строки
            } catch (Exception e) {
                // Ошибка парсинга означает ложную тревогу — строка обрабатывается ниже как обычный текст
            }
        }

        // Сбор trace-id из обычной строки (экранированный под-документ выше уже обработан рекурсией)
        collectTraceValue(currentPath, text);

        getOrCreateMetrics(currentPath).addType("VARCHAR");
        getOrCreateMetrics(currentPath).updateLength(parser.getTextLength());

        // Если строка лежит внутри массива, логируем её значение в контекст аномалий
        if (isInsideArray(pathStack) && text != null && !arrayElementsStack.isEmpty()) {
            List<String> currentArrayElements = arrayElementsStack.peek();
            if (currentArrayElements != null) {
                currentArrayElements.add(text);
            }
            markArrayAsNonNumeric();
        }
    }

    /**
     * Обработчик целочисленных значений. Фиксирует тип INTEGER и сбрасывает текстовые флаги массива.
     */
    private void handleIntValue(JsonParser parser, String currentPath, Deque<String> pathStack) throws Exception {
        // Сбор trace-id из целочисленного скаляра (BSON-числовые id не типичны, но целые — валидный кандидат)
        collectTraceValue(currentPath, parser.getText());

        getOrCreateMetrics(currentPath).addType("INTEGER");
        if (isInsideArray(pathStack) && !arrayElementsStack.isEmpty()) {
            List<String> currentArrayElements = arrayElementsStack.peek();
            if (currentArrayElements != null) {
                currentArrayElements.add(parser.getText());
            }
            markArrayAsNonString();
        }
    }

    /**
     * Закрывает контекст текущего массива. Извлекает собранные на ходу данные,
     * упаковывает их в {@link ArrayContext} и делегирует выявление dbt-аномалий плагину стратегии.
     */
    private void handleEndArray() {
        if (!arrayPathStack.isEmpty()) {
            String finishedArrayPath = arrayPathStack.pop();
            List<String> elements = arrayElementsStack.pop();
            boolean allStrings = arrayAllStringsStack.pop();
            boolean allNumbers = arrayAllNumbersStack.pop();

            PathMetrics metrics = getOrCreateMetrics(finishedArrayPath);

            // Запоминаем существующие true-флаги аномалий до детекции (для фиксации новых в trace-режиме)
            Set<String> existingTrueAnomalies = null;
            if (isTraceActive()) {
                existingTrueAnomalies = new HashSet<>();
                for (Map.Entry<String, Boolean> e : metrics.getAnomalies().entrySet()) {
                    if (e.getValue()) {
                        existingTrueAnomalies.add(e.getKey());
                    }
                }
            }

            // Декларативно делегируем анализ выделенной стратегии
            ArrayContext context = new ArrayContext(finishedArrayPath, elements, allStrings, allNumbers);
            anomalyDetector.detect(context, metrics);

            // Если детектор зажёг новый true-флаг — в trace-режиме путь получает id строки
            // (id-поле может идти в документе ПОСЛЕ массива, поэтому резолв отложен в resolveTraceId)
            if (existingTrueAnomalies != null) {
                boolean newAnomaly = false;
                for (Map.Entry<String, Boolean> e : metrics.getAnomalies().entrySet()) {
                    if (e.getValue() && !existingTrueAnomalies.contains(e.getKey())) {
                        newAnomaly = true;
                        break;
                    }
                }
                if (newAnomaly) {
                    anomalyPathsThisRow.add(finishedArrayPath);
                }
            }
        }
    }

    /**
     * Вспомогательный метод проверки: указывает ли вершина стека на нахождение внутри элементов массива.
     */
    private boolean isInsideArray(Deque<String> pathStack) {
        if (pathStack.isEmpty()) return false;
        String top = pathStack.peek();
        return top != null && top.endsWith("[*]");
    }

    /**
     * Сбрасывает флаги однородности (вызывается, если внутри массива обнаружен сложный объект или float/boolean).
     */
    private void invalidateFlatArrayFlags() {
        if (!arrayAllStringsStack.isEmpty()) {
            arrayAllStringsStack.pop();
            arrayAllStringsStack.push(false);
            arrayAllNumbersStack.pop();
            arrayAllNumbersStack.push(false);
        }
    }

    /**
     * Фиксирует появление строки внутри массива, исключая его числовую однородность.
     */
    private void markArrayAsNonNumeric() {
        if (!arrayAllNumbersStack.isEmpty()) {
            arrayAllNumbersStack.pop();
            arrayAllNumbersStack.push(false);
        }
    }

    /**
     * Фиксирует появление числа внутри массива, исключая его строковую однородность.
     */
    private void markArrayAsNonString() {
        if (!arrayAllStringsStack.isEmpty()) {
            arrayAllStringsStack.pop();
            arrayAllStringsStack.push(false);
        }
    }

    /**
     * Ленивая инициализация или извлечение мутабельного контейнера метрик для конкретного пути.
     * В trace-режиме путь, отсутствующий в схеме, фиксируется как «новый узел» текущей строки.
     */
    private PathMetrics getOrCreateMetrics(String path) {
        if (isTraceActive() && !schemaMap.containsKey(path)) {
            newPathsThisRow.add(path);
        }
        return schemaMap.computeIfAbsent(path, k -> new PathMetrics());
    }

    /**
     * Сборщик JSONPath. Разворачивает стек от дна к вершине и склеивает сегменты через точку.
     * Автоматически опускает точку перед оператором развертки массива {@code []}.
     *
     * @param pathStack текущий стек путей
     * @return строка пути в формате Trino SQL (например, {@code "$.user.payment_dates[]"})
     */
    private String buildJsonPath(Deque<String> pathStack) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = pathStack.descendingIterator();
        while (it.hasNext()) {
            String part = it.next();
            if (part.equals("$")) {
                sb.append(part);
            } else {
                if (sb.length() > 0 && !part.equals("[*]")) {
                    sb.append(".");
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    /**
     * Слияние схем (Вызывается на координаторе Trino / Combine фазе).
     * Объединяет карту путей текущего анализатора с картой путей, прилетевшей с другой ноды кластера.
     *
     * @param other анализатор с параллельного потока/воркера выполнения
     */
    public void merge(JsonSchemaAnalyzer other) {
        if (other == null || other.schemaMap == null) return;
        other.schemaMap.forEach((path, otherMetrics) -> {
            this.schemaMap.computeIfAbsent(path, k -> new PathMetrics()).merge(otherMetrics);
        });
    }

    /**
     * Декларативная сборка итогового JSON-отчета через Jackson ObjectNode.
     * Гарантирует стопроцентное соблюдение синтаксиса JSON, автоматически экранирует
     * специальные символы и динамически выгружает все флаги аномалий, переданные детекторами.
     *
     * @return сериализованная JSON-строка отчета по всей структуре документа
     */
    public String buildJsonReport() {
        ObjectNode rootNode = MAPPER.createObjectNode();

        // Версия схемы rx-data (major.minor): корневой служебный ключ, присутствует во всех режимах
        rootNode.put("schema_version", CoreSettings.getSchemaVersion());

        schemaMap.forEach((path, metrics) -> {
            ObjectNode metricsNode = MAPPER.createObjectNode();
            metricsNode.put("type", metrics.getFinalType());
            metricsNode.put("max_length", metrics.getMaxLength());

            // Динамически выгружаем все зарегистрированные аномалии в итоговый JSON
            metrics.getAnomalies().forEach(metricsNode::put);

            // Трассировочные id строк-источников (ключ присутствует только в trace-режиме)
            if (!metrics.getTraceIds().isEmpty()) {
                metricsNode.set("trace_ids", MAPPER.valueToTree(metrics.getTraceIds()));
                // Имя поля-источника trace_id (выводится и "" — маркер неизвестного источника);
                // присутствует только вместе с trace_ids
                if (metrics.getTraceIdKey() != null) {
                    metricsNode.put("trace_id_key", metrics.getTraceIdKey());
                }
            }

            rootNode.set(path, metricsNode);
        });

        try {
            return MAPPER.writeValueAsString(rootNode);
        } catch (Exception e) {
            return "{}";
        }
    }
}
