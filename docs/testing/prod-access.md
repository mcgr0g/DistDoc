# Подключение к прод-кластеру (ShadowDoc)

Прод-интроспекция выполняется через теневой кластер **ShadowDoc** без установки плагина
на прод. Механизм построен на стороннем коннекторе **kkd927/trino2trino** (Apache-2.0):
работоспособность не гарантируется, механизм на стадии тестирования.

## Технология
- shadow-Trino (контейнер `shadowdoc-trino-local`) + плагин DistDoc из `build/libs/dist`;
- прод подключается как read-only каталог `remote` (файл `catalog/remote.properties`),
  удалённый каталог прода задаётся последним слогом `REMOTE_TRINO_URL`;
- UDAF `analyze_json_schema` выполняется локально на shadow; на прод ничего не ставится
  и ничего не пишется;
- аутентификация пробрасывается параметрами JDBC-драйвера (`REMOTE_PASSWORD` / `accessToken`
  в `REMOTE_TRINO_URL`).

## Ссылки
- стенд и порядок работы — docs/testing/local-cluster.md (раздел 5, ShadowDoc);
- архитектурное решение — docs/adr/0004-trace-and-shadowdoc.md.
