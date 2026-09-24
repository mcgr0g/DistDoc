package io.github.mcgr0g.distdoc.udaf;

import io.github.mcgr0g.distdoc.udaf.config.TraceSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Ограниченная коллекция trace evidence одного scope: {@code path_trace}, одна аномалия
 * или один формат пути (docs/testing/tracing.md).
 *
 * <p>Хранит атомарные пары {@code {id, id_key}} — пара никогда не разрывается, поэтому
 * ложная связь id ↔ key после слияния воркеров невозможна.</p>
 *
 * <p><b>Алгебра:</b> коллекция — bounded min-set: сохраняются {@code max_ids} лексикографически
 * наименьших пар (сортировка по {@code id}, затем по {@code id_key}). Маркер «источник не
 * найден» хранится флагом и выводится как {@code {"id": "", "id_key": ""}} только при
 * свободном слоте. Добавление и {@link #merge(TraceEvidence)} поэтому ассоциативны,
 * коммутативны и идемпотентны: итог не зависит от порядка строк и слияния воркеров.</p>
 */
public final class TraceEvidence {

    /**
     * Атомарная пара трассировки.
     *
     * @param id    значение идентификатора строки-источника (непустое)
     * @param idKey имя поля-источника id (пресет/суффикс/explicit)
     */
    public record Pair(String id, String idKey) implements Comparable<Pair> {
        @Override
        public int compareTo(Pair o) {
            int c = id.compareTo(o.id);
            return c != 0 ? c : idKey.compareTo(o.idKey);
        }
    }

    /** Пара-маркер «источник не найден». */
    public static final Pair MARKER = new Pair("", "");

    private final TreeSet<Pair> pairs = new TreeSet<>();
    private boolean marker = false;

    /**
     * Добавляет пару строки-источника.
     *
     * <ul>
     *   <li>{@code id == null} — игнорируется;</li>
     *   <li>{@code id == ""} — фиксируется маркер (ключ не важен: маркер всегда {@code {"", ""}});</li>
     *   <li>непустой — усекается до {@link TraceSettings#getMaxIdLength()} и остаётся, только если
     *       входит в {@link TraceSettings#getMaxIds()} наименьших пар.</li>
     * </ul>
     *
     * @param id    идентификатор строки-источника
     * @param idKey имя поля-источника ({@code null} трактуется как {@code ""})
     */
    public void add(String id, String idKey) {
        if (id == null) {
            return;
        }
        if (id.isEmpty()) {
            marker = true;
            return;
        }
        TraceSettings settings = TraceSettings.getInstance();
        String truncated = id.length() > settings.getMaxIdLength() ? id.substring(0, settings.getMaxIdLength()) : id;
        Pair pair = new Pair(truncated, idKey == null ? "" : idKey);
        int maxIds = settings.getMaxIds();
        if (pairs.size() >= maxIds && pair.compareTo(pairs.last()) >= 0) {
            return; // коллекция заполнена меньшими парами
        }
        pairs.add(pair);
        if (pairs.size() > maxIds) {
            pairs.pollLast();
        }
    }

    /**
     * Сливает коллекцию того же scope с другого воркера: union пар с усечением до
     * {@code max_ids} наименьших, маркер — по OR.
     *
     * @param other коллекция с другого воркера (может быть {@code null})
     */
    public void merge(TraceEvidence other) {
        if (other == null) return;
        for (Pair p : other.pairs) {
            add(p.id(), p.idKey());
        }
        marker |= other.marker;
    }

    /** @return {@code true}, если нет ни пар, ни маркера (scope в отчёт не выводится) */
    public boolean isEmpty() {
        return pairs.isEmpty() && !marker;
    }

    /**
     * Пары для вывода в отчёт: непустые по возрастанию, затем маркер — если он был
     * зафиксирован и остался свободный слот.
     *
     * @return неизменяемый список пар
     */
    public List<Pair> items() {
        List<Pair> items = new ArrayList<>(pairs);
        if (marker && pairs.size() < TraceSettings.getInstance().getMaxIds()) {
            items.add(MARKER);
        }
        return Collections.unmodifiableList(items);
    }
}
