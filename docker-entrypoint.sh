#!/bin/sh
# Prepara la carpeta de datos y arranca MercaStats sin privilegios.
#
# - Si el contenedor arranca como root (lo normal), crea /data y /data/inbox,
#   les da como dueño PUID:PGID (por defecto 1000:1000, el primer usuario de casi
#   cualquier Linux) y ejecuta el servidor con ese usuario. Así la carpeta data/
#   del equipo se crea sola y es tuya: puedes copiar PDFs a data/inbox sin sudo.
# - Si el contenedor ya arranca con otro usuario (`user:` en el compose), no toca
#   nada y arranca directamente.
set -e

DATA="${DATA_DIR:-/data}"
INBOX="${INBOX_DIR:-$DATA/inbox}"

if [ "$(id -u)" = "0" ]; then
    PUID="${PUID:-1000}"
    PGID="${PGID:-1000}"
    mkdir -p "$DATA" "$INBOX"
    # Solo cambia lo que aún no es suyo; -h para no seguir enlaces simbólicos.
    for dir in "$DATA" "$INBOX"; do
        find "$dir" -xdev \( ! -user "$PUID" -o ! -group "$PGID" \) -exec chown -h "$PUID:$PGID" {} +
    done
    exec su-exec "$PUID:$PGID" /app/bin/server "$@"
fi

mkdir -p "$DATA" "$INBOX" 2>/dev/null || true
exec /app/bin/server "$@"
