<h1 align="center">MercaStats (Docker)</h1>

<p align="center">
  <a href="https://ko-fi.com/neoruben" target="_blank">
    <img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Support me on Ko-fi" height="38">
  </a>
</p>

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

Necesitas Docker con Docker Compose 2.24 o más reciente (`docker compose version`). Se instala con dos archivos en una misma carpeta: `docker-compose.yml`, que no hay que tocar, y `.env`, con tu configuración.

### 1. El `docker-compose.yml`

Cópialo tal cual:

```yaml
services:
  mercastats:
    image: ghcr.io/neoruben/mercastats:latest
    container_name: mercastats
    restart: unless-stopped
    ports:
      - "8080:8080"
    env_file:
      - path: .env
        required: false
    volumes:
      - ./data:/data
```

Lo único que quizá quieras cambiar es el puerto: el primer número de `ports` es el puerto en el que se abrirá la web (por ejemplo, `"8070:8080"` para usar el 8070).

### 2. El `.env`

Junto al compose, crea un archivo `.env` con tu configuración, una opción por línea. Lo mínimo es la contraseña del usuario `admin`:

```ini
ADMIN_PASSWORD=una-contraseña-larga
```

Si quieres que lea los tickets de tu correo, añade también:

```ini
IMAP_HOST=imap.gmail.com
IMAP_USER=tu_correo@gmail.com
IMAP_PASSWORD=xxxx xxxx xxxx xxxx
IMAP_FOLDER=Tickets/Mercadona
```

Qué significa cada una está en [Importar tickets](#importar-tickets).

> **Importante:** pon `ADMIN_PASSWORD` **antes de arrancar por primera vez**. El usuario `admin` se crea en ese primer arranque con la contraseña que haya entonces, y después ya no se cambia desde aquí. Más detalles en [La contraseña de `admin`](#la-contraseña-de-admin).

### 3. Arranca

En la carpeta del compose:

```bash
docker compose up -d
```

La carpeta `data` se crea sola junto al compose. Ahí se guarda la base de datos, y dentro está `data/inbox`, una carpeta vigilada: los PDFs que copies ahí se importan solos.

### 4. Entra

Abre `http://IP-del-equipo:8080` y entra con el usuario **`admin`** y la contraseña de `ADMIN_PASSWORD`.

### La contraseña de `admin`

El usuario `admin` se crea **una sola vez, en el primer arranque**, y su contraseña se decide en ese momento:

- **Si había `ADMIN_PASSWORD`**, es esa.
- **Si no había**, MercaStats genera una al azar y la escribe en los logs. Para verla:

  ```bash
  docker compose logs mercastats | grep Contraseña
  ```

A partir de ahí, la contraseña se cambia **desde la web**, en **Cambiar contraseña**. Poner o cambiar `ADMIN_PASSWORD` después del primer arranque **no tiene ningún efecto**, porque el usuario ya existe y su contraseña está guardada en la base de datos.

Si arrancaste sin `ADMIN_PASSWORD` y prefieres la tuya, tienes dos opciones:

1. Entra con la contraseña generada y cámbiala desde la web.
2. Si acabas de instalar y aún no tienes datos, empieza de cero: para el contenedor (`docker compose down`), borra la carpeta `data`, pon `ADMIN_PASSWORD` en el `.env` y vuelve a arrancar (`docker compose up -d`).

### Más opciones

Se añaden al `.env`, como las anteriores:

| Opción | Para qué |
|---|---|
| `JAVA_OPTS=-Xmx256m -XX:+UseSerialGC` | Gastar menos memoria; recomendado en Raspberry Pi |
| `PUID=1000` y `PGID=1000` | Usuario y grupo dueños de la carpeta `data` (los tuyos se ven con el comando `id`). Por defecto, 1000 |
| `TZ=Europe/Madrid` | Zona horaria (por defecto, Madrid) |
| `IMAP_SYNC_INTERVAL_MINUTES=60` | Cada cuánto se mira el correo (`0` = solo al pulsar Sincronizar) |
| `ALLOW_REGISTRATION=true` | Que otras personas puedan crearse una cuenta desde la pantalla de inicio |

La lista completa está en [docs/DOCKER.md](docs/DOCKER.md#5-variables-de-entorno) y hay una plantilla en [`.env.example`](.env.example). Después de cambiar el `.env`, aplica los cambios con `docker compose up -d`.

Si prefieres no usar `.env`, también puedes poner las opciones en el compose, en un apartado `environment:` (con el formato `- ADMIN_PASSWORD=...`).

---

## Importar tickets

- **Desde el correo:** pon las líneas `IMAP_...` en el `.env` (ver la instalación) y arranca o reinicia con `docker compose up -d`. La primera sincronización empieza sola unos segundos después de arrancar, aunque no hayas entrado en la web, y los tickets van apareciendo; con unos 200 tickets tarda unos minutos. Después se repite cada hora, y también al pulsar **Sincronizar**.
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
| `port is already allocated` | Ese puerto ya lo usa otro programa: cambia el primer número de `ports` en el compose |
| No sé la contraseña de `admin` | Si no pusiste `ADMIN_PASSWORD` en el primer arranque, está en los logs. Ver «La contraseña de `admin`» |
| He puesto o cambiado `ADMIN_PASSWORD` y no entra con ella | Solo cuenta en el primer arranque. Ver «La contraseña de `admin`» |
| He puesto opciones en el `.env` y no hacen nada | El `.env` tiene que estar en la misma carpeta que el compose, y el compose tiene que tener el apartado `env_file` del paso 1. Después, `docker compose up -d` |
| No importa nada del correo | Revisa `IMAP_FOLDER` y que la etiqueta tenga activado «Mostrar en IMAP», y mira `docker compose logs mercastats` |
| No puedo copiar PDFs en `data/inbox` | Pon en `PUID` y `PGID` tu usuario y grupo (comando `id`) y reinicia |
| Se cierra solo en la Raspberry Pi | Añade `JAVA_OPTS=-Xmx256m -XX:+UseSerialGC` al `.env` |

---

## Más información

- [docs/DOCKER.md](docs/DOCKER.md): todas las opciones, correo (Gmail e iCloud), uso detrás de authentik y API.
- [docs/DESARROLLO.md](docs/DESARROLLO.md): para quien quiera modificar el código.

## Apoya el proyecto

Si te gusta el proyecto o te resulta de utilidad, puedes apoyar su desarrollo invitándome a un café en [Ko-fi](https://ko-fi.com/neoruben).

## Licencia

Consulta el archivo [LICENSE](LICENSE).
