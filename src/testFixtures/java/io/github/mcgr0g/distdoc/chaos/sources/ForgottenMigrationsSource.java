package io.github.mcgr0g.distdoc.chaos.sources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosSource;
import net.datafaker.Faker;

/**
 * Источник данных из легаси-системы (например, CRM), где забыли о миграциях данных.
 * Эмулирует накопленный за годы полиморфизм и структурные аномалии в рамках одной таблицы.
 */
public class ForgottenMigrationsSource implements ChaosSource {
    // Написания created_at (docs/contracts/value-formats.md); базовая запись — LOCAL_DATETIME
    private static final String CREATED_AT_DATE_ONLY = "2026-07-23";
    private static final String CREATED_AT_OFFSET = "2026-07-23T01:15:00+03:00";
    private static final long CREATED_AT_UNIX_MILLIS = 1784769300000L; // 2026-07-23T01:15:00Z

    private final ObjectMapper mapper = new ObjectMapper();
    private final Faker faker = new Faker();

    @Override
    public String getName() {
        return "monster-crm";
    }

    @Override
    public ObjectNode generateBaseRecord(long index) {
        ObjectNode root = mapper.createObjectNode();

        // BSON-идентификатор документа (вложенный $oid)
        root.putObject("_id").put("$oid", String.format("60b8d29f1a4c8b%010d", index));

        // Чистый INTEGER; числовой подъём проверяется подмешиванием DOUBLE (RATING_PROMOTION, ALL)
        root.put("customer_rating", faker.number().numberBetween(1, 5));

        // Честный массив дат оплат
        ArrayNode dates = root.putArray("payment_dates");
        dates.add("2026-06-01").add("2026-06-02");

        // Дата рождения: чистая строка (частицы накладываются в DATE_AS_ARRAY/ALL)
        root.put("birth_date", "1990-05-15");

        // Регистрация без таймзоны (особенность продуктового бэкенда)
        root.put("created_at", "2026-07-23T01:15:00");

        // Регистрация с явным смещением UTC
        root.put("updated_at", "2026-07-23T01:15:00+03:00");

        // Чистая дата истечения акции (happy path)
        root.put("promo_expiry_date", "2026-08-01");

        // Экранированный под-документ (деэкранирование jsonstring)
        root.put("metadata_encoded", "{\"user_agent\": \"Mozilla\", \"retry_count\": 1}");

        // Спецсимвольные BSON/@-поля
        root.putObject("version").put("$numberLong", 7);
        ObjectNode docMeta = root.putObject("doc_meta");
        docMeta.put("@type", "deal");
        docMeta.put("@version", 2);

        return root;
    }

    /**
     * Мутации режима. Группы строк выбираются только по {@code index} (детерминированно):
     * ожидания каждой таблицы записаны в docs/testing/fixture-matrix.md (разделы 1, 2, 4).
     */
    @Override
    public ObjectNode applyChaos(ObjectNode baseRecord, AnomalyScenario scenario, long index) {
        switch (scenario) {
            case EMPTY_ARRAY -> emptyPaymentDates(baseRecord);
            case DATE_AS_ARRAY -> birthDateParts(baseRecord);
            case DATE_AT_UNIX -> baseRecord.put("created_at", CREATED_AT_UNIX_MILLIS);
            case DATE_AS_PLAIN -> baseRecord.put("created_at", CREATED_AT_DATE_ONLY);
            case RATING_PROMOTION -> {
                if (index % 2 == 1) doubleRating(baseRecord);
            }
            case POL_CREATED_AT_FORMATS -> createdAtByThree(baseRecord, index);
            case POL_CREATED_AT_WITH_ARRAYS -> {
                createdAtByThree(baseRecord, index);
                if (index % 2 == 1) birthDateParts(baseRecord);
                if (index % 5 == 0) emptyPaymentDates(baseRecord);
            }
            case POL_CREATED_AT_WITH_RATING -> {
                createdAtByThree(baseRecord, index);
                if (index % 2 == 1) doubleRating(baseRecord);
            }
            case TRACE_MIXED_SOURCES -> mixedIdSource(baseRecord, index);
            case ALL -> {
                // created_at: 0 local (базовая запись), 1 date-only, 2 unix millis, 3 offset
                switch ((int) (index % 4)) {
                    case 1 -> baseRecord.put("created_at", CREATED_AT_DATE_ONLY);
                    case 2 -> baseRecord.put("created_at", CREATED_AT_UNIX_MILLIS);
                    case 3 -> baseRecord.put("created_at", CREATED_AT_OFFSET);
                    default -> { }
                }
                if (index % 2 == 1) birthDateParts(baseRecord);
                if (index % 3 == 0) emptyPaymentDates(baseRecord);
                if (index % 5 < 2) doubleRating(baseRecord);
                if (index % 2 == 0) {
                    // Поле 1 уровня в половине строк — для явной трассировки trace('surrogate_pk')
                    baseRecord.put("surrogate_pk", String.format("sp-%010d", index));
                }
            }
            case CLEAN -> { } // Эталонный валидный документ без мутаций
        }
        return baseRecord;
    }

    /** created_at по i%3: 0 local (базовая запись), 1 date-only, 2 offset. */
    private static void createdAtByThree(ObjectNode record, long index) {
        if (index % 3 == 1) {
            record.put("created_at", CREATED_AT_DATE_ONLY);
        } else if (index % 3 == 2) {
            record.put("created_at", CREATED_AT_OFFSET);
        }
    }

    /**
     * Источник id по i%4: 0 {@code _id.$oid} (базовая запись), 1 {@code id}, 2 {@code order_id}
     * (suffix-fallback пресета), 3 без id. Префикс значения ({@code 60b8…}/{@code id-…}/{@code ord-…})
     * однозначно задаёт ожидаемый {@code id_key} — так тесты ловят ложную пару.
     */
    private static void mixedIdSource(ObjectNode record, long index) {
        int group = (int) (index % 4);
        if (group == 0) {
            return;
        }
        record.remove("_id");
        if (group == 1) {
            record.put("id", String.format("id-%010d", index));
        } else if (group == 2) {
            record.put("order_id", String.format("ord-%010d", index));
        }
    }

    private void birthDateParts(ObjectNode record) {
        // Разорванные частицы даты вместо строки
        record.set("birth_date", mapper.createArrayNode().add("1990").add("05").add("15"));
    }

    private static void emptyPaymentDates(ObjectNode record) {
        ((ArrayNode) record.get("payment_dates")).removeAll();
    }

    private void doubleRating(ObjectNode record) {
        // DOUBLE — числовой подъём типа INTEGER + DOUBLE -> DOUBLE (не аномалия)
        record.put("customer_rating", faker.number().randomDouble(1, 1, 5));
    }
}
