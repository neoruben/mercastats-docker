# MercaStats (Docker)

Estadísticas de tus tickets de Mercadona en PDF, en tu propio servidor y desde el navegador del ordenador o del móvil.

> MercaStats es un proyecto personal independiente. **No está afiliado, asociado ni respaldado por Mercadona, S.A.**

## Qué hace

- **Tickets:** tus compras agrupadas por mes, con buscador y el detalle de cada ticket (productos e IVA).
- **Estadísticas:** gasto total, ticket medio, IVA y evolución por meses o semanas.
- **Productos:** los más comprados y los de mayor importe. Al pulsar uno ves la evolución de su precio.
- **Importación automática:** lee los tickets de tu correo, de una carpeta o de los PDFs que subas desde la web. Sin duplicados.
- **Varios usuarios**, con inicio de sesión propio o detrás de authentik/Authelia.
- **Pensada también para el móvil.**
- Funciona en PC, NAS y Raspberry Pi (`amd64` y `arm64`). Todos los datos en un único archivo.

---

## Instalación

Necesitas Docker con Docker Compose. Este es el `docker-compose.yml`:

```yaml
services:
  mercastats:
    image: ghcr.io/neoruben/mercastats:latest
    container_name: mercastats
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      - TZ=Europe/Madrid
      # Contraseña del usuario admin. Si no la pones, se genera una (ver más abajo).
      # - ADMIN_PASSWORD=una-contraseña-larga

      # Leer los tickets del correo (opcional, ver «Importar tickets del correo»)
      # - IMAP_HOST=imap.gmail.com
      # - IMAP_USER=tu_correo@gmail.com
      # - IMAP_PASSWORD=xxxx xxxx xxxx xxxx
      # - IMAP_FOLDER=Tickets/Mercadona
    volumes:
      - ./data:/data
```

Arráncalo en la misma carpeta:

```bash
docker compose up -d
```

La carpeta `data` se crea sola junto al compose. Ahí se guarda la base de datos, y dentro está `data/inbox`, la carpeta vigilada de la que se importan los PDFs.

Abre `http://IP-del-equipo:8080` y entra con el usuario **`admin`**. Si no pusiste `ADMIN_PASSWORD`, la contraseña generada aparece en los logs del primer arranque:

```bash
docker compose logs mercastats | grep Contraseña
```

Después puedes cambiarla desde la web, en **Cambiar contraseña**.

### Opciones útiles

Se añaden en `environment:`, salvo el puerto.

| Opción | Para qué |
|---|---|
| `ports: - "8070:8080"` | Abrir la web en otro puerto (el primer número) |
| `PUID=1000` y `PGID=1000` | Usuario y grupo dueños de la carpeta `data` (los tuyos se ven con el comando `id`). Por defecto, 1000 |
| `JAVA_OPTS=-Xmx256m -XX:+UseSerialGC` | Gastar menos memoria; recomendado en Raspberry Pi |
| `IMAP_SYNC_INTERVAL_MINUTES=60` | Cada cuánto se mira el correo (`0` = solo al pulsar Sincronizar) |
| `ALLOW_REGISTRATION=true` | Que otras personas puedan crearse una cuenta desde la pantalla de inicio |

La lista completa de opciones está en [docs/DOCKER.md](docs/DOCKER.md#5-variables-de-entorno). Si prefieres no poner las contraseñas en el compose, puedes ponerlas en un archivo `.env` y añadir `env_file: .env` (hay una plantilla en [`.env.example`](.env.example)).

---

## Importar tickets

- **Desde el correo:** rellena las líneas `IMAP_...` del compose y reinicia el contenedor con `docker compose up -d`. La primera sincronización empieza sola y los tickets van apareciendo en la web; con unos 200 tickets tarda unos minutos. Después se repite cada hora, y también al pulsar **Sincronizar**.
  - `IMAP_PASSWORD` es una **contraseña de aplicación**, no la de tu cuenta. Cómo crearla en Gmail o iCloud: [docs/DOCKER.md](docs/DOCKER.md#6-cómo-crear-una-contraseña-de-aplicación-imap).
  - `IMAP_FOLDER` es la carpeta o etiqueta de Gmail donde guardas los tickets. Si está dentro de otra, se escribe con `/`. En Gmail, la etiqueta tiene que tener activado «Mostrar en IMAP». Para buscar en todo el correo usa `[Gmail]/Todos`, o `[Gmail]/All Mail` si tienes Gmail en inglés.
- **Desde la web:** pulsa **Importar** y arrastra los PDFs, o elígelos desde el móvil. Se cargan solos.
- **Desde una carpeta:** copia los PDFs en `data/inbox` y en menos de un minuto aparecen en la web.

---

## Mantenimiento

**Actualizar** (los datos se conservan):

```bash
docker compose pull
docker compose up -d
```

**Copia de seguridad:** todo está en la carpeta `data`. Para el contenedor (`docker compose stop`), copia la carpeta y vuelve a arrancarlo (`docker compose start`).

**Problemas frecuentes:**

| Síntoma | Solución |
|---|---|
| `port is already allocated` | Ese puerto ya lo usa otro programa: cambia el primer número de `ports` |
| No sé la contraseña de `admin` | Si no pusiste `ADMIN_PASSWORD`, búscala en los logs (ver «Instalación») |
| He cambiado `ADMIN_PASSWORD` y no entra con la nueva | `ADMIN_PASSWORD` solo se usa la primera vez; después la contraseña se cambia desde la web |
| No importa nada del correo | Revisa `IMAP_FOLDER` y que la etiqueta tenga activado «Mostrar en IMAP», y mira `docker compose logs mercastats` |
| No puedo copiar PDFs en `data/inbox` | Pon en `PUID` y `PGID` tu usuario y grupo (comando `id`) y reinicia |
| Se cierra solo en la Raspberry Pi | Añade `JAVA_OPTS=-Xmx256m -XX:+UseSerialGC` |

---

## Más información

- [docs/DOCKER.md](docs/DOCKER.md): todas las opciones, correo (Gmail e iCloud), uso detrás de authentik y API.
- [docs/DESARROLLO.md](docs/DESARROLLO.md): para quien quiera modificar el código.

## Licencia

Consulta el archivo [LICENSE](LICENSE).
