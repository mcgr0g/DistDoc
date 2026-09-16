package io.github.mcgr0g.distdoc.chaos;

import io.github.mcgr0g.distdoc.chaos.sources.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Центральный реестр (Registry) доступных источников генерации данных.
 */
public class SourceRegistry {
    private static final Map<String, ChaosSource> sources = new HashMap<>();

    static {
        // Регистрируем эталонный монструозный "источник, где забыли о миграциях"
        register(new ForgottenMigrationsSource());
    }

    private static void register(ChaosSource source) {
        sources.put(source.getName(), source);
    }

    /**
     * Извлекает зарегистрированный источник по его текстовому идентификатору.
     *
     * @param name имя источника (например, "monster-crm")
     * @return Optional с найденным источником
     */
    public static Optional<ChaosSource> getSource(String name) {
        return Optional.ofNullable(sources.get(name));
    }
}
