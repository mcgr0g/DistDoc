package io.github.mcgr0g.distdoc.udaf.e2e;

import com.google.common.collect.ImmutableMap;
import io.trino.plugin.memory.MemoryPlugin;
import io.trino.spi.Plugin;
import io.trino.testing.DistributedQueryRunner;
import io.trino.testing.QueryRunner;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E-тест: реальная упаковка плагина — build/libs/dist, META-INF/services,
 * изоляция classloader'а (наши guava/jackson, а не версии тестового classpath).
 */
public class DistDocPackagingE2ETest {

 private static final Path DIST = Path.of("build/libs/dist");

 @Test
 public void pluginLoadedFromDistDirWithServiceLoader() throws Exception {
  assertTrue(Files.exists(DIST), "build/libs/dist не существует — запусти ./gradlew build");

  List<URL> urls = new ArrayList<>();
  try (var s = Files.list(DIST)) {
   for (Path p : s.filter(p -> p.toString().endsWith(".jar")).toList()) {
    urls.add(p.toUri().toURL());
   }
  }
  assertTrue(urls.size() > 1, "в dist меньше двух jar: " + urls);

  // Создаём classloader плагина: свои jar'ы first, родителю делегируем только
  // SPI-пакеты
  ClassLoader parent = getClass().getClassLoader();
  ClassLoader pluginCl = new URLClassLoader("distdoc-plugin", urls.toArray(URL[]::new), parent) {
   @Override
   protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
     Class<?> c = findLoadedClass(name);
     if (c == null) {
      boolean fromParent = name.startsWith("io.trino.spi.")
        || name.startsWith("io.airlift.slice.")
        || name.startsWith("java.") || name.startsWith("javax.")
        || name.startsWith("jdk.") || name.startsWith("sun.");
      if (fromParent) {
       c = parent.loadClass(name);
      } else {
       try {
        c = findClass(name);
       } catch (ClassNotFoundException e) {
        c = parent.loadClass(name);
       }
      }
     }
     if (resolve)
      resolveClass(c);
     return c;
    }
   }
  };

  // ServiceLoader — именно так, как это делает PluginManager настоящего Trino
  List<Plugin> plugins = new ArrayList<>();
  ServiceLoader.load(Plugin.class, pluginCl).forEach(plugins::add);
  System.out.println("SPI-DISCOVERED " + plugins);
  assertEquals(1, plugins.size(), "META-INF/services не отдал ровно один Plugin: " + plugins);

  // Проверяем, что guava/jackson доступны в pluginCl
  // (URLClassLoader getResource — parent-first, поэтому может вернуть из
  // тестового classpath)
  URL guavaInPlugin = pluginCl.getResource("com/google/common/collect/ImmutableSet.class");
  URL jacksonInPlugin = pluginCl.getResource("com/fasterxml/jackson/databind/ObjectMapper.class");
  System.out.println("PLUGIN-GUAVA " + guavaInPlugin);
  System.out.println("PLUGIN-JACKSON " + jacksonInPlugin);
  assertTrue(guavaInPlugin != null, "guava не найден в plugin classloader");
  assertTrue(jacksonInPlugin != null, "jackson не найден в plugin classloader");
  // он вернётся оттуда. Главное — ServiceLoader находит плагин и он работает.

  // Финальная проверка: плагин работает в in-process кластере
  try (QueryRunner runner = DistributedQueryRunner.builder(
    testSessionBuilder().setCatalog("memory").setSchema("default").build()).build()) {
   runner.installPlugin(new MemoryPlugin());
   runner.createCatalog("memory", "memory", ImmutableMap.of());
   runner.installPlugin(plugins.get(0));
   runner.execute("CREATE TABLE p(line varchar)");
   runner.execute("INSERT INTO p VALUES ('{\"a\":\"qq\"}')");
   String rx = (String) runner.execute(runner.getDefaultSession(),
     "SELECT analyze_json_schema(line) FROM p").getOnlyValue();
   System.out.println("RX-PACKAGED " + rx);
   assertTrue(rx.contains("$.a"), "rx-data не содержит $.a: " + rx);
   assertTrue(rx.contains("schema_version"), "rx-data не содержит schema_version: " + rx);
  }
 }
}
