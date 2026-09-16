package io.github.mcgr0g.distdoc.udaf.config;

import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import java.io.InputStream;

/**
 * Единственная точка чтения внутреннего ресурса {@code /app-config.toml}.
 *
 * <p>Ресурс внутренний и контролируемый: любая ошибка чтения или парсинга —
 * {@link IllegalStateException} с сохранённой причиной, дефолтов нет.
 * Результат парсинга (ленивая статическая инициализация) переиспользуется всеми
 * потребителями секций.</p>
 */
public final class AppConfig {

    /** Результат парсинга {@code /app-config.toml} (инициализация при первом обращении). */
    private static final TomlParseResult ROOT = load();

    private AppConfig() {} // Запрещаем инстанцирование

    /**
     * Возвращает таблицу-секцию {@code /app-config.toml}.
     *
     * @param name имя секции (например, {@code "app"} или {@code "trace"})
     * @return таблица секции
     * @throws IllegalStateException если секции нет в ресурсе
     */
    public static TomlTable section(String name) {
        TomlTable table = ROOT.getTable(name);
        if (table == null) {
            throw new IllegalStateException("Секция [" + name + "] отсутствует в /app-config.toml");
        }
        return table;
    }

    /**
     * Читает и парсит ресурс {@code /app-config.toml}.
     *
     * @return результат парсинга без ошибок
     */
    private static TomlParseResult load() {
        try {
            InputStream is = AppConfig.class.getResourceAsStream("/app-config.toml");
            if (is == null) {
                throw new IllegalStateException("Ресурс /app-config.toml отсутствует в classpath — проверь processResources в build.gradle");
            }
            try (is) {
                TomlParseResult result = Toml.parse(is);
                if (result.hasErrors()) {
                    throw new IllegalStateException("Ошибка парсинга /app-config.toml: " + result.errors());
                }
                return result;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Ошибка чтения /app-config.toml", e);
        }
    }
}
