package io.github.mcgr0g.distdoc.udaf.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Настройки форматов: реальная секция {@code [format]} + управляемый источник env
 * ({@link FormatSettings#from}) — результат не зависит от окружения машины.
 */
public class FormatSettingsTest {

    private static FormatSettings withEnv(Map<String, String> env) {
        return FormatSettings.from(AppConfig.section("format"), env::get);
    }

    @Test
    public void tomlPresetMatchesDocumentedContract() {
        // Пресет зафиксирован в docs/contracts/value-formats.md: смена toml без смены документа — провал
        assertEquals(List.of("_at", "_utc"), withEnv(Map.of()).getPathHints());
    }

    @Test
    public void envReplacesPreset() {
        assertEquals(List.of("_ts", "Date"),
                withEnv(Map.of(FormatSettings.ENV_PATH_HINTS, "_ts, ,Date")).getPathHints(),
                "env полностью заменяет пресет; элементы тримятся, пустые отбрасываются");
    }

    @Test
    public void blankEnvFallsBackToPreset() {
        assertEquals(List.of("_at", "_utc"), withEnv(Map.of(FormatSettings.ENV_PATH_HINTS, "  ")).getPathHints());
    }

    @Test
    public void pathHintsAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class, () -> withEnv(Map.of()).getPathHints().add("_ts"));
    }

    @Test
    public void resolvePathHintsBlankFallsBack() {
        List<String> fallback = List.of("_at", "_utc");
        assertSame(fallback, FormatSettings.resolvePathHints(null, fallback));
        assertSame(fallback, FormatSettings.resolvePathHints("", fallback));
        assertSame(fallback, FormatSettings.resolvePathHints("  ", fallback));
    }
}
