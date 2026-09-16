package io.github.mcgr0g.distdoc.chaos.apps;

import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosDataGenerator;
import io.github.mcgr0g.distdoc.chaos.ChaosSource;
import io.github.mcgr0g.distdoc.chaos.SourceRegistry;
import io.github.mcgr0g.distdoc.chaos.config.FixtureConfigLoader;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Автоматизированная точка входа для циклической генерации хаос-данных.
 * Читает декларативное описание сценариев из fixtures.toml и генерирует файлы в пакетном режиме.
 */
public class ChaosGeneratorApp {
    public static void main(String[] args) {
        System.out.println("===============================================================");
        System.out.println("🚀 Запуск циклического генератора фикстур хаоса (Mise-ориентированный)");
        System.out.println("===============================================================");

        try {
            // 1. Загружаем единый TOML-конфиг из ресурсов JAR
            TomlTable config = FixtureConfigLoader.loadTableConfig();
            TomlArray scenarios = config.getArray("scenarios");

            if (scenarios == null || scenarios.isEmpty()) {
                System.out.println("⚠️ Предупреждение: В fixtures.toml не найдено ни одного сценария в секции [[scenarios]].");
                System.exit(0);
            }

            long totalLinesGenerated = 0;
            long startTime = System.currentTimeMillis();

            // По умолчанию используем ваш "monster-crm", но при желании имя источника можно вынести в конфиг
            String sourceName = "monster-crm";
            ChaosSource source = SourceRegistry.getSource(sourceName)
                    .orElseThrow(() -> new IllegalArgumentException("Бизнес-источник не найден в реестре: " + sourceName));

            // 2. Циклический перебор всех конфигураций из TOML
            for (int i = 0; i < scenarios.size(); i++) {
                TomlTable scenarioConfig = scenarios.getTable(i);

                String tableName = scenarioConfig.getString("table");
                long count = scenarioConfig.getLong("count");
                String scenarioArg = scenarioConfig.getString("type").toUpperCase();
                String outputPath = scenarioConfig.getString("file");

                System.out.printf("\n[Сценарий %d/%d] Обработка таблицы: %s\n", i + 1, scenarios.size(), tableName);
                System.out.printf("  ├─ Сценарий аномалий: %s\n", scenarioArg);
                System.out.printf("  ├─ Целевой путь:      %s\n", outputPath);
                System.out.printf("  └─ Количество строк:  %d\n", count);

                // Валидируем сценарий
                AnomalyScenario scenario;
                try {
                    scenario = AnomalyScenario.valueOf(scenarioArg);
                } catch (IllegalArgumentException e) {
                    String allowed = Arrays.stream(AnomalyScenario.values())
                            .map(v -> v.name().toLowerCase(Locale.ROOT))
                            .collect(Collectors.joining(", "));
                    throw new IllegalArgumentException("Неизвестный сценарий аномалий в TOML: " + scenarioArg +
                            ". Допустимые значения в Enum: " + allowed);
                }

                // 3. Пакетная генерация строк в памяти
                long scenarioStartTime = System.currentTimeMillis();
                List<String> lines = new ArrayList<>();
                for (int j = 0; j < count; j++) {
                    lines.add(ChaosDataGenerator.generateSingleLine(source, scenario, j));
                }

                // 4. Безопасная запись на жесткий диск ноутбука (в build/dev-lakehouse/data/)
                Files.createDirectories(Paths.get(outputPath).getParent());
                Files.write(Paths.get(outputPath), lines);

                long scenarioDuration = System.currentTimeMillis() - scenarioStartTime;
                totalLinesGenerated += count;

                System.out.printf("  ⚡ Успешно сгенерировано! Время выполнения: %d мс\n", scenarioDuration);
            }

            long totalDuration = System.currentTimeMillis() - startTime;
            System.out.println("\n===============================================================");
            System.out.printf("🎉 Все сценарии успешно выполнены!\n");
            System.out.printf("📊 Всего сгенерировано строк: %d\n", totalLinesGenerated);
            System.out.printf("⏱️ Общее время работы: %d мс\n", totalDuration);
            System.out.println("===============================================================");

        } catch (Exception e) {
            System.err.println("\n❌ Критическая ошибка при автоматической генерации файлов данных:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}