package io.github.mcgr0g.distdoc.udaf.config;

import org.tomlj.TomlTable;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Настройки распознавания форматов значений: секция {@code [format]} файла {@code /app-config.toml}.
 * Правила распознавания — docs/contracts/value-formats.md.
 *
 * <p><b>Что настраивается.</b> Только path hints — суффиксы имени поля, которые дают право
 * считать <i>число</i> Unix-временем (по умолчанию {@code ["_at", "_utc"]}). Сравнивается
 * последний значимый слог пути (служебные {@code $date}/{@code $numberLong}/{@code $oid} и
 * {@code [*]} пропускаются), {@code endsWith} с учётом регистра. Строковые даты hints не
 * используют: их написание самоописательно и проверяется сканером формы и календаря
 * ({@link io.github.mcgr0g.distdoc.udaf.formats.FormatDetector#detectString(CharSequence)}).</p>
 *
 * <p><b>Жизненный цикл.</b> Финальный синглтон со статической ленивой инициализацией: секция
 * читается через {@link AppConfig} ровно один раз при первом обращении к {@link #getInstance()}.
 * Ресурс внутренний и контролируемый: дефолтов в коде нет, ошибка чтения/парсинга —
 * {@link IllegalStateException}.</p>
 *
 * <p><b>Переопределение окружением.</b> Переменная {@code DISTDOC_FORMAT_PATH_HINTS} (CSV через
 * запятую), если задана и не пуста, полностью заменяет пресет; элементы тримятся, пустые
 * отбрасываются ({@link AppConfig#resolveCsvOverride(String, List)}). Пример включения
 * camelCase: {@code DISTDOC_FORMAT_PATH_HINTS="_at,_utc,At"}.</p>
 *
 * <p><b>Тестируемость.</b> Сборка вынесена в {@link #from(TomlTable, Function)}: тест передаёт
 * реальную секцию и собственный источник env, не завися от окружения машины.</p>
 *
 * @see io.github.mcgr0g.distdoc.udaf.formats.FormatDetector
 */
public final class FormatSettings {

    /** Имя env-переменной, переопределяющей path hints. */
    static final String ENV_PATH_HINTS = "DISTDOC_FORMAT_PATH_HINTS";

    /** Лениво инициализируемый экземпляр настроек (инициализация при первом обращении). */
    private static final FormatSettings INSTANCE = from(AppConfig.section("format"), System::getenv);

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
     * Действующие path hints numeric timestamp.
     *
     * @return неизменяемый список суффиксов
     */
    public List<String> getPathHints() {
        return pathHints;
    }

    /**
     * Собирает настройки из секции {@code [format]} и источника переменных окружения.
     *
     * @param section секция {@code [format]}
     * @param env     источник env-переменных (в рантайме {@code System::getenv})
     * @return настройки
     */
    static FormatSettings from(TomlTable section, Function<String, String> env) {
        return new FormatSettings(resolvePathHints(env.apply(ENV_PATH_HINTS),
                AppConfig.stringList(section.getArray("path_hints"))));
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
