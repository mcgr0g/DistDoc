package io.github.mcgr0g.distdoc.udaf.config;

/**
 * Корневые настройки плагина: секция {@code [app]} файла {@code /app-config.toml}.
 *
 * <p>Полная версия плагина (gradle.properties, {@code distdocVersion}) подставляется Gradle
 * в {@code app.distdoc_version} при сборке; сокращение до major.minor выполняется здесь —
 * единственное место правила.
 * Ресурс внутренний и контролируемый: любая ошибка — {@link IllegalStateException},
 * дефолтов нет.</p>
 */
public final class CoreSettings {

    /** Лениво инициализируемая версия схемы (инициализация при первом обращении). */
    private static final String SCHEMA_VERSION = loadSchemaVersion();

    private CoreSettings() {} // Запрещаем инстанцирование

    /**
     * Возвращает версию схемы rx-data (например, {@code "1.1"}).
     *
     * @return версия схемы в формате major.minor
     */
    public static String getSchemaVersion() {
        return SCHEMA_VERSION;
    }

    /**
     * Читает {@code app.distdoc_version} из {@code /app-config.toml} и сокращает до major.minor.
     *
     * @return версия схемы (первые два сегмента полной версии)
     */
    private static String loadSchemaVersion() {
        String full = AppConfig.section("app").getString("distdoc_version");
        String[] parts = full.split("\\.");
        return parts[0] + "." + parts[1];
    }
}
