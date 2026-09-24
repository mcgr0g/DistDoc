package io.github.mcgr0g.distdoc.udaf.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class FormatSettingsTest {

    @Test
    public void tomlPresetLoaded() {
        // На машине с заданным env пресет переопределён — проверка toml не имеет смысла
        String env = System.getenv("DISTDOC_FORMAT_PATH_HINTS");
        assumeTrue(env == null || env.isBlank(), "DISTDOC_FORMAT_PATH_HINTS задан в окружении");
        assertEquals(List.of("_at", "_utc"), FormatSettings.getInstance().getPathHints());
    }

    @Test
    public void pathHintsAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> FormatSettings.getInstance().getPathHints().add("_ts"));
    }

    @Test
    public void resolvePathHintsEnvOverridesToml() {
        // env задан — полностью заменяет пресет; элементы тримятся, пустые отбрасываются
        assertEquals(List.of("_ts", "Date"), FormatSettings.resolvePathHints("_ts, ,Date", List.of("_at", "_utc")));
    }

    @Test
    public void resolvePathHintsBlankFallsBack() {
        List<String> fallback = List.of("_at", "_utc");
        assertSame(fallback, FormatSettings.resolvePathHints(null, fallback));
        assertSame(fallback, FormatSettings.resolvePathHints("", fallback));
        assertSame(fallback, FormatSettings.resolvePathHints("  ", fallback));
    }
}
