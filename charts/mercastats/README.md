# Chart de Helm de MercaStats

Despliega [MercaStats](../../README.md) (estadísticas de tus tickets de Mercadona en PDF) en cualquier clúster de Kubernetes: k3s, k0s, MicroK8s, kind, minikube, Talos, RKE2, OpenShift, EKS, GKE, AKS…

> MercaStats es un proyecto personal independiente. **No está afiliado, asociado ni respaldado por Mercadona, S.A.**

## Qué incluye

| Recurso | Para qué | Por defecto |
| --- | --- | --- |
| Deployment | El servidor, sin root, con el sistema de archivos de solo lectura y estrategia `Recreate` | Siempre |
| ConfigMap / Secret | La configuración (variables de entorno de la imagen). Las contraseñas van en el Secret, o en uno tuyo (`existingSecret`) | Siempre |
| PersistentVolumeClaim | Base de datos SQLite y carpeta vigilada en `/data` | Activado |
| Service | `ClusterIP`, `NodePort` o `LoadBalancer` | `ClusterIP` |
| Ingress | Con cualquier ingress controller (nginx, Traefik, HAProxy…) y TLS | Desactivado |
| HTTPRoute | Gateway API v1 (Envoy Gateway, Cilium, Istio, Traefik…) | Desactivado |
| NetworkPolicy | Limita quién llega al pod y, si quieres, a dónde sale | Desactivado |
| CronJob de copias | `sqlite3 .backup` diario a otro volumen, conservando las últimas N | Desactivado |
| Volumen de inbox | Carpeta vigilada en un volumen aparte (NFS, SMB…) | Desactivado |
| `helm test` | Comprueba que `/api/health` responde | Activado |

Y además: `values.schema.json` para validar los valores antes de desplegar, mensajes de error claros si falta algo (por ejemplo, `trustedProxies` en modo proxy), adaptación automática a OpenShift, `global.imageRegistry` para mirrors, `extraEnv`, `sidecars`, `initContainers`, `extraVolumes` y `extraObjects` para todo lo demás.

## Requisitos

- Kubernetes 1.21 o más reciente (`backup.timeZone` necesita 1.27).
- Helm 3.8 o más reciente (también Helm 4).
- Un StorageClass por defecto o un volumen que puedas montar (PVC, `hostPath`, NFS…).
- Nodos `amd64` o `arm64` (Raspberry Pi 4/5 incluida).

## Instalación rápida

```bash
helm install mercastats ./charts/mercastats \
  --namespace mercastats --create-namespace \
  --set config.auth.adminPassword='una-contraseña-larga'
```

```bash
kubectl port-forward -n mercastats svc/mercastats 8080:8080
```

Abre <http://127.0.0.1:8080> y entra como `admin`. Las notas que muestra Helm al terminar dicen cómo llegar a la web según lo que hayas configurado.

> **Importante:** igual que con Docker, la contraseña de `admin` **solo cuenta en el primer arranque**. Si no la pones, se genera una y aparece en los logs (`kubectl logs -n mercastats deploy/mercastats | grep -A1 "Contraseña generada"`). Después se cambia desde la web.

Para algo más que una prueba, guarda tu configuración en un archivo y no pongas contraseñas en la línea de comandos:

```bash
helm install mercastats ./charts/mercastats -n mercastats --create-namespace -f my-values.yaml
```

```bash
helm upgrade mercastats ./charts/mercastats -n mercastats -f my-values.yaml
```

## Ejemplos

### Con Ingress y certificado de cert-manager

```yaml
ingress:
  enabled: true
  className: nginx            # o traefik, haproxy...
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt
    # Solo ingress-nginx: permite subir varios PDFs de una vez.
    nginx.ingress.kubernetes.io/proxy-body-size: 50m
  hosts:
    - host: mercastats.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: mercastats-tls
      hosts:
        - mercastats.example.com
```

La URL pública (`BASE_URL`) se deduce del primer host del Ingress, y como tiene TLS la cookie de sesión se marca `Secure` sola (`config.auth.cookieSecure: auto`).

### Con Gateway API

```yaml
config:
  baseUrl: https://mercastats.example.com   # con HTTPRoute no se deduce: ponla tú
httpRoute:
  enabled: true
  parentRefs:
    - name: gateway
      namespace: gateway-system
      sectionName: https
  hostnames:
    - mercastats.example.com
```

### Contraseñas en un Secret propio (Sealed Secrets, External Secrets, SOPS…)

```bash
kubectl create secret generic mercastats-secrets -n mercastats \
  --from-literal=ADMIN_PASSWORD='una-contraseña-larga' \
  --from-literal=IMAP_PASSWORD='abcd efgh ijkl mnop'
```

```yaml
existingSecret: mercastats-secrets
# Solo si tus claves se llaman de otra forma:
# secretKeys:
#   adminPassword: admin-password
#   imapPassword: imap-password
#   gmailClientSecret: gmail-client-secret
config:
  imap:
    enabled: true
    host: imap.gmail.com
    user: tu_correo@gmail.com
    folder: Tickets/Mercadona
```

Cómo crear la contraseña de aplicación de Gmail o iCloud: [docs/DOCKER.md](../../docs/DOCKER.md#6-cómo-crear-una-contraseña-de-aplicación-imap).

### Detrás de authentik / Authelia (`AUTH_MODE=proxy`)

MercaStats comprueba la IP TCP de quien le habla, y en Kubernetes esa es la IP **del pod** del ingress controller (o del outpost), no la del Service. Pon su IP o el rango de pods, y limita con una NetworkPolicy quién puede llegar al pod:

```yaml
config:
  auth:
    mode: proxy
    adminUser: tu_usuario_authentik
    cookieSecure: true
    proxy:
      userHeader: X-authentik-username
      trustedProxies:
        - 10.42.0.0/16          # rango de pods (k3s por defecto) o la IP del ingress controller
networkPolicy:
  enabled: true
  ingress:
    from:
      - namespaceSelector:
          matchLabels:
            kubernetes.io/metadata.name: ingress-nginx
```

El proxy tiene que **sobrescribir siempre** la cabecera del usuario, también cuando la envía el cliente. Ejemplos de Traefik y nginx en [docs/DOCKER.md](../../docs/DOCKER.md#7-despliegue-detrás-de-authentik-forward-auth).

### Raspberry Pi / k3s con poco espacio

```yaml
nodeSelector:
  kubernetes.io/arch: arm64
persistence:
  data:
    storageClass: local-path
    size: 512Mi
resources:
  requests:
    cpu: 50m
    memory: 160Mi
  limits:
    memory: 384Mi
```

### Datos en una carpeta del nodo (`hostPath`) o en un NAS

```yaml
persistence:
  data:
    volume:
      hostPath:
        path: /srv/mercastats
        type: DirectoryOrCreate
  # La carpeta vigilada en una carpeta compartida del NAS: deja ahí los PDFs.
  inbox:
    enabled: true
    volume:
      nfs:
        server: nas.lan
        path: /export/tickets
# hostPath y NFS no respetan fsGroup: este init container ajusta los dueños.
volumePermissions:
  enabled: true
# Con hostPath, fija el pod al nodo que tiene la carpeta.
nodeSelector:
  kubernetes.io/hostname: nombre-del-nodo
```

No pongas la base de datos (`persistence.data`) en NFS: SQLite necesita bloqueos de archivo fiables.

Al dejar PDFs en la carpeta vigilada, cópialos primero con otro nombre o en otra carpeta y muévelos o renómbralos después. MercaStats importa un archivo en cuanto lleva 2 segundos sin cambiar, y si una copia lenta (por red, SMB o `kubectl`) se detiene ese tiempo, lo coge a medio copiar y lo deja en `inbox/failed`.

### Copias de seguridad

```yaml
backup:
  enabled: true
  schedule: "0 3 * * *"
  timeZone: Europe/Madrid
  keep: 14
  persistence:
    size: 2Gi
```

El CronJob usa `sqlite3 .backup` (seguro con la base de datos en uso), comprueba la copia con `PRAGMA integrity_check` y borra las más antiguas. Los nombres llevan la hora en UTC (`mercastats-20261126-120616Z.db`).

Como el volumen de datos suele ser `ReadWriteOnce`, el Job se programa en el mismo nodo que MercaStats (`backup.sameNodeAsApp`). Además, el volumen de copias se monta en solo lectura en `/backups` del pod de MercaStats (`backup.mountInApp`). Así el PVC se enlaza al instalar, también con StorageClasses `WaitForFirstConsumer`, y `helm install --wait` o Argo CD no se quedan esperando a la primera copia. De paso, las copias se pueden ver sin pods extra:

```bash
kubectl exec -n mercastats deploy/mercastats -- ls -l /backups
```

Para lanzar una copia ahora:

```bash
kubectl create job -n mercastats --from=cronjob/mercastats-backup mercastats-backup-manual
```

**Restaurar una copia.** Para MercaStats:

```bash
kubectl scale -n mercastats deploy/mercastats --replicas=0
```

Crea un pod temporal con los dos volúmenes (cambia `mercastats-data` y `mercastats-backup` si tu release no se llama `mercastats`):

```yaml
# restore.yaml
apiVersion: v1
kind: Pod
metadata:
  name: mercastats-restore
  namespace: mercastats
spec:
  restartPolicy: Never
  securityContext:
    runAsNonRoot: true
    runAsUser: 1000
    runAsGroup: 1000
    fsGroup: 1000
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: restore
      image: ghcr.io/neoruben/mercastats:0.2.0
      command: ["sleep", "3600"]
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
      volumeMounts:
        - name: data
          mountPath: /data
        - name: backup
          mountPath: /backups
          readOnly: true
  volumes:
    - name: data
      persistentVolumeClaim:
        claimName: mercastats-data
    - name: backup
      persistentVolumeClaim:
        claimName: mercastats-backup
```

```bash
kubectl apply -f restore.yaml
```

Copia la copia elegida sobre la base de datos (con MercaStats parado no debe haber archivos `mercastats.db-wal` ni `mercastats.db-shm` en `/data`):

```bash
kubectl exec -n mercastats mercastats-restore -- sh -c 'cp /backups/mercastats-20261126-120616Z.db /data/mercastats.db.new && mv /data/mercastats.db.new /data/mercastats.db'
```

Borra el pod temporal y vuelve a arrancar MercaStats:

```bash
kubectl delete pod -n mercastats mercastats-restore
```

```bash
kubectl scale -n mercastats deploy/mercastats --replicas=1
```

### OpenShift

No hace falta nada especial: el chart detecta la API `security.openshift.io` y quita `runAsUser`, `runAsGroup` y `fsGroup` para que la SCC `restricted-v2` asigne el UID (`global.compatibility.openshift.adaptSecurityContext`). Para publicar la web, un Ingress funciona (OpenShift crea la Route), o crea la Route con `extraObjects`. `volumePermissions` necesita root y no funciona con la SCC por defecto.

## Seguridad

Por defecto el pod:

- funciona como el usuario `1000:1000`, nunca como root (`runAsNonRoot`), así que el entrypoint de la imagen no cambia dueños: los permisos del volumen los da `fsGroup`;
- tiene el sistema de archivos de solo lectura, sin capacidades de Linux, sin escalada de privilegios y con el perfil seccomp `RuntimeDefault` (cumple el Pod Security Standard `restricted`);
- no monta el token del ServiceAccount (MercaStats no usa la API de Kubernetes) ni recibe las variables de los Services del namespace.

`/tmp` es un `emptyDir` porque la JVM y SQLite lo necesitan para escribir.

## Limitaciones

- **Una sola réplica.** La base de datos es un archivo SQLite: dos pods a la vez la corromperían. Por eso `replicaCount` solo admite `0` o `1`, la estrategia es `Recreate` y no hay HorizontalPodAutoscaler ni PodDisruptionBudget (con una réplica, un PDB bloquearía el vaciado de nodos).
- MercaStats se sirve en la raíz (`/`), no en una subruta.

## Desinstalar

```bash
helm uninstall mercastats -n mercastats
```

Los PVC **se conservan** (`persistence.data.retain: true`) para no perder la base de datos por accidente. Si vuelves a instalar con el mismo nombre de release, Helm los reutiliza y MercaStats arranca con los datos que tenía (y con la contraseña de `admin` de entonces). Si de verdad quieres borrarlos:

```bash
kubectl delete pvc -n mercastats -l app.kubernetes.io/instance=mercastats
```

## Desarrollo del chart

```bash
helm lint --strict charts/mercastats
```

```bash
helm template mercastats charts/mercastats -f charts/mercastats/ci/full-values.yaml
```

Los archivos de `ci/` son escenarios de prueba para [chart-testing](https://github.com/helm/chart-testing). Si cambias el chart, sube `version` en `Chart.yaml`; si cambia la versión de la imagen por defecto, sube también `appVersion`.

## Parámetros

Todos los valores, con comentarios, están en [values.yaml](values.yaml). Cada opción de `config` corresponde a una variable de entorno de la imagen, documentada en [docs/DOCKER.md](../../docs/DOCKER.md#5-variables-de-entorno).

<!-- PARAMS -->
### Parámetros globales

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `global.imageRegistry` | `""` | Registro que sustituye al de todas las imágenes del chart (mirrors, air-gapped). |
| `global.imagePullSecrets` | `[]` | Secretos de descarga de imágenes añadidos a todos los pods. Admite nombres (`- my-secret`) u objetos (`- name: my-secret`). |
| `global.compatibility.openshift.adaptSecurityContext` | `"auto"` | Adapta los securityContext a OpenShift (quita runAsUser, runAsGroup y fsGroup para que la SCC asigne el UID). `auto` lo detecta por la API `security.openshift.io`; `force` siempre; `disabled` nunca. |

### Parámetros comunes

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `nameOverride` | `""` | Sustituye el nombre del chart en los recursos. |
| `fullnameOverride` | `""` | Sustituye el nombre completo de los recursos. |
| `namespaceOverride` | `""` | Despliega en este namespace en lugar del de la release. |
| `commonLabels` | `{}` | Etiquetas añadidas a todos los recursos. |
| `commonAnnotations` | `{}` | Anotaciones añadidas a todos los recursos. |

### Imagen

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `image.registry` | `"ghcr.io"` | Registro de la imagen. |
| `image.repository` | `"neoruben/mercastats"` | Repositorio de la imagen. |
| `image.tag` | `""` | Etiqueta de la imagen. Vacío = `appVersion` del Chart.yaml. |
| `image.digest` | `""` | Digest de la imagen (`sha256:...`). Si se pone, tiene prioridad sobre la etiqueta. |
| `image.pullPolicy` | `"IfNotPresent"` | Política de descarga de la imagen. |
| `imagePullSecrets` | `[]` | Secretos de descarga de imágenes (además de `global.imagePullSecrets`). |

### Configuración de MercaStats

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `config.timezone` | `"Europe/Madrid"` | Zona horaria (`TZ`). |
| `config.logLevel` | `"INFO"` | Nivel de logs (`LOG_LEVEL`): DEBUG, INFO, WARN, ERROR. |
| `config.host` | `"0.0.0.0"` | Interfaz de escucha (`HOST`). Usa `"::"` en clústeres solo IPv6. |
| `config.baseUrl` | `""` | URL pública (`BASE_URL`). Vacío = se deduce del primer host del Ingress (https si tiene TLS). Se usa para la redirección de Gmail OAuth y para `config.auth.cookieSecure: auto`. |
| `config.javaOpts` | `ver values.yaml` | Opciones de la JVM (`JAVA_OPTS`). Por defecto la memoria se ajusta al límite del contenedor (`resources.limits.memory`). |
| `config.inbox.pollSeconds` | `30` | Segundos entre escaneos de la carpeta vigilada (`INBOX_POLL_SECONDS`, 0 = desactivada). |
| `config.inbox.defaultUser` | `""` | Usuario que recibe los PDFs de la raíz de la carpeta (`INBOX_DEFAULT_USER`). Vacío = `config.auth.adminUser`. |
| `config.auth.mode` | `"local"` | Modo de autenticación (`AUTH_MODE`): `local`, `proxy` o `none`. |
| `config.auth.adminUser` | `"admin"` | Usuario administrador (`ADMIN_USER`). |
| `config.auth.adminPassword` | `""` | Contraseña inicial del administrador (`ADMIN_PASSWORD`). Solo cuenta en el PRIMER arranque; vacía = se genera una y aparece en los logs. Se guarda en un Secret. Mejor usa `existingSecret` en producción. |
| `config.auth.allowRegistration` | `false` | Permite el registro público desde el login (`ALLOW_REGISTRATION`). |
| `config.auth.cookieSecure` | `"auto"` | Cookie de sesión con atributo Secure (`COOKIE_SECURE`): `true`, `false` o `auto` (true si la URL pública empieza por https://). |
| `config.auth.sessionTtlDays` | `30` | Duración de las sesiones en días (`SESSION_TTL_DAYS`). |
| `config.auth.proxy.userHeader` | `"X-authentik-username"` | Cabecera con el usuario (`PROXY_USER_HEADER`). Tu proxy debe sobrescribirla siempre. |
| `config.auth.proxy.emailHeader` | `"X-authentik-email"` | Cabecera con el correo (`PROXY_EMAIL_HEADER`). |
| `config.auth.proxy.nameHeader` | `"X-authentik-name"` | Cabecera con el nombre visible (`PROXY_NAME_HEADER`). |
| `config.auth.proxy.trustedProxies` | `[]` | IPs o rangos CIDR desde los que se aceptan las cabeceras (`TRUSTED_PROXIES`). Obligatorio con `mode: proxy`. En Kubernetes es la IP de los pods del ingress controller / outpost (o su rango), NO la del Service. |
| `config.imap.enabled` | `false` | Activa la sincronización por IMAP con contraseña de aplicación. |
| `config.imap.host` | `""` | Servidor IMAP (`IMAP_HOST`). |
| `config.imap.port` | `""` | Puerto IMAP (`IMAP_PORT`). Vacío = 993 con SSL, 143 sin SSL. |
| `config.imap.ssl` | `true` | `true` = IMAPS (TLS directo); `false` = STARTTLS obligatorio (`IMAP_SSL`). |
| `config.imap.user` | `""` | Usuario / correo IMAP (`IMAP_USER`). |
| `config.imap.password` | `""` | Contraseña de aplicación (`IMAP_PASSWORD`). Se guarda en un Secret. |
| `config.imap.folder` | `"INBOX"` | Carpeta o etiqueta de Gmail (`IMAP_FOLDER`). |
| `config.imap.fromFilter` | `"ticket_digital@mail.mercadona.com"` | Remitente buscado (`IMAP_FROM_FILTER`). |
| `config.imap.syncIntervalMinutes` | `60` | Minutos entre sincronizaciones (`IMAP_SYNC_INTERVAL_MINUTES`, 0 = solo manual). |
| `config.imap.targetUser` | `""` | Usuario que recibe los tickets (`IMAP_TARGET_USER`). Vacío = `config.auth.adminUser`. |
| `config.gmail.enabled` | `false` | Activa la sincronización por Gmail OAuth 2.0. |
| `config.gmail.clientId` | `""` | Client ID OAuth (`GMAIL_CLIENT_ID`). |
| `config.gmail.clientSecret` | `""` | Client Secret OAuth (`GMAIL_CLIENT_SECRET`). Se guarda en un Secret. |
| `config.gmail.redirectUri` | `""` | URI de redirección (`GMAIL_REDIRECT_URI`). Vacío = `<baseUrl>/api/gmail/oauth/callback`. |
| `config.gmail.syncIntervalMinutes` | `60` | Minutos entre sincronizaciones (`GMAIL_SYNC_INTERVAL_MINUTES`, 0 = solo manual). |
| `existingSecret` | `""` | Secret ya existente con los valores sensibles. Si se indica, el chart no crea el suyo y `adminPassword`, `imap.password` y `gmail.clientSecret` se ignoran. |
| `secretKeys.adminPassword` | `"ADMIN_PASSWORD"` | Clave con la contraseña inicial del administrador. |
| `secretKeys.imapPassword` | `"IMAP_PASSWORD"` | Clave con la contraseña IMAP. |
| `secretKeys.gmailClientSecret` | `"GMAIL_CLIENT_SECRET"` | Clave con el Client Secret de Gmail. |
| `extraEnv` | `[]` | Variables de entorno adicionales (lista de EnvVar; admite plantillas). Tienen prioridad sobre las generadas por el chart. |
| `extraEnvFrom` | `[]` | Fuentes adicionales de variables de entorno (ConfigMaps / Secrets). |

### Despliegue

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `replicaCount` | `1` | Réplicas. MercaStats usa SQLite y solo admite 1 (0 para pararlo). |
| `updateStrategy` | `{"type": "Recreate"}` | Estrategia del Deployment. `Recreate` evita dos pods con la misma base de datos. |
| `revisionHistoryLimit` | `3` | Número de ReplicaSets antiguos que se conservan. |
| `deploymentAnnotations` | `{}` | Anotaciones del Deployment. |
| `deploymentLabels` | `{}` | Etiquetas del Deployment. |
| `podAnnotations` | `{}` | Anotaciones de los pods. |
| `podLabels` | `{}` | Etiquetas de los pods. |
| `containerPort` | `8080` | Puerto HTTP del contenedor (`PORT`). |
| `command` | `[]` | Sustituye el comando del contenedor. |
| `args` | `[]` | Sustituye los argumentos del contenedor. |
| `podSecurityContext` | `ver values.yaml` | securityContext del pod. Con un UID distinto de 0 el entrypoint de la imagen no cambia los dueños; los permisos de escritura sobre el volumen los da `fsGroup`. |
| `securityContext` | `ver values.yaml` | securityContext del contenedor. |
| `resources` | `ver values.yaml` | Recursos del contenedor. La JVM usa el 60 % del límite de memoria como heap. |
| `startupProbe.enabled` | `true` | Activa la startupProbe (da margen al arranque de la JVM, p. ej. en Raspberry Pi). |
| `livenessProbe.enabled` | `true` | Activa la livenessProbe. |
| `readinessProbe.enabled` | `true` | Activa la readinessProbe. |
| `customStartupProbe` | `{}` | Sustituye por completo la startupProbe. |
| `customLivenessProbe` | `{}` | Sustituye por completo la livenessProbe. |
| `customReadinessProbe` | `{}` | Sustituye por completo la readinessProbe. |
| `lifecycle` | `{}` | Hooks de ciclo de vida del contenedor. |
| `terminationGracePeriodSeconds` | `30` | Segundos de gracia al parar el pod. |
| `nodeSelector` | `{}` | Selector de nodos. |
| `tolerations` | `[]` | Tolerancias. |
| `affinity` | `{}` | Afinidad (p. ej. fijar el pod a un nodo con almacenamiento local). |
| `topologySpreadConstraints` | `[]` | Restricciones de reparto entre zonas / nodos. |
| `priorityClassName` | `""` | PriorityClass de los pods. |
| `schedulerName` | `""` | Scheduler alternativo. |
| `runtimeClassName` | `""` | RuntimeClass (p. ej. gvisor, kata). |
| `hostAliases` | `[]` | Entradas extra de /etc/hosts. |
| `dnsPolicy` | `""` | Política DNS del pod. |
| `dnsConfig` | `{}` | Configuración DNS del pod. |
| `enableServiceLinks` | `false` | Inyecta variables de entorno de los Services del namespace (desactivado: no se usan). |
| `initContainers` | `[]` | Contenedores init adicionales (admite plantillas). |
| `sidecars` | `[]` | Contenedores sidecar adicionales (admite plantillas). |
| `extraVolumes` | `[]` | Volúmenes adicionales del pod (admite plantillas). |
| `extraVolumeMounts` | `[]` | Montajes adicionales del contenedor principal (admite plantillas). |
| `volumePermissions.enabled` | `false` | Activa el contenedor init de permisos. |
| `volumePermissions.image` | `ver values.yaml` | Imagen del contenedor init. Vacío = la propia imagen de MercaStats. |
| `volumePermissions.resources` | `{}` | Recursos del contenedor init. |

### ServiceAccount

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `serviceAccount.create` | `true` | Crea un ServiceAccount propio. |
| `serviceAccount.name` | `""` | Nombre del ServiceAccount. Vacío = nombre completo de la release (o `default` si no se crea). |
| `serviceAccount.annotations` | `{}` | Anotaciones del ServiceAccount (p. ej. IRSA / Workload Identity). |
| `serviceAccount.labels` | `{}` | Etiquetas del ServiceAccount. |
| `serviceAccount.automount` | `false` | Monta el token en el ServiceAccount (MercaStats no usa la API de Kubernetes). |
| `automountServiceAccountToken` | `false` | Monta el token del ServiceAccount en el pod. |

### Persistencia

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `persistence.data.enabled` | `true` | Persiste /data. Si es `false` se usa un emptyDir y los datos SE PIERDEN al reiniciar el pod. |
| `persistence.data.existingClaim` | `""` | PVC ya existente. |
| `persistence.data.volume` | `{}` | Volumen propio en lugar de un PVC (p. ej. `hostPath: {path: /srv/mercastats}` o `nfs: {server: nas.lan, path: /mercastats}`). |
| `persistence.data.storageClass` | `""` | StorageClass. Vacío = la por defecto del clúster; `"-"` = sin StorageClass. |
| `persistence.data.accessModes` | `["ReadWriteOnce"]` | Modos de acceso del PVC. |
| `persistence.data.size` | `"1Gi"` | Tamaño del PVC. |
| `persistence.data.selector` | `{}` | Selector de PV. |
| `persistence.data.volumeName` | `""` | Enlaza con un PV concreto. |
| `persistence.data.dataSource` | `{}` | Crea el PVC a partir de un snapshot u otro PVC. |
| `persistence.data.subPath` | `""` | Ruta dentro del volumen que se monta en /data. |
| `persistence.data.annotations` | `{}` | Anotaciones del PVC. |
| `persistence.data.labels` | `{}` | Etiquetas del PVC. |
| `persistence.data.retain` | `true` | Conserva el PVC al desinstalar (`helm.sh/resource-policy: keep`). |
| `persistence.inbox.enabled` | `false` | Monta un volumen propio en /data/inbox. |
| `persistence.inbox.existingClaim` | `""` | PVC ya existente. |
| `persistence.inbox.volume` | `{}` | Volumen propio en lugar de un PVC (p. ej. `nfs: {...}`). |
| `persistence.inbox.storageClass` | `""` | StorageClass. Vacío = la por defecto; `"-"` = sin StorageClass. |
| `persistence.inbox.accessModes` | `["ReadWriteOnce"]` | Modos de acceso del PVC. |
| `persistence.inbox.size` | `"1Gi"` | Tamaño del PVC. |
| `tmpVolume.sizeLimit` | `"256Mi"` | Tamaño máximo de /tmp. |
| `tmpVolume.medium` | `""` | `Memory` para usar tmpfs. |

### Service

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `service.enabled` | `true` | Crea el Service. |
| `service.type` | `"ClusterIP"` | Tipo: ClusterIP, NodePort o LoadBalancer. |
| `service.port` | `8080` | Puerto del Service. |
| `service.nodePort` | `""` | NodePort fijo (solo NodePort / LoadBalancer). |
| `service.portName` | `"http"` | Nombre del puerto. |
| `service.appProtocol` | `"http"` | appProtocol del puerto. |
| `service.clusterIP` | `""` | clusterIP fijo (o `None`). |
| `service.loadBalancerIP` | `""` | IP del LoadBalancer (si tu proveedor lo admite). |
| `service.loadBalancerClass` | `""` | Clase de LoadBalancer. |
| `service.loadBalancerSourceRanges` | `[]` | Rangos de origen permitidos en el LoadBalancer. |
| `service.externalTrafficPolicy` | `""` | `Local` conserva la IP de origen del cliente. |
| `service.internalTrafficPolicy` | `""` | Política de tráfico interno. |
| `service.ipFamilyPolicy` | `""` | SingleStack, PreferDualStack o RequireDualStack. |
| `service.ipFamilies` | `[]` | Familias IP (IPv4, IPv6). |
| `service.externalIPs` | `[]` | IPs externas. |
| `service.sessionAffinity` | `""` | Afinidad de sesión. |
| `service.annotations` | `{}` | Anotaciones (p. ej. MetalLB, nube). |
| `service.labels` | `{}` | Etiquetas. |

### Ingress

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `ingress.enabled` | `false` | Crea un Ingress. |
| `ingress.className` | `""` | IngressClass (nginx, traefik, haproxy...). Vacío = la por defecto del clúster. |
| `ingress.annotations` | `{}` | Anotaciones del Ingress (cert-manager, auth, tamaño máximo de subida...). |
| `ingress.labels` | `{}` | Etiquetas del Ingress. |
| `ingress.hosts` | `ver values.yaml` | Hosts y rutas. MercaStats se sirve en la raíz (`/`). |
| `ingress.tls` | `[]` | TLS del Ingress. |

### Gateway API

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `httpRoute.enabled` | `false` | Crea un HTTPRoute (Gateway API v1). |
| `httpRoute.annotations` | `{}` | Anotaciones. |
| `httpRoute.labels` | `{}` | Etiquetas. |
| `httpRoute.parentRefs` | `[]` | Gateways a los que se engancha la ruta. |
| `httpRoute.hostnames` | `[]` | Hostnames de la ruta. |
| `httpRoute.rules` | `ver values.yaml` | Reglas. El backend (este Service) se añade solo si no se indica `backendRefs`. |

### NetworkPolicy

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `networkPolicy.enabled` | `false` | Crea una NetworkPolicy. |
| `networkPolicy.ingress.from` | `[]` | Orígenes permitidos hacia el puerto HTTP. Vacío = cualquiera. Con `auth.mode: proxy` conviene limitarlo al ingress controller / outpost. |
| `networkPolicy.ingress.extraRules` | `[]` | Reglas de entrada adicionales. |
| `networkPolicy.egress.enabled` | `false` | Restringe también la salida (DNS + puertos de `allowedPorts` + `extraRules`). |
| `networkPolicy.egress.allowedPorts` | `[443, 993, 143]` | Puertos de salida permitidos a cualquier destino (HTTPS para Gmail, IMAP). |
| `networkPolicy.egress.extraRules` | `[]` | Reglas de salida adicionales. |

### Copias de seguridad

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `backup.enabled` | `false` | Activa las copias de seguridad programadas. |
| `backup.schedule` | `"0 3 * * *"` | Programación cron. |
| `backup.timeZone` | `""` | Zona horaria de la programación (Kubernetes 1.27+). Vacío = la del controlador. |
| `backup.keep` | `7` | Número de copias que se conservan. |
| `backup.suspend` | `false` | Suspende el CronJob sin borrarlo. |
| `backup.concurrencyPolicy` | `"Forbid"` | Qué hacer si coincide con una copia aún en curso. |
| `backup.successfulJobsHistoryLimit` | `3` | Jobs correctos que se conservan. |
| `backup.failedJobsHistoryLimit` | `1` | Jobs fallidos que se conservan. |
| `backup.startingDeadlineSeconds` | `""` | Segundos de margen para lanzar una ejecución perdida. |
| `backup.backoffLimit` | `2` | Reintentos de cada Job. |
| `backup.activeDeadlineSeconds` | `600` | Tiempo máximo de cada Job en segundos. |
| `backup.ttlSecondsAfterFinished` | `86400` | Segundos que se conserva un Job terminado. |
| `backup.image` | `ver values.yaml` | Imagen con `sqlite3` y `sh`. |
| `backup.sameNodeAsApp` | `true` | Programa el Job en el mismo nodo que MercaStats (necesario con volúmenes ReadWriteOnce). |
| `backup.mountInApp` | `true` | Monta también el volumen de copias, en solo lectura, en /backups del pod de MercaStats. Así el PVC se enlaza al instalar (con `WaitForFirstConsumer` no se enlazaría hasta la primera copia y `helm --wait` o Argo CD se quedarían esperando) y las copias se pueden ver con `kubectl exec`. |
| `backup.podAnnotations` | `{}` | Anotaciones de los pods de copia. |
| `backup.podLabels` | `{}` | Etiquetas de los pods de copia. |
| `backup.resources` | `{}` | Recursos del contenedor de copia. |
| `backup.nodeSelector` | `{}` | Selector de nodos del Job. |
| `backup.tolerations` | `[]` | Tolerancias del Job. |
| `backup.affinity` | `{}` | Afinidad del Job. Si se indica, sustituye a la de `sameNodeAsApp`. |
| `backup.persistence.existingClaim` | `""` | PVC ya existente. |
| `backup.persistence.volume` | `{}` | Volumen propio (p. ej. `nfs: {...}`). |
| `backup.persistence.storageClass` | `""` | StorageClass. Vacío = la por defecto; `"-"` = sin StorageClass. |
| `backup.persistence.accessModes` | `["ReadWriteOnce"]` | Modos de acceso del PVC. |
| `backup.persistence.size` | `"2Gi"` | Tamaño del PVC de copias. |
| `backup.persistence.retain` | `true` | Conserva el PVC de copias al desinstalar. |

### Pruebas

| Parámetro | Por defecto | Descripción |
| --- | --- | --- |
| `tests.enabled` | `true` | Crea el pod de `helm test`, que comprueba `GET /api/health`. |
| `tests.image` | `ver values.yaml` | Imagen del test (necesita `wget`). Vacío = la propia imagen de MercaStats. |
| `extraObjects` | `[]` | Manifiestos adicionales que se despliegan con la release (admite plantillas). |
