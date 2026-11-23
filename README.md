# MercaStats (Docker)

Versión web para servidor propio de MercaStats: estadísticas de tus tickets de Mercadona en PDF, desde el navegador del ordenador o del móvil.

> MercaStats es un proyecto personal independiente. **No está afiliado, asociado ni respaldado por Mercadona, S.A.**

## Qué hace

- **Tickets:** tus compras agrupadas por mes, con buscador y el detalle de cada ticket (productos e IVA).
- **Estadísticas:** gasto total, ticket medio, IVA y evolución por meses o semanas.
- **Productos:** los más comprados y los de mayor importe. Al pulsar uno ves la evolución de su precio.
- **Importación automática:** desde el correo (IMAP, con una contraseña de aplicación), desde una carpeta vigilada o subiendo los PDFs desde la web. Sin duplicados.
- **Varios usuarios**, con inicio de sesión propio o detrás de authentik/Authelia.
- **Pensada también para el móvil:** barra de navegación inferior y ventanas que suben desde abajo.
- Funciona en `linux/amd64` y `linux/arm64` (PC, NAS, Raspberry Pi). Todos los datos en un único archivo SQLite.

---

## Instalación con Docker Compose, paso a paso

Necesitas Docker con el plugin Compose (`docker compose version` debe responder) y unos 300 MB de RAM libres.

### 1. Descarga el código

El repositorio es privado: al clonar, GitHub te pide tu usuario y, como contraseña, un **token de acceso** (GitHub → Settings → Developer settings → Personal access tokens), no la contraseña de la cuenta.

```bash
git clone https://github.com/neoruben/mercastats-docker.git
cd mercastats-docker
```

### 2. Crea el `docker-compose.yml`

```bash
cp docker-compose.example.yml docker-compose.yml
```

Queda así (puedes copiarlo tal cual):

```yaml
services:
  mercastats:
    build: .                     # compila la imagen con el código de esta carpeta
    image: mercastats:local
    container_name: mercastats
    restart: unless-stopped
    # user: "1000:1000"          # opcional, ver paso 4
    ports:
      - "8080:8080"              # puerto del equipo : puerto del contenedor
    env_file:
      - .env
    environment:
      - TZ=Europe/Madrid
      # - JAVA_OPTS=-Xmx256m -XX:+UseSerialGC   # recomendado en Raspberry Pi
    # mem_limit: 512m                           # recomendado en Raspberry Pi
    volumes:
      - ./data:/data             # base de datos y carpeta vigilada
```

Lo que puedes cambiar:

| Qué | Dónde | Ejemplo |
|---|---|---|
| Puerto en el que se abre la web | primer número de `ports` | `"8070:8080"` para abrirla en el 8070 |
| Abrirla solo desde el propio equipo | `ports` | `"127.0.0.1:8080:8080"` |
| Memoria en una Raspberry Pi | descomenta `JAVA_OPTS` y `mem_limit` | |
| Dónde se guardan los datos | parte izquierda de `volumes` | `/srv/mercastats:/data` |

### 3. Crea el `.env`

```bash
cp .env.example .env
nano .env
```

Lo mínimo es cambiar la contraseña del administrador:

```ini
ADMIN_USER=admin
ADMIN_PASSWORD=pon-aqui-una-contraseña-larga
```

Para que lea los tickets del correo automáticamente, descomenta y rellena también la parte de IMAP. Con Gmail:

```ini
IMAP_HOST=imap.gmail.com
IMAP_USER=tu_correo@gmail.com
IMAP_PASSWORD=xxxx xxxx xxxx xxxx    # contraseña de aplicación, no la de tu cuenta
IMAP_FOLDER=Tickets/Mercadona        # etiqueta de Gmail donde guardas los tickets
IMAP_SYNC_INTERVAL_MINUTES=60
```

Cómo sacar la contraseña de aplicación de Gmail o iCloud: [docs/DOCKER.md, apartado 6](docs/DOCKER.md#6-cómo-crear-una-contraseña-de-aplicación-imap). El `.env` tiene contraseñas: no lo compartas ni lo subas a ningún sitio.

### 4. Prepara la carpeta de datos

El contenedor no se ejecuta como root, sino con el usuario `10001`. Elige una de estas dos opciones:

- **Opción A (sencilla):** la carpeta pasa a ser del usuario del contenedor.

  ```bash
  mkdir -p data/inbox
  sudo chown -R 10001:10001 data
  ```

- **Opción B:** la carpeta sigue siendo tuya, así puedes copiar PDFs a `data/inbox` sin `sudo`. Mira tu UID y GID con `id` (normalmente `1000`), descomenta `user: "1000:1000"` en el `docker-compose.yml` y crea la carpeta:

  ```bash
  mkdir -p data/inbox
  ```

### 5. Arranca

```bash
docker compose up -d --build
```

La primera vez tarda varios minutos porque compila la imagen; las siguientes, mucho menos. Comprueba que ha arrancado:

```bash
docker compose ps
docker compose logs -f mercastats
```

Cuando en `ps` aparezca `healthy`, abre `http://IP-del-equipo:8080` (o `http://localhost:8080` en el propio equipo) y entra con `admin` y la contraseña del `.env`.

### 6. Importa tus tickets

- Pulsa **Sincronizar** (arriba) para leer el correo y la carpeta vigilada.
- O pulsa **Importar** y arrastra los PDFs, o elígelos desde el móvil: se cargan solos.
- O copia los PDFs en `data/inbox`: en menos de un minuto aparecen en la web.

### Con Dockge o Portainer

Crea un stack nuevo, pega el `docker-compose.yml` del paso 2 cambiando `build: .` por la ruta completa de la carpeta donde clonaste el código (por ejemplo `build: /home/usuario/mercastats-docker`), pega el contenido del `.env` en el apartado de variables o junto al compose, y despliega.

---

## Mantenimiento

**Actualizar a la última versión** (los datos se conservan):

```bash
git pull
docker compose up -d --build
```

**Copia de seguridad:** todo está en `data/mercastats.db`. Para copiarlo con el contenedor en marcha:

```bash
sqlite3 data/mercastats.db ".backup 'mercastats-copia.db'"
```

O para el contenedor (`docker compose stop`), copia la carpeta `data` y vuelve a arrancarlo (`docker compose start`).

**Parar o quitar:**

```bash
docker compose stop      # parar
docker compose down      # quitar el contenedor (los datos de data/ se conservan)
```

**Problemas frecuentes:**

| Síntoma | Solución |
|---|---|
| En los logs: `AccessDeniedException` o no puede crear `/data/...` | Permisos de `data`: repite el paso 4 |
| `port is already allocated` | Ese puerto ya lo usa otro contenedor: cambia el primer número de `ports` |
| He cambiado `ADMIN_PASSWORD` en el `.env` y no entra con la nueva | `ADMIN_PASSWORD` solo se usa al crear el administrador en el primer arranque; después la contraseña se cambia desde la web, en **Cambiar contraseña** |
| No importa nada del correo | Revisa `IMAP_FOLDER` (las etiquetas de Gmail deben tener activado «Mostrar en IMAP») y mira los logs |
| Se cierra solo en la Raspberry Pi | Activa `JAVA_OPTS` y `mem_limit` |

---

## Documentación

- [docs/DOCKER.md](docs/DOCKER.md): todas las variables de entorno, correo (Gmail e iCloud), authentik, API y más detalles.
- [docs/DESARROLLO.md](docs/DESARROLLO.md): estructura del código, compilar y probar, publicar una versión y mejoras pendientes.

## Licencia

Consulta el archivo [LICENSE](LICENSE).
