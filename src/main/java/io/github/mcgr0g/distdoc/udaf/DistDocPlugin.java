package io.github.mcgr0g.distdoc.udaf;

import io.trino.spi.Plugin;
import java.util.Set;
import com.google.common.collect.ImmutableSet;

public class DistDocPlugin implements Plugin {
    @Override
    public Set<Class<?>> getFunctions() {
        return ImmutableSet.<Class<?>>builder()
                .add(JsonSchemaAggregation.class)          // UDAF: analyze_json_schema(line)
                .add(JsonSchemaTraceAggregation.class)     // UDAF-перегрузка: analyze_json_schema(line, trace(...))
                .add(TraceFunctions.class)                 // scalar-обёртки trace() / trace(v)
                .build();
    }
}
