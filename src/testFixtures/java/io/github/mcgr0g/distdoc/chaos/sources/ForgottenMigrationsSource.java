package io.github.mcgr0g.distdoc.chaos.sources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mcgr0g.distdoc.chaos.AnomalyScenario;
import io.github.mcgr0g.distdoc.chaos.ChaosSource;
import net.datafaker.Faker;
import java.util.Random;

/**
 * Источник данных из легаси-системы (например, CRM), где забыли о миграциях данных.
 * Эмулирует накопленный за годы полиморфизм и структурные аномалии в рамках одной таблицы.
 */
public class ForgottenMigrationsSource implements ChaosSource {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Faker faker = new Faker();
    private final Random random = new Random();

    @Override
    public String getName() {
        return "monster-crm";
    }

    @Override
    public ObjectNode generateBaseRecord(long index) {
        ObjectNode root = mapper.createObjectNode();

        // BSON-идентификатор документа (вложенный $oid)
        root.putObject("_id").put("$oid", String.format("60b8d29f1a4c8b%010d", index));

        // Чистый INTEGER; числовой подъём проверяется подмешиванием DOUBLE в ALL
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

    @Override
    public ObjectNode applyChaos(ObjectNode baseRecord, AnomalyScenario scenario, long index) {
        ArrayNode dates = (ArrayNode) baseRecord.get("payment_dates");

        switch (scenario) {
            case EMPTY_ARRAY:
                dates.removeAll(); // Только пустые массивы
                break;

            case DATE_AS_ARRAY:
                // Разорванные частицы даты в birth_date (вместо строки)
                baseRecord.set("birth_date", mapper.createArrayNode().add("1990").add("05").add("15"));
                break;

            case DATE_AT_UNIX:
                // Unix-timestamp в миллисекундах: 2026-07-23T01:15:00Z
                baseRecord.put("created_at", 1784769300000L);
                break;

            case DATE_AS_PLAIN:
                // Обычная дата YYYY-MM-DD вместо полного timestamp
                baseRecord.put("created_at", "2026-07-23");
                break;

            case ALL:
                if (random.nextDouble() < 0.33) {
                    dates.removeAll(); // ~33% строк с пустыми массивами
                }
                if (random.nextDouble() < 0.5) {
                    // ~50% строк с частицами дат в birth_date
                    baseRecord.set("birth_date", mapper.createArrayNode().add("1990").add("05").add("15"));
                }
                if (random.nextDouble() < 0.5) {
                    // ~50% строк с DOUBLE — числовой подъём типа
                    baseRecord.put("customer_rating", faker.number().randomDouble(1, 1, 5));
                }
                if (random.nextDouble() < 0.33) {
                    // ~33% строк с обычной датой YYYY-MM-DD
                    baseRecord.put("created_at", "2026-07-23");
                }
                if (index % 2 == 0) {
                    // ~50% строк (чётные) — поле 1 уровня для явной трассировки trace('surrogate_pk')
                    baseRecord.put("surrogate_pk", "sp-" + String.format("%010d", index));
                }
                break;

            case CLEAN:
            default:
                // Эталонный валидный документ без мутаций
                break;
        }

        return baseRecord;
    }
}
