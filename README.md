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
- Imagen para `linux/amd64` y `linux/arm64` (PC, NAS, Raspberry Pi). Todos los datos en un único archivo SQLite.

## Instalación rápida

```bash
cp docker-compose.example.yml docker-compose.yml
cp .env.example .env        # pon al menos ADMIN_PASSWORD
mkdir -p data/inbox && sudo chown -R 10001:10001 data
docker compose up -d
```

Abre `http://localhost:8080` e inicia sesión con `ADMIN_USER` (por defecto `admin`) y `ADMIN_PASSWORD`.

Si aún no hay imagen publicada, descomenta `build: .` en `docker-compose.yml` y usa `docker compose up -d --build` para compilarla en tu equipo.

## Documentación

- [docs/DOCKER.md](docs/DOCKER.md): instalación, uso de la web, variables de entorno, correo (Gmail e iCloud), authentik, API y copias de seguridad.
- [docs/DESARROLLO.md](docs/DESARROLLO.md): estructura del código, compilar y probar, publicar una versión y mejoras pendientes.

## Licencia

Consulta el archivo [LICENSE](LICENSE).
