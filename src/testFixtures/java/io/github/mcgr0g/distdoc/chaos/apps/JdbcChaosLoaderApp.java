package io.github.mcgr0g.distdoc.chaos.apps;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mcgr0g.distdoc.chaos.config.FixtureConfigLoader;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Автоматизированная точка входа для ультра-быстрой циклической загрузки хаос-данных в Trino.
 * Использует Jackson для упаковки JSONL в один массив, полностью исключая сетевые циклы JDBC.
 */
public class JdbcChaosLoaderApp {

    // Используем готовый Jackson-парсер, который уже объявлен в build.gradle
    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("📥 Запуск JDBC-загрузчика in-memory Trino");
        System.out.println("===============================================================");

        try {
            // 1. Загружаем единый TOML-конфиг и переменные из fixtures.env
            TomlTable config = FixtureConfigLoader.loadTableConfig();
            FixtureConfigLoader.ClusterInfo cluster = FixtureConfigLoader.loadClusterConfig();

            TomlArray scenarios = config.getArray("scenarios");
            if (scenarios == null || scenarios.isEmpty()) {
                System.out.println("⚠️ Предупреждение: В fixtures.toml не найдено сценариев для импорта.");
                System.exit(0);
            }

            Properties props = new Properties();
            props.setProperty("user", "admin"); // Дефолтный пользователь для локального стенда

            String url = cluster.getJdbcUrl();
            System.out.printf("🔗 Ожидание готовности кластера Trino (%s)...\n", url);

            // Встроенный Healthcheck-ожидатель ===
            Connection conn = null;
            int maxAttempts = 15; // 15 попыток по 2 секунды = 30 секунд максимум
            int attempt = 0;

            while (conn == null) {
                try {
                    attempt++;
                    // Пытаемся открыть честное JDBC-соединение
                    conn = DriverManager.getConnection(url, props);

                    // Проверяем, что движок не просто открыл порт, а готов выполнять SQL
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("SELECT 1");
                    }
                    System.out.println("✅ Кластер Trino успешно запущен и готов к приему данных!");

                } catch (java.sql.SQLException e) {
                    if (attempt >= maxAttempts) {
                        System.err.println("\n❌ Тайм-аут: Кластер Trino не ответил за 30 секунд. Проверьте логи Docker (mise run up).");
                        System.exit(1);
                    }
                    if (conn != null) { conn.close(); conn = null; } // Сбрасываем грязное соединение

                    System.out.printf("   [Попытка %d/%d] Сервер еще инициализируется, ждем 2 сек...\n", attempt, maxAttempts);
                    Thread.sleep(2000); // Засыпаем на 2 секунды перед следующим пингом
                }
            }

            long totalLinesLoaded = 0;
            long startTime = System.currentTimeMillis();

            // 2. Открываем единое соединение
            try (Connection finalConn = conn) { // Оборачиваем выжившее соединение в try-with-resources

                // Гарантируем наличие схемы
                try (Statement stmt = finalConn.createStatement()) {
                    // noinspection SqlInjection
                    stmt.execute(String.format("CREATE SCHEMA IF NOT EXISTS %s.%s", cluster.catalog, cluster.schema));
                }

                // 3. Перебор сценариев без использования addBatch()
                for (int i = 0; i < scenarios.size(); i++) {
                    TomlTable scenarioConfig = scenarios.getTable(i);
                    String tableName = scenarioConfig.getString("table");
                    String filePath = scenarioConfig.getString("file");

                    System.out.printf("[Импорт %d/%d] Заливка таблицы: %s\n", i + 1, scenarios.size(), tableName);
                    System.out.printf("  ├─ Чтение файла: %s\n", filePath);

                    if (!Files.exists(Paths.get(filePath))) {
                        System.out.printf("  ❌ Ошибка: Файл %s не найден! Пропустите шаг или запустите генерацию (mise run lc-gen).\n", filePath);
                        continue;
                    }

                    // Восстанавливаем DDL структуру в памяти, если контейнер перезапускался
                    try (Statement stmt = finalConn.createStatement()) {
                        // noinspection SqlInjection
                        stmt.execute(String.format("CREATE TABLE IF NOT EXISTS %s.%s.%s (line VARCHAR)", cluster.catalog, cluster.schema, tableName));
                    }

                    // Запускаем оптимизированный JSON-пайплайн
                    int loadedCount = loadJsonlWithoutLoops(finalConn, tableName, filePath);
                    totalLinesLoaded += loadedCount;
                }
            }

            long totalDuration = System.currentTimeMillis() - startTime;
            System.out.println("===============================================================");
            System.out.println("🎉 Все фикстуры успешно импортированы в RAM кластера Trino!");
            System.out.printf("📊 Суммарно загружено строк: %d\n", totalLinesLoaded);
            System.out.printf("⏱️ Общее время трансляции: %d мс\n", totalDuration);
            System.out.println("===============================================================");

        } catch (Exception e) {
            System.err.println("\n❌ Критическая ошибка JDBC-заливки данных в кластер Trino:");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Логика трансляции JSONL-файла в Trino за ОДИН сетевой запрос.
     */
    private static int loadJsonlWithoutLoops(Connection conn, String tableName, String filePath) throws Exception {
        long tableStartTime = System.currentTimeMillis();

        // 1. Быстро вычитываем все строки в Java-коллекцию
        List<String> allLines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    allLines.add(line);
                }
            }
        }

        if (allLines.isEmpty()) {
            System.out.println("  ⚠️ Файл пуст, пропускаем.");
            return 0;
        }

        // 2. Упаковываем все строки в один экранированный JSON-массив ["строка1", "строка2"]
        String jsonArrayString = mapper.writeValueAsString(allLines);

        // 3. Формируем SQL-запрос десериализации на стороне сервера
        String sql = String.format(
                "INSERT INTO %s (line) " +
                "SELECT cast(t.item as varchar) " +
                "FROM unnest(cast(json_parse(?) as array(json))) as t(item)",
                tableName
        );

        // 4. Отправляем ровно одну команду в Trino
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, jsonArrayString);
            pstmt.executeUpdate();
        }

        long tableDuration = System.currentTimeMillis() - tableStartTime;
        System.out.printf("  ⚡ Успешно загружено! Строк: %d | Время: %d мс\n\n", allLines.size(), tableDuration);

        return allLines.size();
    }
}