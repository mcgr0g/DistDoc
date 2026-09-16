package io.github.mcgr0g.distdoc.udaf.config;

import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Настройки трассировки идентификаторов для отчёта {@code .rx.json}: секция {@code [trace]}
 * файла {@code /app-config.toml}.
 *
 * <p>Класс является финальным синглтоном со статической ленивой инициализацией: секция
 * читается через {@link AppConfig} ровно один раз при первом обращении к {@link #getInstance()}.</p>
 *
 * <p>Пресет и суффикс могут быть переопределены переменными окружения
 * {@code DISTDOC_TRACE_PRESET} (CSV через запятую) и {@code DISTDOC_TRACE_SUFFIX}:
 * заданная непустая переменная полностью заменяет значение из {@code [trace]},
 * иначе берётся значение из toml. Лимиты {@code max_ids}/{@code max_id_length}
 * переопределению не подлежат.</p>
 *
 * @see io.github.mcgr0g.distdoc.udaf.JsonSchemaAnalyzer
 */
public final class TraceSettings {

    /** Лениво инициализируемый экземпляр настроек (инициализация при первом обращении). */
    private static final TraceSettings INSTANCE = load();

    private final int maxIds;
    private final int maxIdLength;
    private final List<String> preset;
    private final List<String> presetPaths;
    private final String suffix;

    private TraceSettings(int maxIds, int maxIdLength, List<String> preset, String suffix) {
        this.maxIds = maxIds;
        this.maxIdLength = maxIdLength;
        this.preset = Collections.unmodifiableList(new ArrayList<>(preset));

        // Предвычисленные абсолютные пути пресета от корня документа ("$.x"), в порядке приоритета
        List<String> paths = new ArrayList<>(preset.size());
        for (String entry : preset) {
            paths.add("$." + entry);
        }
        this.presetPaths = Collections.unmodifiableList(paths);
        this.suffix = suffix;
    }

    /**
     * Возвращает единственный экземпляр настроек трассировки.
     *
     * @return синглтон {@link TraceSettings}
     */
    public static TraceSettings getInstance() {
        return INSTANCE;
    }

    /**
     * Сколько идентификаторов хранить на один путь (обычные узлы и аномалии одинаково).
     *
     * @return лимит количества id на путь
     */
    public int getMaxIds() {
        return maxIds;
    }

    /**
     * Максимальная длина одного id-значения в отчёте, символов.
     *
     * @return лимит длины id
     */
    public int getMaxIdLength() {
        return maxIdLength;
    }

    /**
     * Ранжированный пресет id-путей в порядке приоритета (от корня, без префикса {@code "$."}).
     *
     * @return неизменяемый список путей пресета
     */
    public List<String> getPreset() {
        return preset;
    }

    /**
     * Абсолютные пути пресета от корня документа ({@code "$." + preset}), в порядке приоритета.
     *
     * @return неизменяемый список абсолютных путей пресета
     */
    public List<String> getPresetPaths() {
        return presetPaths;
    }

    /**
     * Суффикс-правило: поля верхнего уровня, оканчивающиеся на этот суффикс,
     * считаются id-кандидатами после пресета.
     *
     * @return суффикс (например, {@code "_id"})
     */
    public String getSuffix() {
        return suffix;
    }

    /**
     * Читает секцию {@code [trace]} через {@link AppConfig} и собирает настройки,
     * переопределяя пресет и суффикс значениями переменных окружения (если заданы).
     * Ресурс внутренний и контролируемый: любая ошибка чтения или парсинга —
     * {@link IllegalStateException}, дефолтов в коде нет.
     *
     * @return собранные настройки
     */
    private static TraceSettings load() {
        TomlTable trace = AppConfig.section("trace");
        int maxIds = trace.getLong("max_ids").intValue();
        int maxIdLength = trace.getLong("max_id_length").intValue();
        List<String> preset = resolvePreset(System.getenv("DISTDOC_TRACE_PRESET"), parsePreset(trace.getArray("preset")));
        String suffix = resolveSuffix(System.getenv("DISTDOC_TRACE_SUFFIX"), trace.getString("suffix"));
        return new TraceSettings(maxIds, maxIdLength, preset, suffix);
    }

    /**
     * Десериализует TOML-массив пресета в список.
     *
     * @param arr массив из секции {@code [trace]}
     * @return список элементов массива
     */
    private static List<String> parsePreset(TomlArray arr) {
        List<String> preset = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            preset.add(arr.getString(i));
        }
        return preset;
    }

    /**
     * Разрешает пресет: заданная (не {@code null} и не blank) env-переменная полностью
     * заменяет toml-пресет; формат — CSV через запятую, элементы тримятся, пустые отбрасываются.
     *
     * @param envValue значение {@code DISTDOC_TRACE_PRESET} (может быть {@code null})
     * @param fallback пресет из {@code [trace]}
     * @return действующий пресет
     */
    static List<String> resolvePreset(String envValue, List<String> fallback) {
        if (envValue == null || envValue.isBlank()) {
            return fallback;
        }
        return Arrays.stream(envValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Разрешает суффикс: заданная (не {@code null} и не blank) env-переменная полностью
     * заменяет toml-суффикс.
     *
     * @param envValue значение {@code DISTDOC_TRACE_SUFFIX} (может быть {@code null})
     * @param fallback суффикс из {@code [trace]}
     * @return действующий суффикс
     */
    static String resolveSuffix(String envValue, String fallback) {
        if (envValue == null || envValue.isBlank()) {
            return fallback;
        }
        return envValue.trim();
    }
}
