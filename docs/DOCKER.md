# MercaStats Self-Hosted (Docker)

> **Aviso importante:** MercaStats es un proyecto independiente desarrollado con fines personales y educativos. **No está afiliado, asociado, autorizado ni respaldado por Mercadona, S.A.**

Esta guía explica cómo desplegar la versión web self-hosted de **MercaStats** con Docker para consultar tus tickets PDF, estadísticas mensuales/semanales y ranking de productos desde cualquier navegador, con soporte multiusuario, carpeta vigilada, sincronización por IMAP y autenticación local o mediante *forward auth* (authentik / Authelia).

---

## 1. Requisitos previos

- **Docker** y **Docker Compose** (v2+).
- Arquitectura compatible: `linux/amd64` o `linux/arm64` (Raspberry Pi 4/5, servidores ARM, NAS, VPS x86_64).

---

## 2. Instalación rápida con Docker Compose

1. Crea una carpeta para la instancia y descarga `docker-compose.example.yml` y `.env.example` (o clona el repositorio):

   ```bash
   mkdir -p mercastats/data/inbox
   cd mercastats
   cp docker-compose.example.yml docker-compose.yml
   cp .env.example .env
   ```

2. Ajusta los permisos de `./data` para el usuario no-root del contenedor (UID/GID `10001`):

   ```bash
   sudo chown -R 10001:10001 ./data
   ```

   Si prefieres que la carpeta siga siendo tuya (para copiar PDFs a `data/inbox` sin `sudo`), no cambies los permisos y añade en `docker-compose.yml` la línea `user: "1000:1000"` (tu UID y GID; los ves con el comando `id`).

3. Edita `.env` y establece al menos `ADMIN_PASSWORD` (y opcionalmente los datos de IMAP o proxy).

4. Arranca el contenedor:

   ```bash
   docker compose up -d
   ```

5. Abre `http://localhost:8080` e inicia sesión con `ADMIN_USER` y `ADMIN_PASSWORD`.

### Compilar la imagen localmente

Si prefieres construir la imagen desde el código fuente en lugar de descargarla de `ghcr.io`:

```bash
docker build -t mercastats:local .
```

O descomenta `build: .` en `docker-compose.yml` y ejecuta `docker compose up -d --build`.

---

## 3. Cómo importar tickets

MercaStats ofrece 4 formas compatibles entre sí. Todas evitan duplicados automáticamente comparando el **número de factura simplificada**, la **huella SHA-256 del PDF** y el **identificador del correo**, así que puedes repetir una importación sin miedo.

1. **Subida web:** en la pestaña **Tickets**, pulsa **Importar** y elige uno o varios PDFs. En el ordenador también puedes arrastrar los PDFs a cualquier parte de la página. Se importan al momento, sin pulsar nada más.
2. **Carpeta vigilada (`/data/inbox`):**
   - Cualquier `.pdf` que copies en `./data/inbox/` se importa solo (cada `INBOX_POLL_SECONDS` segundos) para el usuario indicado en `INBOX_DEFAULT_USER`.
   - En instancias multiusuario, puedes crear subcarpetas `./data/inbox/<usuario>/` y los PDFs depositados ahí se importarán directamente a la cuenta de `<usuario>`.
   - Tras procesarse, los archivos se mueven a `processed/` (o a `failed/` con un `.error.txt` si no eran un ticket válido).
   - Si `INBOX_DEFAULT_USER` no existe, los PDFs de la raíz se dejan sin importar (nunca se asignan a otro usuario).
3. **Correo por IMAP (recomendado):** lee los correos de `ticket_digital@mail.mercadona.com` con una contraseña de aplicación y descarga sus PDFs. Se sincroniza solo cada `IMAP_SYNC_INTERVAL_MINUTES` minutos y al arrancar el contenedor. Solo lee: no borra ni mueve correos.
4. **Gmail OAuth 2.0 (opcional):** con tu propio `Client ID` y `Client Secret` de Google Cloud (permiso de solo lectura `gmail.readonly`).

El botón **Sincronizar** del panel **Importar** (y el botón ⟳ junto a él) lanza de una vez todo lo disponible para tu usuario: la carpeta vigilada, Gmail y el correo IMAP. Mientras dura, una barra bajo la cabecera muestra el avance («Importando tickets: 120 de 230») desde cualquier pestaña, y la lista se actualiza sola.

---

## 4. Uso de la web

- **Tickets:** compras agrupadas por mes, con buscador. Pulsa un ticket para ver sus productos y el desglose de IVA, o para eliminarlo. Arriba, una línea indica cuándo se sincronizó el correo por última vez (punto verde) o si hubo un error (punto rojo); al pulsarla se abre el detalle.
- **Estadísticas:** gasto total, ticket medio, IVA y número de compras, con la evolución por meses o por semanas.
- **Productos:** ranking por «Más comprados» o «Mayor importe». Al pulsar un producto se abre su **evolución de precio**: último precio, variación desde la primera compra, mínimo, máximo, una gráfica en escalones y la lista de compras. Los productos al peso se muestran en €/kg.
- **En el móvil:** la navegación pasa a una barra inferior y las ventanas suben desde abajo. La web está pensada para usarse igual de bien en el móvil que en el ordenador.
- **Actualización automática:** con la página abierta, cada 30 segundos se comprueba si el servidor ha importado tickets nuevos y, si es así, se recargan los datos.

---

## 5. Variables de entorno

### Servidor y almacenamiento

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `PORT` | `8080` | Puerto HTTP interno del servidor Ktor. |
| `HOST` | `0.0.0.0` | Interfaz de escucha interna. |
| `TZ` | `Europe/Madrid` | Zona horaria del contenedor. |
| `LOG_LEVEL` | `INFO` | Nivel de logs (`DEBUG`, `INFO`, `WARN`, `ERROR`). |
| `BASE_URL` | `http://localhost:8080` | URL pública base (usada como prefijo por defecto de `GMAIL_REDIRECT_URI`). |
| `DATA_DIR` | `/data` | Directorio persistente montado como volumen. |
| `DB_PATH` | `/data/mercastats.db` | Ruta de la base de datos SQLite. |
| `INBOX_DIR` | `/data/inbox` | Carpeta vigilada para importación automática de PDFs. |
| `INBOX_POLL_SECONDS` | `30` | Frecuencia de escaneo de `INBOX_DIR` en segundos (`0` desactiva la vigilancia). |
| `INBOX_DEFAULT_USER` | `ADMIN_USER` (`admin`) | Usuario que recibe los PDFs colocados en la raíz de `INBOX_DIR`. |

### Autenticación (`AUTH_MODE`)

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `AUTH_MODE` | `local` | Modo de autenticación: `local`, `proxy` o `none`. |
| `ADMIN_USER` | `admin` | Nombre del usuario administrador inicial (y usuario por defecto en `AUTH_MODE=none`). |
| `ADMIN_PASSWORD` | *(vacío)* | Contraseña inicial del administrador en `AUTH_MODE=local`. Si se omite al crear la BD, se genera una contraseña aleatoria y se muestra en los logs. |
| `ALLOW_REGISTRATION` | `false` | Permite crear cuentas nuevas desde la pantalla de login en modo `local`. Los administradores también pueden crear usuarios desde el modal de cuenta. |
| `COOKIE_SECURE` | `false` | Marca la cookie de sesión con el atributo `Secure`. Ponlo a `true` si sirves MercaStats por HTTPS. |
| `SESSION_TTL_DAYS` | `30` | Duración en días de las sesiones locales (almacenadas en SQLite). |
| `PROXY_USER_HEADER` | `X-authentik-username` | Cabecera (una sola) de la que leer el usuario en `AUTH_MODE=proxy`. Tu proxy debe sobrescribirla siempre, también cuando el cliente la envía. |
| `PROXY_EMAIL_HEADER` | `X-authentik-email` | Cabecera opcional con el correo del usuario en `AUTH_MODE=proxy`. |
| `PROXY_NAME_HEADER` | `X-authentik-name` | Cabecera opcional con el nombre visible en `AUTH_MODE=proxy`. |
| `TRUSTED_PROXIES` | `127.0.0.1,::1` | IPs (o rangos CIDR) permitidos para enviar cabeceras de usuario en `AUTH_MODE=proxy`. Cualquier petición desde otra IP TCP recibirá `403 Forbidden`. Una entrada mal escrita impide arrancar. |

### Sincronización IMAP

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `IMAP_HOST` | *(vacío)* | Servidor IMAP (ej. `imap.gmail.com`, `imap.mail.me.com`). |
| `IMAP_PORT` | `993` / `143` | Puerto IMAP. Por defecto `993` con `IMAP_SSL=true` y `143` con `IMAP_SSL=false`. |
| `IMAP_SSL` | `true` | `true` usa IMAPS (TLS directo); `false` usa STARTTLS obligatorio (si el servidor no lo ofrece, la conexión se aborta antes de enviar la contraseña). |
| `IMAP_USER` | *(vacío)* | Dirección de correo / usuario IMAP. |
| `IMAP_PASSWORD` | *(vacío)* | Contraseña de aplicación del proveedor de correo. |
| `IMAP_FOLDER` | `INBOX` | Carpeta a inspeccionar. En Gmail, cada etiqueta es una carpeta: una etiqueta anidada se escribe con `/` (por ejemplo `Tickets/Mercadona`). Para buscar en todo el correo, `[Gmail]/Todos` (o `[Gmail]/All Mail` si Gmail está en inglés). |
| `IMAP_FROM_FILTER` | `ticket_digital@mail.mercadona.com` | Remitente buscado en el buzón. |
| `IMAP_SYNC_INTERVAL_MINUTES` | `60` | Intervalo de sincronización automática en minutos (`0` = solo bajo demanda desde la web). |
| `IMAP_TARGET_USER` | `ADMIN_USER` | Usuario de MercaStats al que se asignarán los tickets descargados por IMAP. Si no existe, la sincronización se detiene con un error (nunca se asignan a otro usuario). Solo ese usuario y los administradores pueden lanzarla desde la web. |

### Sincronización Gmail OAuth 2.0 (opcional)

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `GMAIL_CLIENT_ID` | *(vacío)* | Client ID OAuth 2.0 de tipo **Aplicación web** en Google Cloud. |
| `GMAIL_CLIENT_SECRET` | *(vacío)* | Client Secret OAuth 2.0 de tu proyecto de Google Cloud. |
| `GMAIL_REDIRECT_URI` | `${BASE_URL}/api/gmail/oauth/callback` | URI de redirección autorizada configurada en Google Cloud Console. |
| `GMAIL_SYNC_INTERVAL_MINUTES` | `60` | Intervalo en minutos para sincronizar automáticamente cuentas conectadas (`0` = solo manual). |

### Memoria (opcional, recomendado en Raspberry Pi)

El servidor es una aplicación Java. En equipos con poca memoria conviene limitarla en `docker-compose.yml`:

```yaml
    environment:
      - JAVA_OPTS=-Xmx256m -XX:+UseSerialGC
    mem_limit: 512m
```

Con unos cientos de tickets usa alrededor de 100–150 MB.

---

## 6. Cómo crear una contraseña de aplicación IMAP

Usar una **contraseña de aplicación** es la forma más sencilla y segura de conectar tu correo a MercaStats sin exponer tu contraseña principal ni configurar Google Cloud.

### En Gmail (Google)

1. Verifica que tienes activada la **Verificación en dos pasos** en [myaccount.google.com/security](https://myaccount.google.com/security). Sin ella, Google no muestra la opción de contraseñas de aplicación.
2. Entra en **Contraseñas de aplicaciones** (puedes buscar "Contraseñas de aplicaciones" en la barra de búsqueda de tu cuenta de Google o ir a [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords)).
3. Crea una nueva contraseña llamada, por ejemplo, `MercaStats`.
4. Copia la clave de 16 caracteres (puedes quitar los espacios) y añádela a tu `.env`:

   ```env
   IMAP_HOST=imap.gmail.com
   IMAP_PORT=993
   IMAP_SSL=true
   IMAP_USER=tu_correo@gmail.com
   IMAP_PASSWORD=abcdefghijklmnop
   IMAP_FOLDER=INBOX
   IMAP_SYNC_INTERVAL_MINUTES=60
   ```

> **Consejo:** si guardas los tickets de Mercadona en una etiqueta (por ejemplo `Tickets/Mercadona`), pon `IMAP_FOLDER=Tickets/Mercadona`, con las mismas mayúsculas. Comprueba en Gmail, en **Ajustes → Etiquetas**, que la etiqueta tiene marcado **Mostrar en IMAP**. Para buscar en todo el correo, usa `[Gmail]/Todos` (o `[Gmail]/All Mail` si Gmail está en inglés).

> **Si la página de contraseñas de aplicación dice que la opción «no está disponible para tu cuenta»:** o bien no tienes activada la verificación en dos pasos, o bien es una cuenta de empresa o de centro educativo (Google Workspace) cuyo administrador las ha desactivado. En ese caso, usa una cuenta personal de Gmail; por ejemplo, reenviando a ella los tickets con un filtro, o cambiando el correo del ticket digital en la app de Mercadona.

### En iCloud Mail (Apple)

1. Asegúrate de tener activa la **autenticación de doble factor** en tu cuenta de Apple.
2. Inicia sesión en [account.apple.com](https://account.apple.com/).
3. En la sección **Inicio de sesión y seguridad**, selecciona **Contraseñas específicas de la aplicación** y genera una nueva para `MercaStats`.
4. Usa tu dirección de Apple/iCloud y la contraseña generada (`xxxx-xxxx-xxxx-xxxx`) en `.env`:

   ```env
   IMAP_HOST=imap.mail.me.com
   IMAP_PORT=993
   IMAP_SSL=true
   IMAP_USER=tu_usuario@icloud.com
   IMAP_PASSWORD=xxxx-xxxx-xxxx-xxxx
   IMAP_FOLDER=INBOX
   IMAP_SYNC_INTERVAL_MINUTES=60
   ```

---

## 7. Despliegue detrás de authentik (Forward Auth)

En modo `AUTH_MODE=proxy`, MercaStats delega el inicio de sesión en tu proveedor de identidad (**authentik**, Authelia, etc.) y aprovisiona automáticamente cada usuario la primera vez que accede.

- Solo el usuario cuyo nombre coincide con `ADMIN_USER` es administrador; el resto son usuarios normales.
- Si ya existe una cuenta con ese nombre creada en modo `local` o `none`, el acceso se deniega: no se vincula automáticamente al proxy.

### Medidas de seguridad imprescindibles

- **No expongas el puerto `8080` directamente al exterior.** Deja MercaStats únicamente en una red Docker interna compartida con tu reverse proxy (o en `127.0.0.1:8080`).
- **Configura `TRUSTED_PROXIES`** con la IP exacta del reverse proxy, no con toda la subred Docker: cualquier otro contenedor de esa subred podría hacerse pasar por cualquier usuario. Lo más seguro es una red Docker dedicada a MercaStats y su proxy, con IP fija para el proxy (`ipv4_address`). MercaStats comprueba la IP TCP directa de la conexión, **no** `X-Forwarded-For`, y rechaza con `403 Forbidden` cualquier petición que no provenga de un proxy de confianza.

### Variables en `.env` para authentik

```env
AUTH_MODE=proxy
COOKIE_SECURE=true
PROXY_USER_HEADER=X-authentik-username
PROXY_EMAIL_HEADER=X-authentik-email
PROXY_NAME_HEADER=X-authentik-name
# IP fija de tu reverse proxy en la red que comparte con MercaStats
TRUSTED_PROXIES=172.30.0.2
# Usuario de authentik que recibirá permisos de administrador automáticamente
ADMIN_USER=tu_usuario_authentik
```

### Paso 1: Crear Provider y Application en authentik

1. En el panel de administración de **authentik**, ve a **Applications → Providers → Create** y elige **Proxy Provider**.
2. Selecciona el modo **Forward auth (single application)** y pon la URL externa (ej. `https://mercastats.tu-dominio.com`).
3. Crea una **Application** asociada a ese provider y añádela a tu **Embedded Outpost** (o al Outpost que uses).

### Paso 2A: Ejemplo con Traefik v3 (etiquetas en `docker-compose.yml`)

```yaml
services:
  mercastats:
    image: ghcr.io/neoruben/mercastats:latest
    restart: unless-stopped
    env_file:
      - .env
    volumes:
      - ./data:/data
    networks:
      - proxy
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.mercastats.rule=Host(`mercastats.tu-dominio.com`)"
      - "traefik.http.routers.mercastats.entrypoints=websecure"
      - "traefik.http.routers.mercastats.tls.certresolver=letsencrypt"
      - "traefik.http.routers.mercastats.middlewares=authentik@docker"
      - "traefik.http.services.mercastats.loadbalancer.server.port=8080"

networks:
  proxy:
    external: true
```

*(Asegúrate de que tu middleware `authentik` de Traefik reenvía las cabeceras `X-authentik-username`, `X-authentik-email` y `X-authentik-name` en `authResponseHeaders`.)*

### Paso 2B: Ejemplo con Nginx (Forward Auth a authentik)

```nginx
server {
    listen 443 ssl http2;
    server_name mercastats.tu-dominio.com;

    # Certificados TLS ...

    location / {
        proxy_pass http://mercastats:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        auth_request /outpost.goauthentik.io/auth/nginx;
        error_page 401 = @goauthentik_proxy_signin;

        auth_request_set $authentik_username $upstream_http_x_authentik_username;
        auth_request_set $authentik_email $upstream_http_x_authentik_email;
        auth_request_set $authentik_name $upstream_http_x_authentik_name;

        # Sobrescribe siempre las cabeceras de identidad (aunque el cliente las envíe).
        proxy_set_header X-authentik-username $authentik_username;
        proxy_set_header X-authentik-email $authentik_email;
        proxy_set_header X-authentik-name $authentik_name;
        proxy_set_header Remote-User "";
    }

    location /outpost.goauthentik.io {
        proxy_pass http://authentik-server:9000/outpost.goauthentik.io;
        proxy_set_header Host $host;
        proxy_set_header X-Original-URL $scheme://$http_host$request_uri;
        proxy_set_header Content-Length "";
        proxy_pass_request_body off;
    }

    location @goauthentik_proxy_signin {
        internal;
        return 302 /outpost.goauthentik.io/start?rd=$scheme://$http_host$request_uri;
    }
}
```

---

## 8. Referencia rápida de la API REST JSON

| Método | Ruta | Descripción |
| --- | --- | --- |
| `GET` | `/api/health` | Estado del servicio (`{"status":"ok"}`) para healthchecks. |
| `GET` | `/api/auth/me` | Estado de sesión, modo de autenticación y integraciones activas. |
| `POST` | `/api/auth/login` | Inicio de sesión local (`username`, `password`). |
| `POST` | `/api/auth/logout` | Cierre de sesión local. |
| `GET` | `/api/tickets` | Listado de tickets del usuario (filtros opcionales `?month=YYYY-MM&q=...`). |
| `GET` | `/api/tickets/{id}` | Detalle completo del ticket con líneas de producto y desglose de IVA. |
| `DELETE` | `/api/tickets/{id}` | Elimina un ticket del usuario. |
| `POST` | `/api/tickets/import` | Subida `multipart/form-data` de uno o varios PDFs. |
| `GET` | `/api/stats` | KPIs globales y desglose mensual (`monthlyStats`) y semanal (`weeklyStats`). |
| `GET` | `/api/products` | Ranking de productos (`?sort=frequency\|amount&q=...`). |
| `GET` | `/api/products/history` | Evolución de precio de un producto (`?key=<productKey>`). Responde `404` si no hay compras. |
| `GET` | `/api/sync/status` | Estado y último resultado de IMAP, Gmail OAuth e Inbox. Mientras hay una sincronización IMAP en curso incluye `imapProgress` (`done`, `total`, `inserted`…). |
| `POST` | `/api/sync/imap` | Lanza una sincronización IMAP en segundo plano y responde `202` al momento; el avance se consulta con `/api/sync/status`. Si ya hay una en curso, no lanza otra. |
| `POST` | `/api/sync/inbox` | Fuerza un escaneo inmediato de `/data/inbox`. Solo administradores. |
| `GET` | `/api/gmail/oauth/start` | Inicia el flujo OAuth 2.0 de Gmail (si está configurado). |
| `POST` | `/api/sync/gmail` | Lanza una sincronización Gmail OAuth manual. |

---

## 9. Copias de seguridad

Toda la información reside en un único archivo SQLite (`/data/mercastats.db`). Al usar modo WAL, realiza las copias de seguridad con el contenedor detenido o usando el comando `.backup` de SQLite (desde el equipo anfitrión, con `sqlite3` instalado):

```bash
sqlite3 ./data/mercastats.db ".backup './mercastats-backup.db'"
```

---

## 10. Actualizar

Si usas la imagen publicada:

```bash
docker compose pull && docker compose up -d
```

Si compilas desde el código (`build: .` en `docker-compose.yml`):

```bash
git pull && docker compose up -d --build
```

La base de datos se conserva en `./data`.
