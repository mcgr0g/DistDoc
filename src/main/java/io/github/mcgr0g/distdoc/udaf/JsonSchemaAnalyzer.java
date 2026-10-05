package io.github.mcgr0g.distdoc.udaf;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mcgr0g.distdoc.udaf.anomalies.ArrayContext;
import io.github.mcgr0g.distdoc.udaf.anomalies.ArrayAnomalyDetector;
import io.github.mcgr0g.distdoc.udaf.anomalies.AnomalyDetector;
import io.github.mcgr0g.distdoc.udaf.config.CoreSettings;
import io.github.mcgr0g.distdoc.udaf.config.FormatSettings;
import io.github.mcgr0g.distdoc.udaf.config.TraceSettings;
import io.github.mcgr0g.distdoc.udaf.formats.FormatDetector;
import io.github.mcgr0g.distdoc.udaf.formats.ValueFormat;
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

    /** Логгер JDK: в Trino попадает в server.log узла. */
    private static final System.Logger LOG = System.getLogger(JsonSchemaAnalyzer.class.getName());

    /** Действующие path hints numeric timestamp (секция {@code [format]} + env-override). */
    private static final List<String> FORMAT_HINTS = FormatSettings.getInstance().getPathHints();

    /** Ключи отчёта и внутреннего состояния (форма отчёта — docs/contracts/rx-data-contract.md). */
    static final String FIELD_SCHEMA_VERSION = "schema_version";
    static final String FIELD_TYPE = "type";
    /** Только во внутреннем состоянии между воркерами: набор типов пути вместо свёрнутого {@code type}. */
    static final String FIELD_TYPES = "types";
    /** Только во внутреннем состоянии между воркерами: trace по типам (источник trace производных аномалий). */
    static final String FIELD_TYPE_TRACE = "type_trace";
    /** Форма пути в trace {@code is_polymorphic_structure}. */
    static final String FIELD_FORM = "form";
    static final String FIELD_MAX_LENGTH = "max_length";
    static final String FIELD_OBSERVED_FORMATS = "observed_formats";
    static final String FIELD_ANOMALIES = "anomalies";
    static final String FIELD_DETECTED = "detected";
    static final String FIELD_TRACE = "trace";
    static final String FIELD_PATH_TRACE = "path_trace";
    static final String FIELD_ID = "id";
    static final String FIELD_ID_KEY = "id_key";
    static final String FIELD_FORMAT = "format";
    /** Только во внутреннем состоянии между воркерами: trace по форматам (источник trace полиморфизма). */
    static final String FIELD_FORMAT_TRACE = "format_trace";

    /** Аномалии, вычисляемые при сборке отчёта: в состояние не пишутся, при чтении состояния игнорируются. */
    private static final Set<String> COMPUTED_ANOMALIES = Set.of(
            PathMetrics.ANOMALY_POLYMORPHIC_FORMAT, PathMetrics.ANOMALY_JSON_STRING,
            PathMetrics.ANOMALY_NON_JSON_STRINGS, PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE);

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

    /** Счётчик всех элементов текущего массива любого типа (единственный критерий пустоты, {@code is_array_empty}). */
    private final Deque<int[]> arrayCountStack = new ArrayDeque<>();

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

    /** Аномалии, впервые зафиксированные в текущей строке: путь -> имена аномалий. */
    private final Map<String, Set<String>> newAnomaliesThisRow = new HashMap<>();

    /** Форматы, впервые доказанные в текущей строке: путь -> форматы. */
    private final Map<String, Set<ValueFormat>> newFormatsThisRow = new HashMap<>();

    /** Типы, впервые зафиксированные на пути в текущей строке: путь -> типы (источник trace производных аномалий). */
    private final Map<String, Set<String>> newTypesThisRow = new HashMap<>();

    /** Объект, чей первый ключ ещё не прочитан: BSON-обёртка ({@code $…}) формы объекта не даёт. */
    private String pendingObjectPath = null;
    private String pendingObjectType = null;

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
            parseTokens(parser, rootPathStack, false);
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
            parseTokens(parser, rootPathStack, false);
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
        newAnomaliesThisRow.clear();
        newFormatsThisRow.clear();
        newTypesThisRow.clear();
        pendingObjectPath = null;
        pendingObjectType = null;
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

        // Одна пара {id, id_key} кладётся в каждый scope, который строка затронула впервые:
        // появление пути, каждая новая аномалия и каждый новый формат — независимые коллекции
        for (String path : newPathsThisRow) {
            schemaMap.get(path).addPathTrace(traceId, traceIdKey);
        }
        for (Map.Entry<String, Set<String>> e : newAnomaliesThisRow.entrySet()) {
            PathMetrics metrics = schemaMap.get(e.getKey());
            for (String name : e.getValue()) {
                metrics.addAnomalyTrace(name, traceId, traceIdKey);
            }
        }
        for (Map.Entry<String, Set<ValueFormat>> e : newFormatsThisRow.entrySet()) {
            PathMetrics metrics = schemaMap.get(e.getKey());
            for (ValueFormat format : e.getValue()) {
                metrics.addFormatTrace(format, traceId, traceIdKey);
            }
        }
        for (Map.Entry<String, Set<String>> e : newTypesThisRow.entrySet()) {
            PathMetrics metrics = schemaMap.get(e.getKey());
            for (String type : e.getValue()) {
                metrics.addTypeTrace(type, traceId, traceIdKey);
            }
        }
    }

    /**
     * Фиксирует тип на пути; в trace-режиме новый для пути тип запоминается для раскладки id в конце строки.
     */
    private void recordType(String path, String type) {
        if (getOrCreateMetrics(path).addType(type) && isTraceActive()) {
            newTypesThisRow.computeIfAbsent(path, k -> new TreeSet<>()).add(type);
        }
    }

    /**
     * Фиксирует доказанный формат скаляра на его пути (scalar-leaf, docs/contracts/value-formats.md);
     * в trace-режиме новый для пути формат запоминается для раскладки id в конце строки.
     *
     * @param path   путь скалярного значения
     * @param format формат или {@code null} (не доказан)
     */
    private void recordFormat(String path, ValueFormat format) {
        if (format != null && getOrCreateMetrics(path).addFormat(format) && isTraceActive()) {
            newFormatsThisRow.computeIfAbsent(path, k -> EnumSet.noneOf(ValueFormat.class)).add(format);
        }
    }

    /**
     * Внутренний циклический диспетчер токенов Jackson.
     * Управляет навигацией по документу и координирует рост/сокращение стека путей.
     *
     * <p>Формы пути (контракт rx-data 3.0, раздел 2b): объект — значение ключа — фиксирует на своём пути тип
     * {@code OBJECT} (в отчёт попадает только у неоднородного поля); BSON-обёртка (первый ключ начинается с {@code $}),
     * корень документа и объект-элемент массива формы не дают; корень содержимого jsonstring даёт {@code JSON_OBJECT}.</p>
     *
     * @param parser      активный экземпляр парсера Jackson
     * @param pathStack   изолированный стек путей текущего уровня вложенности (рекурсии)
     * @param subDocument {@code true} для содержимого jsonstring: первый контейнер — значение пути строки, а не объект на нём
     * @throws Exception при системных ошибках чтения потока
     */
    private void parseTokens(JsonParser parser, Deque<String> pathStack, boolean subDocument) throws Exception {
        int depth = 0;
        // Какие открытые массивы поставили маркер [*] на стек путей: внутренний массив (массив в массиве) путь не меняет
        // и маркер не снимает — иначе второй и следующие внутренние массивы уходят на корневой путь $[*]
        Deque<Boolean> ownsMarker = new ArrayDeque<>();
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
            String currentPath = buildJsonPath(pathStack);

            switch (token) {
                case FIELD_NAME:
                    resolvePendingObject(parser.currentName());
                    pathStack.push(parser.currentName());
                    break;

                case START_OBJECT:
                    countArrayElement(pathStack);
                    if (isInsideArray(pathStack)) {
                        invalidateFlatArrayFlags();
                    } else if (isKeyedValue(pathStack)) {
                        // Форма объекта решается по первому ключу (BSON-обёртка — не объект); {} решается на END_OBJECT
                        pendingObjectPath = currentPath;
                        pendingObjectType = (subDocument && depth == 0) ? PathMetrics.TYPE_JSON_OBJECT : PathMetrics.TYPE_OBJECT;
                    }
                    depth++;
                    break;

                case START_ARRAY:
                    countArrayElement(pathStack);
                    depth++;
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        String arrayField = pathStack.pop();
                        pathStack.push(arrayField + "[*]");
                        ownsMarker.push(true);
                    } else if (pathStack.isEmpty() || pathStack.peek().equals("$")) {
                        pathStack.push("[*]");
                        ownsMarker.push(true);
                    } else {
                        ownsMarker.push(false); // массив в массиве: путь тот же ($.a[*])
                    }

                    String fullArrayPath = buildJsonPath(pathStack);
                    recordType(fullArrayPath, PathMetrics.TYPE_ARRAY);

                    arrayPathStack.push(fullArrayPath);
                    arrayElementsStack.push(new ArrayList<>());
                    arrayCountStack.push(new int[1]);
                    arrayAllStringsStack.push(true);
                    arrayAllNumbersStack.push(true);
                    break;

                case END_OBJECT:
                    resolvePendingObject(null); // пустой объект: ключей не было, обёрткой быть не может
                    depth--;
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case END_ARRAY:
                    depth--;
                    handleEndArray();
                    boolean owned = ownsMarker.isEmpty() || ownsMarker.pop();
                    if (owned && !pathStack.isEmpty() && pathStack.peek().endsWith("[*]")) {
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
                    countArrayElement(pathStack);
                    recordType(currentPath, "DOUBLE");
                    recordFormat(currentPath, FormatDetector.detectDouble(
                            parser.getDoubleValue(), FormatDetector.numericContext(currentPath, FORMAT_HINTS)));
                    if (isInsideArray(pathStack)) invalidateFlatArrayFlags();
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case VALUE_TRUE:
                case VALUE_FALSE:
                    countArrayElement(pathStack);
                    recordType(currentPath, "BOOLEAN");
                    if (isInsideArray(pathStack)) invalidateFlatArrayFlags();
                    if (!pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]")) {
                        pathStack.pop();
                    }
                    break;

                case VALUE_NULL:
                    countArrayElement(pathStack);
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
        countArrayElement(pathStack);

        // JSON-строка: валидный JSON-контейнер целиком. Невалидный текст ([TEST], {abc}) — обычная строка:
        // валидирующий проход до рабочего не оставляет в схеме частично записанных путей
        if (isJsonContainer(text)) {
            try (JsonParser subParser = FACTORY.createParser(text)) {
                parseTokens(subParser, new ArrayDeque<>(pathStack), true);
            }
            // Путь строки: JSON_STRING (в отчёте VARCHAR, is_json_string), длина сырой строки; элемент массива — не плоский литерал
            recordType(currentPath, PathMetrics.TYPE_JSON_STRING);
            getOrCreateMetrics(currentPath).updateLength(text.length());
            if (isInsideArray(pathStack)) {
                invalidateFlatArrayFlags();
            }
            return;
        }

        // Сбор trace-id из обычной строки (экранированный под-документ выше уже обработан рекурсией)
        collectTraceValue(currentPath, text);

        recordType(currentPath, PathMetrics.TYPE_VARCHAR);
        getOrCreateMetrics(currentPath).updateLength(parser.getTextLength());
        recordFormat(currentPath, FormatDetector.detectString(text, currentPath, FORMAT_HINTS));

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
        countArrayElement(pathStack);
        // Сбор trace-id из целочисленного скаляра (BSON-числовые id не типичны, но целые — валидный кандидат)
        collectTraceValue(currentPath, parser.getText());

        recordType(currentPath, "INTEGER");
        // Значения вне диапазона long (BIG_INTEGER) Unix-временем не считаются
        JsonParser.NumberType numberType = parser.getNumberType();
        if (numberType == JsonParser.NumberType.INT || numberType == JsonParser.NumberType.LONG) {
            recordFormat(currentPath, FormatDetector.detectInteger(
                    parser.getLongValue(), FormatDetector.numericContext(currentPath, FORMAT_HINTS)));
        }
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
            int totalElements = arrayCountStack.pop()[0];

            PathMetrics metrics = getOrCreateMetrics(finishedArrayPath);

            // Снимок аномалий до детекции (в trace-режиме — чтобы найти впервые зафиксированные)
            Set<String> before = isTraceActive() ? Set.copyOf(metrics.getAnomalies()) : null;

            // Декларативно делегируем анализ выделенной стратегии
            ArrayContext context = new ArrayContext(finishedArrayPath, elements, totalElements, allStrings, allNumbers);
            anomalyDetector.detect(context, metrics);

            // Каждая новая аномалия получает свой trace scope; id-поле может идти в документе
            // ПОСЛЕ массива, поэтому резолв отложен в resolveTraceId
            if (before != null && metrics.getAnomalies().size() > before.size()) {
                for (String name : metrics.getAnomalies()) {
                    if (!before.contains(name)) {
                        newAnomaliesThisRow.computeIfAbsent(finishedArrayPath, k -> new TreeSet<>()).add(name);
                    }
                }
            }
        }
    }

    /** Вершина стека — имя ключа (значение лежит по ключу объекта), а не корень {@code $} и не элемент массива. */
    private boolean isKeyedValue(Deque<String> pathStack) {
        return !pathStack.isEmpty() && !pathStack.peek().equals("$") && !pathStack.peek().endsWith("[*]");
    }

    /** Учитывает значение, являющееся прямым элементом текущего массива (любого типа, включая объект и null). */
    private void countArrayElement(Deque<String> pathStack) {
        if (isInsideArray(pathStack) && !arrayCountStack.isEmpty()) {
            arrayCountStack.peek()[0]++;
        }
    }

    /**
     * Решает форму ожидающего объекта по первому ключу: имя на {@code $} — BSON-обёртка (формы объекта нет),
     * иначе фиксируется {@code OBJECT}/{@code JSON_OBJECT}. {@code firstKey == null} — пустой объект.
     */
    private void resolvePendingObject(String firstKey) {
        if (pendingObjectPath == null) {
            return;
        }
        if (firstKey == null || !firstKey.startsWith("$")) {
            recordType(pendingObjectPath, pendingObjectType);
        }
        pendingObjectPath = null;
        pendingObjectType = null;
    }

    /**
     * Текст — целиком валидный JSON-объект или массив: один корневой контейнер, без хвоста (jsonstring).
     * Отсекает строки, лишь похожие на JSON ({@code [TEST]}, {@code {abc}}, {@code [1,}).
     */
    private static boolean isJsonContainer(String text) {
        if (text == null || text.length() < 2) {
            return false;
        }
        boolean braces = text.startsWith("{") && text.endsWith("}");
        boolean brackets = text.startsWith("[") && text.endsWith("]");
        if (!braces && !brackets) {
            return false;
        }
        try (JsonParser probe = FACTORY.createParser(text)) {
            probe.nextToken();
            probe.skipChildren();
            return probe.nextToken() == null;
        } catch (java.io.IOException e) {
            return false; // невалидный текст — результат проверки, а не сбой: обрабатывается как обычная строка
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
     * Декларативная сборка итогового rx-data (контракт 3.0) через Jackson ObjectNode.
     * Пути записываются в лексикографическом порядке; на пути — {@code type}, {@code max_length},
     * {@code observed_formats?}, {@code anomalies?} ({@code {detected, trace?}}), {@code path_trace?}.
     * Trace {@code is_polymorphic_format} собирается из trace форматов: элементы несут поле {@code format}.
     *
     * @return сериализованная JSON-строка отчета по всей структуре документа
     */
    public String buildJsonReport() {
        return buildJson(false);
    }

    /**
     * Сборка внутреннего состояния для передачи между воркерами ({@link SchemaStateSerializer}).
     * Отличия от отчёта: вычисляемая аномалия {@code is_polymorphic_format} не пишется (восстанавливается
     * из форматов), trace форматов пишется как есть в {@code format_trace}.
     *
     * @return сериализованная JSON-строка состояния
     */
    public String buildStateJson() {
        return buildJson(true);
    }

    private String buildJson(boolean internal) {
        ObjectNode rootNode = MAPPER.createObjectNode();

        // Версия схемы rx-data (major.minor): корневой служебный ключ, присутствует во всех режимах
        rootNode.put(FIELD_SCHEMA_VERSION, CoreSettings.getSchemaVersion());

        new TreeMap<>(schemaMap).forEach((path, metrics) -> {
            Set<String> forms = structureForms(path);
            // Чистый объект собственной записи не имеет: запись нужна, только если форма поля неоднородна
            if (!internal && isPureObject(metrics) && forms.size() < 2) {
                return;
            }
            ObjectNode metricsNode = rootNode.putObject(path);
            if (internal) {
                // Состояние несёт набор типов целиком: свёрнутый type терял бы форму при слиянии воркеров
                ArrayNode typesNode = metricsNode.putArray(FIELD_TYPES);
                metrics.getTypes().forEach(typesNode::add);
            } else {
                metricsNode.put(FIELD_TYPE, metrics.getFinalType());
            }
            metricsNode.put(FIELD_MAX_LENGTH, metrics.getMaxLength());

            if (!metrics.getFormats().isEmpty()) {
                ArrayNode formatsNode = metricsNode.putArray(FIELD_OBSERVED_FORMATS);
                metrics.getFormats().forEach(f -> formatsNode.add(f.name()));
            }

            TreeSet<String> names = new TreeSet<>(metrics.getAnomalies());
            if (!internal) {
                if (metrics.isPolymorphicFormat()) {
                    names.add(PathMetrics.ANOMALY_POLYMORPHIC_FORMAT);
                }
                if (metrics.isJsonString()) {
                    names.add(PathMetrics.ANOMALY_JSON_STRING);
                }
                if (metrics.hasNonJsonStrings()) {
                    names.add(PathMetrics.ANOMALY_NON_JSON_STRINGS);
                }
                if (forms.size() >= 2) {
                    names.add(PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE);
                }
            }
            if (!names.isEmpty()) {
                ObjectNode anomaliesNode = metricsNode.putObject(FIELD_ANOMALIES);
                for (String name : names) {
                    ObjectNode anomalyNode = anomaliesNode.putObject(name);
                    anomalyNode.put(FIELD_DETECTED, true);
                    switch (name) {
                        case PathMetrics.ANOMALY_POLYMORPHIC_FORMAT -> {
                            ArrayNode traceNode = MAPPER.createArrayNode();
                            metrics.getFormatTrace().forEach((format, evidence) ->
                                    writeTrace(traceNode, evidence, format, null));
                            if (!traceNode.isEmpty()) {
                                anomalyNode.set(FIELD_TRACE, traceNode);
                            }
                        }
                        case PathMetrics.ANOMALY_POLYMORPHIC_STRUCTURE -> {
                            ArrayNode traceNode = MAPPER.createArrayNode();
                            for (String form : forms) {
                                TraceEvidence evidence = formEvidence(path, metrics, form);
                                if (evidence != null) {
                                    writeTrace(traceNode, evidence, null, form);
                                }
                            }
                            if (!traceNode.isEmpty()) {
                                anomalyNode.set(FIELD_TRACE, traceNode);
                            }
                        }
                        case PathMetrics.ANOMALY_JSON_STRING ->
                                writeTraceIfAny(anomalyNode, metrics.getTypeTrace().get(PathMetrics.TYPE_JSON_STRING));
                        case PathMetrics.ANOMALY_NON_JSON_STRINGS ->
                                writeTraceIfAny(anomalyNode, metrics.getTypeTrace().get(PathMetrics.TYPE_VARCHAR));
                        default -> writeTraceIfAny(anomalyNode, metrics.getAnomalyTrace().get(name));
                    }
                }
            }

            TraceEvidence pathTrace = metrics.getPathTrace();
            if (pathTrace != null && !pathTrace.isEmpty()) {
                writeTrace(metricsNode.putArray(FIELD_PATH_TRACE), pathTrace, null, null);
            }

            if (internal && !metrics.getFormatTrace().isEmpty()) {
                ObjectNode formatTraceNode = metricsNode.putObject(FIELD_FORMAT_TRACE);
                metrics.getFormatTrace().forEach((format, evidence) ->
                        writeTrace(formatTraceNode.putArray(format.name()), evidence, null, null));
            }
            if (internal && !metrics.getTypeTrace().isEmpty()) {
                ObjectNode typeTraceNode = metricsNode.putObject(FIELD_TYPE_TRACE);
                metrics.getTypeTrace().forEach((type, evidence) ->
                        writeTrace(typeTraceNode.putArray(type), evidence, null, null));
            }
        });

        try {
            return MAPPER.writeValueAsString(rootNode);
        } catch (Exception e) {
            // Паттерн 5: сбой маршалинга не роняет запрос — пустая схема той же версии
            LOG.log(System.Logger.Level.WARNING, "analyze_json_schema: не удалось сериализовать схему", e);
            return "{\"" + FIELD_SCHEMA_VERSION + "\":\"" + CoreSettings.getSchemaVersion() + "\"}";
        }
    }

    /** Дописывает пары evidence в массив trace; {@code format}/{@code form} (если не {@code null}) — поле каждого элемента. */
    private static void writeTrace(ArrayNode target, TraceEvidence evidence, ValueFormat format, String form) {
        for (TraceEvidence.Pair pair : evidence.items()) {
            ObjectNode item = target.addObject();
            item.put(FIELD_ID, pair.id());
            item.put(FIELD_ID_KEY, pair.idKey());
            if (format != null) {
                item.put(FIELD_FORMAT, format.name());
            }
            if (form != null) {
                item.put(FIELD_FORM, form);
            }
        }
    }

    private static void writeTraceIfAny(ObjectNode anomalyNode, TraceEvidence evidence) {
        if (evidence != null && !evidence.isEmpty()) {
            writeTrace(anomalyNode.putArray(FIELD_TRACE), evidence, null, null);
        }
    }

    /** Путь только с типом {@code OBJECT}: чистый объект (собственной записи в отчёте нет). */
    private static boolean isPureObject(PathMetrics metrics) {
        return metrics.getTypes().equals(Set.of(PathMetrics.TYPE_OBJECT));
    }

    /**
     * Формы пути {@code path} (контракт rx-data 3.0, раздел 2b), вычисляются по слитому состоянию: {@code SCALAR},
     * {@code OBJECT}, {@code JSON_OBJECT} — по набору типов пути, {@code ARRAY} — по наличию записи {@code path[*]}.
     * Для путей-массивов ({@code …[*]}) форм нет.
     *
     * @param path путь без суффикса {@code [*]}
     * @return формы в алфавитном порядке; неоднородное поле — две и более
     */
    Set<String> structureForms(String path) {
        Set<String> forms = new TreeSet<>();
        PathMetrics metrics = schemaMap.get(path);
        if (metrics == null || path.endsWith("[*]")) {
            return forms;
        }
        if (metrics.hasScalar()) forms.add("SCALAR");
        if (metrics.getTypes().contains(PathMetrics.TYPE_OBJECT)) forms.add("OBJECT");
        if (metrics.getTypes().contains(PathMetrics.TYPE_JSON_OBJECT)) forms.add("JSON_OBJECT");
        if (schemaMap.containsKey(path + "[*]")) forms.add("ARRAY");
        return forms;
    }

    /** Первые документы, давшие форму пути (trace {@code is_polymorphic_structure}); {@code null} — trace не собирался. */
    private TraceEvidence formEvidence(String path, PathMetrics metrics, String form) {
        switch (form) {
            case "ARRAY" -> {
                return schemaMap.get(path + "[*]").getTypeTrace().get(PathMetrics.TYPE_ARRAY);
            }
            case "OBJECT" -> {
                return metrics.getTypeTrace().get(PathMetrics.TYPE_OBJECT);
            }
            case "JSON_OBJECT" -> {
                return metrics.getTypeTrace().get(PathMetrics.TYPE_JSON_OBJECT);
            }
            default -> {
                TraceEvidence merged = null;
                for (String type : PathMetrics.SCALAR_TYPES) {
                    TraceEvidence evidence = metrics.getTypeTrace().get(type);
                    if (evidence != null) {
                        if (merged == null) merged = new TraceEvidence();
                        merged.merge(evidence);
                    }
                }
                return merged;
            }
        }
    }

    /**
     * Восстанавливает анализатор из внутреннего состояния ({@link JsonSchemaAnalyzer#buildStateJson()}).
     *
     * <p><b>Fault tolerance (паттерн 5, docs/patterns/plugin.md):</b> сбой разбора не роняет SQL-запрос.
     * Путь со служебным полем или форматом вне whitelist (состояние от воркера другой версии плагина)
     * пропускается целиком, остальные пути сохраняются; невалидный JSON даёт пустое состояние
     * воркера. Каждый пропуск — {@code WARNING} в логе сервера Trino с путём и причиной.
     * Ключи-пути ({@code $.x}, {@code $.x[*].y}) — данные и не проверяются; набор типов пути проверяется
     * по {@link PathMetrics#KNOWN_TYPES}.</p>
     *
     * @param serializedData JSON состояния
     * @return анализатор с восстановленной (возможно, частично) картой путей
     */
    static JsonSchemaAnalyzer fromStateJson(String serializedData) {
        JsonSchemaAnalyzer analyzer = new JsonSchemaAnalyzer();
        JsonNode root;
        try {
            root = MAPPER.readTree(serializedData);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING,
                    "analyze_json_schema: состояние воркера не разобрано (невалидный JSON), вклад воркера потерян", e);
            return analyzer;
        }

        Map<String, PathMetrics> schemaMap = analyzer.getSchemaMap();
        Iterator<Map.Entry<String, JsonNode>> paths = root.fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> entry = paths.next();
            // Корневой служебный ключ версии схемы не является jsonpath-узлом схемы
            if (entry.getKey().equals(FIELD_SCHEMA_VERSION)) {
                continue;
            }
            try {
                schemaMap.put(entry.getKey(), readMetrics(entry.getValue()));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "analyze_json_schema: путь " + entry.getKey()
                        + " пропущен при слиянии воркеров: " + e.getMessage()
                        + " (разные версии плагина на узлах?)");
            }
        }
        return analyzer;
    }

    private static PathMetrics readMetrics(JsonNode node) {
        PathMetrics metrics = new PathMetrics();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            switch (field.getKey()) {
                case FIELD_TYPES -> {
                    for (JsonNode t : value) {
                        metrics.addType(parseType(t.asText()));
                    }
                }
                case FIELD_MAX_LENGTH -> metrics.updateLength(value.asLong());
                case FIELD_OBSERVED_FORMATS -> {
                    for (JsonNode f : value) {
                        metrics.addFormat(parseFormat(f.asText()));
                    }
                }
                case FIELD_ANOMALIES -> value.fields().forEachRemaining(a -> {
                    String name = a.getKey();
                    if (COMPUTED_ANOMALIES.contains(name)) {
                        return; // вычисляется при сборке отчёта
                    }
                    metrics.markAnomaly(name);
                    JsonNode trace = a.getValue().get(FIELD_TRACE);
                    if (trace != null) {
                        for (JsonNode item : trace) {
                            metrics.addAnomalyTrace(name, item.get(FIELD_ID).asText(), item.get(FIELD_ID_KEY).asText());
                        }
                    }
                });
                case FIELD_PATH_TRACE -> {
                    for (JsonNode item : value) {
                        metrics.addPathTrace(item.get(FIELD_ID).asText(), item.get(FIELD_ID_KEY).asText());
                    }
                }
                case FIELD_FORMAT_TRACE -> value.fields().forEachRemaining(f -> {
                    ValueFormat format = parseFormat(f.getKey());
                    for (JsonNode item : f.getValue()) {
                        metrics.addFormatTrace(format, item.get(FIELD_ID).asText(), item.get(FIELD_ID_KEY).asText());
                    }
                });
                case FIELD_TYPE_TRACE -> value.fields().forEachRemaining(t -> {
                    String type = parseType(t.getKey());
                    for (JsonNode item : t.getValue()) {
                        metrics.addTypeTrace(type, item.get(FIELD_ID).asText(), item.get(FIELD_ID_KEY).asText());
                    }
                });
                default -> throw new IllegalStateException("служебное поле вне whitelist: " + field.getKey());
            }
        }
        return metrics;
    }

    private static String parseType(String name) {
        if (!PathMetrics.KNOWN_TYPES.contains(name)) {
            throw new IllegalStateException("неизвестный тип: " + name);
        }
        return name;
    }

    private static ValueFormat parseFormat(String name) {
        try {
            return ValueFormat.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("неизвестный формат: " + name, e);
        }
    }
}
