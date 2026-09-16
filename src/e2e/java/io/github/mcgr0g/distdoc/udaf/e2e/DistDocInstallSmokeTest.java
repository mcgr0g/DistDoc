package io.github.mcgr0g.distdoc.udaf.e2e;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Install-smoke тест: реальный образ trinodb/trino через Testcontainers.
 *
 * <p>
 * Образ уже содержит memory-каталог, catalog.management=static.
 * </p>
 */
@Tag("install-smoke")
@Testcontainers
public class DistDocInstallSmokeTest {

 private static final String TRINO_VERSION = System.getProperty("trinoVersion", "483");
 private static final Path DIST = Path.of("build/libs/dist");

 @Container
 private static final GenericContainer<?> trino = new GenericContainer<>("trinodb/trino:" + TRINO_VERSION)
   .withExposedPorts(8080)
   .withFileSystemBind(DIST.toAbsolutePath().toString(), "/usr/lib/trino/plugin/distdoc", BindMode.READ_ONLY)
   .waitingFor(Wait.forHttp("/v1/info")
     .forPort(8080)
     .forResponsePredicate(response -> response.contains("\"starting\":false"))
     .withStartupTimeout(Duration.ofMinutes(3)))
   .withEnv("DISTDOC_TRACE_PRESET", "");

 @Test
 public void pluginLoadsInRealTrinoImage() throws Exception {
  assertTrue(Files.exists(DIST), "build/libs/dist не существует");

  String jdbcUrl = "jdbc:trino://" + trino.getHost() + ":" + trino.getMappedPort(8080) + "/memory/default";
  System.out.println("JDBC-URL: " + jdbcUrl);

  try (Connection conn = DriverManager.getConnection(jdbcUrl, "admin", "");
    Statement stmt = conn.createStatement()) {

   // Образ уже содержит memory-каталог — не создаём
   stmt.execute("CREATE TABLE t(line varchar)");
   stmt.execute("INSERT INTO t VALUES ('{\"x\":1}'), ('{\"x\":2}')");

   // Запускаем UDAF
   try (ResultSet rs = stmt.executeQuery("SELECT analyze_json_schema(line) FROM t")) {
    assertTrue(rs.next(), "UDAF не вернул результат");
    String rx = rs.getString(1);
    System.out.println("RX-SMOKE: " + rx);

    assertTrue(rx.contains("schema_version"), "rx-data не содержит schema_version: " + rx);
    assertTrue(rx.contains("$.x"), "rx-data не содержит $.x: " + rx);
    assertTrue(rx.contains("\"type\":\"INTEGER\""), "тип $.x должен быть INTEGER: " + rx);
   }
  }
 }
}
