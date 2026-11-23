# Desarrollo de MercaStats (Docker)

Guía para trabajar con el código: estructura, cómo compilar y probar, cómo publicar una versión y qué queda pendiente.

---

## 1. Estructura del proyecto

```text
core/                 Lectura de tickets y cálculo de estadísticas (Kotlin, sin dependencias)
  parser/             MercadonaTicketParser: convierte el texto del PDF en un ticket
  stats/              StatsCalculator: totales por mes y semana, ranking y evolución de precios
server/               Servidor web (Ktor) con SQLite
  api/                Rutas REST (/api/...) y modelos JSON
  auth/               Inicio de sesión (local, proxy, none) y comprobación de IPs de confianza
  config/             Lectura de variables de entorno
  db/                 Base de datos SQLite (usuarios, sesiones, tickets)
  pdf/                Extracción de texto de los PDF (Apache PDFBox)
  service/            Importación, carpeta vigilada, IMAP y Gmail OAuth
  resources/static/   Web (HTML, CSS y JavaScript sin dependencias ni paso de compilación)
docs/                 Documentación
Dockerfile            Imagen en dos fases: compila con el JDK y ejecuta con un JRE ligero
docker-compose.example.yml, .env.example   Plantillas para instalar
.github/workflows/docker.yml               Tests y publicación de la imagen en GHCR
```

---

## 2. Compilar y probar

Hace falta un JDK 17 o superior. La versión de Gradle la fija el wrapper (`./gradlew`), no hace falta instalarlo.

```bash
./gradlew :core:test :server:test
```

Si no tienes un JDK instalado, puedes usar uno en Docker:

```bash
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/p -w /p eclipse-temurin:21-jdk ./gradlew :core:test :server:test
```

Para arrancar el servidor sin Docker (los datos se guardan en `./data`):

```bash
DATA_DIR=./data ADMIN_PASSWORD=una-contraseña ./gradlew :server:run
```

Y abre `http://localhost:8080`.

Para construir la imagen:

```bash
docker build -t mercastats:local .
```

---

## 3. Publicar una versión

El workflow `.github/workflows/docker.yml` se ejecuta al subir una etiqueta que empiece por `v`:

```bash
git tag v1.0.0
git push origin v1.0.0
```

1. Pasa los tests de `core` y `server`.
2. Construye la imagen para `linux/amd64` y `linux/arm64` (PC y Raspberry Pi).
3. La sube a `ghcr.io/<usuario>/mercastats` con las etiquetas `1.0.0`, `1.0`, `1` y `latest`.

También se puede lanzar a mano desde la pestaña **Actions** de GitHub; en ese caso la imagen se etiqueta con el nombre de la rama y el identificador del commit.

> Si el repositorio es privado, la imagen también lo es: para descargarla hay que iniciar sesión con `docker login ghcr.io` usando un token de GitHub con permiso `read:packages`.

---

## 4. Decisiones de diseño

- **Un solo usuario por cada ticket:** los tickets pertenecen a un usuario. Los duplicados se detectan por usuario (número de factura, huella del PDF o identificador de correo), así que dos personas de la misma casa pueden tener sus propias cuentas.
- **Seguridad del modo proxy:** solo se acepta una cabecera de identidad y solo si la conexión llega desde una IP de `TRUSTED_PROXIES`. Una entrada mal escrita en esa variable impide arrancar en lugar de abrir el acceso. Solo `ADMIN_USER` es administrador y las cuentas creadas en otro modo no se vinculan solas.
- **IMAP:** con `IMAP_SSL=false` se exige STARTTLS; si el servidor no lo ofrece, se aborta antes de enviar la contraseña.
- **Sincronización en segundo plano:** `POST /api/sync/imap` responde al momento y la web consulta el avance en `/api/sync/status`, para que una primera sincronización larga no corte la conexión.
- **Carpeta vigilada:** se ignoran los enlaces simbólicos para no salir de la carpeta.

---

## 5. Pendiente

Mejoras conocidas que aún no están hechas:

- El límite de 15 MB por PDF se comprueba después de leer el archivo en memoria; convendría aplicarlo durante la lectura y limitar el tamaño total de cada subida.
- Si un correo trae varios PDF y uno falla, en la siguiente sincronización se salta el correo entero. Habría que registrar el resultado de cada adjunto y usar el UID de IMAP.
- La sincronización de correo borra los tickets que no tienen ninguna línea de producto, incluidos los subidos a mano. Sería mejor validar el ticket antes de guardarlo y no borrar nada automáticamente.
- Las sesiones siguen abiertas después de cambiar la contraseña, no hay límite de intentos de inicio de sesión y, si no se define `ADMIN_PASSWORD`, la contraseña generada se escribe en los logs.
- En el ranking de productos se agrupa solo por nombre, así que un producto vendido por unidades y al peso con el mismo nombre se suma junto.
- Un mismo producto con nombres distintos (por ejemplo, en catalán y en castellano) aparece como dos productos.
