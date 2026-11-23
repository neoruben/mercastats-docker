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

La imagen está publicada en GitHub:

```text
ghcr.io/neoruben/mercastats:latest
```

No hace falta descargar el código: basta con un `docker-compose.yml` y un `.env`. Necesitas Docker con el plugin Compose (`docker compose version` debe responder) y unos 300 MB de RAM libres.

### 1. Inicia sesión en GitHub desde Docker (solo mientras la imagen sea privada)

La imagen todavía es privada, así que Docker necesita permiso para descargarla. Se hace una sola vez en cada equipo:

1. En GitHub, ve a tu foto → **Settings** → **Developer settings** → **Personal access tokens** → **Tokens (classic)** → **Generate new token (classic)**.
2. Ponle un nombre (por ejemplo `Docker`), elige la caducidad y marca **solo** `read:packages`. Los tokens «Fine-grained» no sirven para esto.
3. Pulsa **Generate token** y copia el token (empieza por `ghp_`; solo se muestra una vez).
4. En el equipo donde vas a instalar MercaStats:

   ```bash
   docker login ghcr.io -u TU_USUARIO_DE_GITHUB
   ```

   Cuando pida `Password:`, pega el token (no se ve al pegarlo) y pulsa Enter. Debe responder `Login Succeeded`.

### 2. Crea una carpeta para MercaStats

```bash
mkdir -p mercastats/data/inbox
cd mercastats
```

`data/` guardará la base de datos y `data/inbox` es la carpeta vigilada donde puedes dejar PDFs.

### 3. Crea el `docker-compose.yml`

```bash
nano docker-compose.yml
```

Pega esto:

```yaml
services:
  mercastats:
    image: ghcr.io/neoruben/mercastats:latest
    container_name: mercastats
    restart: unless-stopped
    user: "1000:1000"            # tu UID:GID (ver paso 5)
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
| Fijar una versión en lugar de la última | etiqueta de `image` | `ghcr.io/neoruben/mercastats:0.1.0` |

### 4. Crea el `.env`

```bash
nano .env
```

Lo mínimo es la contraseña del administrador:

```ini
ADMIN_USER=admin
ADMIN_PASSWORD=pon-aqui-una-contraseña-larga
```

Para que lea los tickets del correo automáticamente, añade la parte de IMAP. Con Gmail:

```ini
IMAP_HOST=imap.gmail.com
IMAP_USER=tu_correo@gmail.com
IMAP_PASSWORD=xxxx xxxx xxxx xxxx
IMAP_FOLDER=Tickets/Mercadona
IMAP_SYNC_INTERVAL_MINUTES=60
```

- `IMAP_PASSWORD` es una **contraseña de aplicación**, no la de tu cuenta. Cómo crearla en Gmail o iCloud: [docs/DOCKER.md, apartado 6](docs/DOCKER.md#6-cómo-crear-una-contraseña-de-aplicación-imap).
- `IMAP_FOLDER` es la etiqueta de Gmail donde guardas los tickets (si está dentro de otra, con `/`). Para buscar en todo el correo: `[Gmail]/Todos`. La etiqueta tiene que tener activado «Mostrar en IMAP» en la configuración de Gmail.
- El resto de opciones están en [`.env.example`](.env.example) y en [docs/DOCKER.md](docs/DOCKER.md#5-variables-de-entorno).

El `.env` tiene contraseñas: no lo compartas ni lo subas a ningún sitio.

### 5. Permisos de la carpeta de datos

El contenedor no se ejecuta como root, así que la carpeta `data` tiene que ser del usuario con el que corre:

- **Con `user: "1000:1000"`** (como en el paso 3): el contenedor corre con tu usuario y la carpeta sigue siendo tuya, así que puedes copiar PDFs a `data/inbox` sin `sudo`. Comprueba tu UID y GID con `id`; si no son `1000`, cámbialos en el compose. Si creaste la carpeta tú en el paso 2, no hay nada más que hacer.
- **Sin la línea `user:`**: el contenedor corre con el usuario `10001` y tienes que darle la carpeta:

  ```bash
  sudo chown -R 10001:10001 data
  ```

### 6. Arranca

```bash
docker compose up -d
```

Comprueba que ha arrancado:

```bash
docker compose ps
docker compose logs -f mercastats
```

Cuando en `ps` aparezca `healthy`, abre `http://IP-del-equipo:8080` (o `http://localhost:8080` en el propio equipo) y entra con `admin` y la contraseña del `.env`.

### 7. Importa tus tickets

- Si configuraste IMAP, la primera sincronización **empieza sola al arrancar** y los tickets van apareciendo en la web (con unos 200 tickets tarda unos minutos). Después se repite cada `IMAP_SYNC_INTERVAL_MINUTES`.
- El botón **Sincronizar** (arriba) la lanza cuando quieras.
- Con **Importar** puedes arrastrar PDFs o elegirlos desde el móvil: se cargan solos.
- O copia los PDFs en `data/inbox`: en menos de un minuto aparecen en la web.

---

## Instalación con Dockge o Portainer

Usa el mismo compose del paso 3 y el mismo `.env` del paso 4, con dos diferencias:

**1. El gestor también tiene que poder descargar la imagen privada.** Dockge y Portainer corren en su propio contenedor y no ven el `docker login` que hiciste en el equipo.

- **Dockge:** añade esta línea en los `volumes:` del compose de Dockge y reinícialo (`docker compose up -d` en su carpeta). Cambia `TU_USUARIO` por tu usuario del equipo:

  ```yaml
        - /home/TU_USUARIO/.docker/config.json:/root/.docker/config.json:ro
  ```

- **Portainer:** ve a **Registries** → **Add registry** → **Custom registry**, con URL `ghcr.io`, tu usuario de GitHub y el token como contraseña.

**2. La carpeta de datos la crea el gestor como root**, así que hay que darle permisos antes de desplegar.

Pasos en Dockge:

1. Pulsa **+ Componer** y ponle de nombre `mercastats`.
2. Pega el compose del paso 3 y, en el cuadro `.env`, el contenido del paso 4.
3. Pulsa **Guardar**, todavía no **Desplegar**.
4. En una terminal del equipo, crea la carpeta de datos con tu usuario (cambia la ruta si tus stacks no están en `/opt/stacks`):

   ```bash
   sudo install -d -o 1000 -g 1000 /opt/stacks/mercastats/data /opt/stacks/mercastats/data/inbox
   ```

5. Pulsa **Desplegar** y sigue desde el paso 6.

---

## Mantenimiento

**Actualizar a la última versión** (los datos se conservan):

```bash
docker compose pull
docker compose up -d
```

En Dockge, con el botón **Actualizar** del stack.

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
| `denied` o `unauthorized` al descargar la imagen | Falta el `docker login` del paso 1 (o, en Dockge y Portainer, darles acceso al registro) |
| En los logs: `AccessDeniedException` o no puede crear `/data/...` | Permisos de `data`: repasa el paso 5 |
| `port is already allocated` | Ese puerto ya lo usa otro contenedor: cambia el primer número de `ports` |
| He cambiado `ADMIN_PASSWORD` en el `.env` y no entra con la nueva | `ADMIN_PASSWORD` solo se usa al crear el administrador en el primer arranque; después la contraseña se cambia desde la web, en **Cambiar contraseña** |
| No importa nada del correo | Revisa `IMAP_FOLDER` (la etiqueta debe tener activado «Mostrar en IMAP») y mira los logs |
| Se cierra solo en la Raspberry Pi | Activa `JAVA_OPTS` y `mem_limit` |

---

## Compilar la imagen desde el código

Si prefieres no usar la imagen publicada:

```bash
git clone https://github.com/neoruben/mercastats-docker.git
cd mercastats-docker
cp docker-compose.example.yml docker-compose.yml
```

En `docker-compose.yml` cambia la línea `image:` por `build: .` y arranca con `docker compose up -d --build` (la primera vez tarda varios minutos). Para actualizar: `git pull && docker compose up -d --build`.

---

## Documentación

- [docs/DOCKER.md](docs/DOCKER.md): todas las variables de entorno, correo (Gmail e iCloud), authentik, API y más detalles.
- [docs/DESARROLLO.md](docs/DESARROLLO.md): estructura del código, compilar y probar, publicar una versión y mejoras pendientes.

## Licencia

Consulta el archivo [LICENSE](LICENSE).
