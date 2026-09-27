package io.github.mcgr0g.distdoc.udaf.config;

import org.tomlj.TomlTable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Настройки трассировки идентификаторов для отчёта {@code .rx.json}: секция {@code [trace]}
 * файла {@code /app-config.toml}. Правила трассировки — docs/testing/tracing.md.
 *
 * <p>Сделано по аналогии с {@link FormatSettings} (синглтон, отсутствие дефолтов в коде,
 * env-override CSV, фабрика {@link #from(TomlTable, Function)} для детерминированных тестов) —
 * общие механизмы описаны там. Здесь — только отличия.</p>
 *
 * <p>Переопределяются пресет ({@code DISTDOC_TRACE_PRESET}, CSV) и суффикс
 * ({@code DISTDOC_TRACE_SUFFIX}). Лимиты {@code max_ids}/{@code max_id_length}
 * переопределению не подлежат.</p>
 *
 * @see io.github.mcgr0g.distdoc.udaf.JsonSchemaAnalyzer
 */
public final class TraceSettings {

    /** Имена env-переменных, переопределяющих пресет и суффикс. */
    static final String ENV_PRESET = "DISTDOC_TRACE_PRESET";
    static final String ENV_SUFFIX = "DISTDOC_TRACE_SUFFIX";

    /** Лениво инициализируемый экземпляр настроек (инициализация при первом обращении). */
    private static final TraceSettings INSTANCE = from(AppConfig.section("trace"), System::getenv);

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
     * Собирает настройки из секции {@code [trace]} и источника переменных окружения.
     *
     * @param trace секция {@code [trace]}
     * @param env   источник env-переменных (в рантайме {@code System::getenv})
     * @return собранные настройки
     */
    static TraceSettings from(TomlTable trace, Function<String, String> env) {
        int maxIds = trace.getLong("max_ids").intValue();
        int maxIdLength = trace.getLong("max_id_length").intValue();
        List<String> preset = resolvePreset(env.apply(ENV_PRESET), AppConfig.stringList(trace.getArray("preset")));
        String suffix = resolveSuffix(env.apply(ENV_SUFFIX), trace.getString("suffix"));
        return new TraceSettings(maxIds, maxIdLength, preset, suffix);
    }

    /**
     * Разрешает пресет по общему правилу {@link AppConfig#resolveCsvOverride(String, List)}.
     *
     * @param envValue значение {@code DISTDOC_TRACE_PRESET} (может быть {@code null})
     * @param fallback пресет из {@code [trace]}
     * @return действующий пресет
     */
    static List<String> resolvePreset(String envValue, List<String> fallback) {
        return AppConfig.resolveCsvOverride(envValue, fallback);
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
