package io.github.mcgr0g.distdoc.udaf.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class TraceSettingsTest {

    @Test
    public void resolvePresetEnvOverridesToml() {
        // env задан — полностью заменяет toml-пресет, порядок элементов сохраняется
        assertEquals(
                List.of("_id.$oid", "business_key"),
                TraceSettings.resolvePreset("_id.$oid,business_key", List.of("_id", "id")));
    }

    @Test
    public void resolvePresetTrimsAndSkipsEmpty() {
        // CSV: элементы тримятся, пустые отбрасываются (в т.ч. хвостовая запятая)
        assertEquals(List.of("a", "b"), TraceSettings.resolvePreset(" a, ,b,", List.of("x")));
    }

    @Test
    public void resolvePresetBlankFallsBack() {
        // отсутствующая/пустая переменная — значение из [trace] возвращается как есть
        List<String> fallback = List.of("_id", "id");
        assertSame(fallback, TraceSettings.resolvePreset(null, fallback));
        assertSame(fallback, TraceSettings.resolvePreset("  ", fallback));
    }

    @Test
    public void resolveSuffixOverridesAndFallsBack() {
        assertEquals("rating", TraceSettings.resolveSuffix("rating", "_id"));
        assertEquals("_id", TraceSettings.resolveSuffix(null, "_id"));
        assertEquals("_id", TraceSettings.resolveSuffix("  ", "_id"));
    }

    @Test
    public void tomlSectionMatchesDocumentedContract() {
        // Реальная секция [trace] без env (docs/testing/tracing.md, раздел «TOML-конфиг»)
        TraceSettings s = TraceSettings.from(AppConfig.section("trace"), k -> null);
        assertEquals(2, s.getMaxIds());
        assertEquals(128, s.getMaxIdLength());
        assertEquals(List.of("_id.$oid", "_id", "id", "uuid", "guid", "oid"), s.getPreset());
        assertEquals("$._id.$oid", s.getPresetPaths().get(0));
        assertEquals("_id", s.getSuffix());
    }

    @Test
    public void envOverridesPresetAndSuffixButNotLimits() {
        Map<String, String> env = Map.of(TraceSettings.ENV_PRESET, "doc_code", TraceSettings.ENV_SUFFIX, "_key");
        TraceSettings s = TraceSettings.from(AppConfig.section("trace"), env::get);
        assertEquals(List.of("doc_code"), s.getPreset());
        assertEquals("_key", s.getSuffix());
        assertEquals(2, s.getMaxIds(), "лимиты env не переопределяются");
    }
}
