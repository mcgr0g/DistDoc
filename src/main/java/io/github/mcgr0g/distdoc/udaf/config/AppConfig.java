package io.github.mcgr0g.distdoc.udaf.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * Разрешает список с env-override: заданная (не {@code null} и не blank) переменная
     * полностью заменяет значение из toml; формат — CSV через запятую, элементы тримятся,
     * пустые отбрасываются. Общее правило для {@code [trace]} и {@code [format]}.
     *
     * @param envValue значение env-переменной (может быть {@code null})
     * @param fallback список из toml-секции
     * @return действующий список
     */
    public static List<String> resolveCsvOverride(String envValue, List<String> fallback) {
        if (envValue == null || envValue.isBlank()) {
            return fallback;
        }
        return Arrays.stream(envValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Десериализует TOML-массив строк в список.
     *
     * @param arr массив из секции
     * @return список элементов массива
     */
    public static List<String> stringList(TomlArray arr) {
        List<String> list = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            list.add(arr.getString(i));
        }
        return list;
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
