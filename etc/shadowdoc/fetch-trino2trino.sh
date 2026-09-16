#!/usr/bin/env bash
# Скачивает релиз коннектора kkd927/trino2trino под Trino ${VERSION} и раскладывает
# в etc/shadowdoc/plugin/trino2trino (монтируется в контейнер как каталог `trino`).
# Контрольная сумма НЕ проверяется: целостность обеспечивают HTTPS к GitHub и пин точного
# тега релиза (trino-481-r2). Чексуммы появятся в рамках будущей reliability matrix.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=481
TAG=trino-481-r2
ZIP=/tmp/trino-trino-${VERSION}.zip

curl -fL -o "$ZIP" "https://github.com/kkd927/trino2trino/releases/download/${TAG}/trino-trino-${VERSION}.zip"
rm -rf plugin/trino2trino && mkdir -p plugin/trino2trino
unzip -q "$ZIP" -d plugin/trino2trino
# Релизный zip кладёт jar-файлы в поддиректорию trino-trino-481/ — Trino ожидает
# jar на верхнем уровне каталога плагина, поэтому поднимаем содержимое на уровень
rm -rf plugin/trino2trino/trino-trino-481/META-INF
mv plugin/trino2trino/trino-trino-481/* plugin/trino2trino/
rmdir plugin/trino2trino/trino-trino-481

echo "OK: etc/shadowdoc/plugin/trino2trino"
