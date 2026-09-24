package io.github.mcgr0g.distdoc.udaf.config;

import org.tomlj.TomlTable;
import java.util.Collections;
import java.util.List;

/**
 * Настройки распознавания форматов значений: секция {@code [format]} файла
 * {@code /app-config.toml} (docs/adr/0006-format-detection.md).
 *
 * <p>Финальный синглтон со статической ленивой инициализацией, по образцу {@link TraceSettings}:
 * секция читается через {@link AppConfig} ровно один раз при первом обращении к
 * {@link #getInstance()}; дефолтов в коде нет, ошибка чтения/парсинга —
 * {@link IllegalStateException}.</p>
 *
 * <p>Path hints могут быть переопределены переменной окружения
 * {@code DISTDOC_FORMAT_PATH_HINTS} (CSV через запятую): заданная непустая переменная
 * полностью заменяет пресет из {@code [format]}.</p>
 *
 * @see io.github.mcgr0g.distdoc.udaf.formats.FormatDetector
 */
public final class FormatSettings {

    /** Лениво инициализируемый экземпляр настроек (инициализация при первом обращении). */
    private static final FormatSettings INSTANCE = load();

    private final List<String> pathHints;

    private FormatSettings(List<String> pathHints) {
        this.pathHints = Collections.unmodifiableList(pathHints);
    }

    /**
     * Возвращает единственный экземпляр настроек форматов.
     *
     * @return синглтон {@link FormatSettings}
     */
    public static FormatSettings getInstance() {
        return INSTANCE;
    }

    /**
     * Суффиксы последнего значимого слога пути, дающие контекст numeric timestamp
     * (например, {@code ["_at", "_utc"]}). Строковые форматы от hints не зависят.
     *
     * @return неизменяемый список суффиксов
     */
    public List<String> getPathHints() {
        return pathHints;
    }

    /**
     * Читает секцию {@code [format]} через {@link AppConfig}, переопределяя hints
     * значением переменной окружения (если задана).
     *
     * @return собранные настройки
     */
    private static FormatSettings load() {
        TomlTable format = AppConfig.section("format");
        List<String> hints = resolvePathHints(System.getenv("DISTDOC_FORMAT_PATH_HINTS"),
                AppConfig.stringList(format.getArray("path_hints")));
        return new FormatSettings(hints);
    }

    /**
     * Разрешает path hints по общему правилу {@link AppConfig#resolveCsvOverride(String, List)}.
     *
     * @param envValue значение {@code DISTDOC_FORMAT_PATH_HINTS} (может быть {@code null})
     * @param fallback hints из {@code [format]}
     * @return действующие hints
     */
    static List<String> resolvePathHints(String envValue, List<String> fallback) {
        return AppConfig.resolveCsvOverride(envValue, fallback);
    }
}
