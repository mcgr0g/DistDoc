package io.github.mcgr0g.distdoc.udaf.config;

import org.junit.jupiter.api.Test;
import java.util.List;
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
}
