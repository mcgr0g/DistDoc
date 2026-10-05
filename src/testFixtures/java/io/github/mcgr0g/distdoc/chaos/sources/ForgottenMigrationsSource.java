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
    // JSON-строки (obj_json_*): экранированный JSON внутри строкового значения
    private static final String JSON_OBJECT_TEXT = "{\"u\":\"a\"}";
    private static final String JSON_ARRAY_TEXT = "[{\"k\":1}]";
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
            case OBJ_JSON_OBJECT_OR_ARRAY -> baseRecord.put("meta", index % 2 == 0 ? JSON_OBJECT_TEXT : JSON_ARRAY_TEXT);
            case OBJ_JSON_ARRAY_ELEMENTS -> baseRecord.putArray("list").add("{\"a\":1}").add("{\"a\":2}");
            case OBJ_JSON_WITH_PLAIN -> baseRecord.put("meta", jsonOrPlain(index));
            case OBJ_JSON_WITH_NATIVE_OBJECT -> {
                if (index % 2 == 0) {
                    baseRecord.putObject("meta").put("u", "a");
                } else {
                    baseRecord.put("meta", JSON_OBJECT_TEXT);
                }
            }
            case OBJ_JSON_FALSE_ALARM -> baseRecord.put("t", index % 2 == 0 ? "[TEST]" : "{abc}");
            case OBJ_BSON_ID_FORMS -> {
                if (index % 2 == 1) {
                    // Та же идентичность, но строкой: рядом с обёрткой {"$oid":…} в других строках
                    baseRecord.put("_id", baseRecord.get("_id").get("$oid").asText());
                }
            }
            case OBJ_NESTED_PLAIN -> nestedProfile(baseRecord);
            case OBJ_ARRAY_OF_OBJECTS -> itemsOfObjects(baseRecord);
            case OBJ_OBJECT_OR_ARRAY -> party(baseRecord, index, false);
            case OBJ_OBJECT_OR_SCALAR -> contact(baseRecord, index);
            case OBJ_OBJECT_OR_ARRAY_FORMATS -> party(baseRecord, index, true);
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
                counterparties(baseRecord, index);
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

    /** meta по i%4: чётные — JSON-строка, 1 — пустая строка, 3 — обычный текст. */
    private static String jsonOrPlain(long index) {
        if (index % 2 == 0) return JSON_OBJECT_TEXT;
        return index % 4 == 1 ? "" : "n/a";
    }

    /** profile: объекты двух уровней и пустой объект (узлы OBJECT без потомков). */
    private void nestedProfile(ObjectNode record) {
        ObjectNode profile = record.putObject("profile");
        ObjectNode contacts = profile.putObject("contacts");
        contacts.put("email", "ops@example.org");
        contacts.put("phone", "+7-000-000-00-00");
        profile.putObject("flags");
    }

    /** items: массив объектов; элемент содержит вложенный объект dims (у самого элемента узла OBJECT нет). */
    private void itemsOfObjects(ObjectNode record) {
        ArrayNode items = record.putArray("items");
        for (int n = 1; n <= 2; n++) {
            ObjectNode item = items.addObject();
            item.put("sku", "sku-" + n);
            item.put("qty", n + 1);
            item.putObject("dims").put("w", n).put("h", n + 1);
        }
    }

    /**
     * party по i%2: чётные — объект {name, role}, нечётные — массив объектов [{id, share}] из 1–2 элементов.
     * {@code withSignedAt}: signed_at другого написания в каждой ветке (LOCAL_DATETIME у объекта, DATE_ONLY у элементов).
     */
    private void party(ObjectNode record, long index, boolean withSignedAt) {
        if (index % 2 == 0) {
            ObjectNode party = record.putObject("party");
            party.put("name", "Acme");
            party.put("role", "buyer");
            if (withSignedAt) party.put("signed_at", "2026-07-23T01:15:00");
        } else {
            ArrayNode party = record.putArray("party");
            int size = 1 + (int) ((index / 2) % 2);
            for (int n = 1; n <= size; n++) {
                ObjectNode element = party.addObject();
                element.put("id", "p-" + n);
                element.put("share", 50);
                if (withSignedAt) element.put("signed_at", CREATED_AT_DATE_ONLY);
            }
        }
    }

    /** contact по i%2: чётные — объект {email}, нечётные — строка "n/a" (смешение объекта со скаляром). */
    private static void contact(ObjectNode record, long index) {
        if (index % 2 == 0) {
            record.putObject("contact").put("email", "ops@example.org");
        } else {
            record.put("contact", "n/a");
        }
    }

    /** counterparties (режим all) по i%3: 0 — массив объектов [{share}], иначе объект {name}. */
    private void counterparties(ObjectNode record, long index) {
        if (index % 3 == 0) {
            ArrayNode list = record.putArray("counterparties");
            list.addObject().put("share", 100);
            list.addObject().put("share", 50);
        } else {
            record.putObject("counterparties").put("name", "Acme");
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
