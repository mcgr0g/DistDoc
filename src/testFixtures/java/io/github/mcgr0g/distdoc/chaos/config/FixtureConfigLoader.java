package io.github.mcgr0g.distdoc.chaos.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import java.io.InputStream;
import java.io.IOException;

public class FixtureConfigLoader {

    /**
     * Контейнер (DTO) для хранения параметров подключения к Trino.
     */
    public static class ClusterInfo {
        public final String host;
        public final int port;
        public final String catalog;
        public final String schema;
        public final String user;

        public ClusterInfo(String host, int port, String catalog, String schema, String user) {
            this.host = host;
            this.port = port;
            this.catalog = catalog;
            this.schema = schema;
            this.user = user;
        }

        public String getJdbcUrl() {
            return String.format("jdbc:trino://%s:%d/%s/%s", host, port, catalog, schema);
        }
    }

    /**
     * Чтение сценариев данных из TOML-ресурса
     */
    public static TomlTable loadTableConfig() throws IOException {
        // Читаем файл из ресурсов как поток данных
        try (InputStream is = FixtureConfigLoader.class.getResourceAsStream("/fixtures.toml")) {
            if (is == null) {
                throw new java.io.FileNotFoundException("Файл fixtures.toml не найден в ресурсах проекта!");
            }
            // Парсим поток напрямую в объект TomlTable
            return Toml.parse(is);
        }
    }

    /**
     * Чтение параметров кластера из переменных окружения.
     * Поскольку mise автоматически загружает fixtures.env в ENV при старте,
     * нам не нужно вручную парсить .env файл — мы берем значения прямо из ОС.
     */
    public static ClusterInfo loadClusterConfig() {
        String host = getEnvOrDefault("TRINO_HOST", "localhost");
        int port = Integer.parseInt(getEnvOrDefault("TRINO_PORT", "8080"));
        String catalog = getEnvOrDefault("TRINO_CATALOG", "lakehouse");
        String schema = getEnvOrDefault("TRINO_SCHEMA", "default");
        String user = getEnvOrDefault("TRINO_USER", "admin");

        return new ClusterInfo(host, port, catalog, schema, user);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.trim().isEmpty()) ? value : defaultValue;
    }
}