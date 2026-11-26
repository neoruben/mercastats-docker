{{/* vim: set filetype=mustache: */}}

{{/*
Nombre del chart.
*/}}
{{- define "mercastats.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Nombre completo. Se trunca a 63 caracteres (límite de los nombres DNS).
*/}}
{{- define "mercastats.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Namespace de los recursos.
*/}}
{{- define "mercastats.namespace" -}}
{{- default .Release.Namespace .Values.namespaceOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Nombre y versión del chart para la etiqueta helm.sh/chart.
*/}}
{{- define "mercastats.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Etiquetas de selección (inmutables: no añadir nada que cambie entre versiones).
*/}}
{{- define "mercastats.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mercastats.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Etiquetas comunes recomendadas.
*/}}
{{- define "mercastats.labels" -}}
helm.sh/chart: {{ include "mercastats.chart" . }}
{{ include "mercastats.selectorLabels" . }}
app.kubernetes.io/version: {{ include "mercastats.imageTag" . | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: mercastats
{{- with .Values.commonLabels }}
{{ include "mercastats.tplvalues.render" (dict "value" . "context" $) }}
{{- end }}
{{- end }}

{{/*
Metadatos estándar: nombre, namespace, etiquetas y anotaciones.
Uso: include "mercastats.metadata" (dict "context" $ "name" "x" "component" "y" "labels" dict "annotations" dict)
*/}}
{{- define "mercastats.metadata" -}}
{{- $ctx := .context -}}
name: {{ .name | default (include "mercastats.fullname" $ctx) }}
namespace: {{ include "mercastats.namespace" $ctx }}
labels:
  {{- include "mercastats.labels" $ctx | nindent 2 }}
  {{- with .component }}
  app.kubernetes.io/component: {{ . }}
  {{- end }}
  {{- with .labels }}
  {{- include "mercastats.tplvalues.render" (dict "value" . "context" $ctx) | nindent 2 }}
  {{- end }}
{{- $annotations := merge (deepCopy (.annotations | default dict)) (deepCopy ($ctx.Values.commonAnnotations | default dict)) }}
{{- with $annotations }}
annotations:
  {{- include "mercastats.tplvalues.render" (dict "value" . "context" $ctx) | nindent 2 }}
{{- end }}
{{- end }}

{{/*
Renderiza un valor que puede contener plantillas.
Uso: include "mercastats.tplvalues.render" (dict "value" .Values.x "context" $)
*/}}
{{- define "mercastats.tplvalues.render" -}}
{{- if typeIs "string" .value }}
{{- tpl .value .context }}
{{- else }}
{{- tpl (.value | toYaml) .context }}
{{- end }}
{{- end }}

{{/*
Etiqueta efectiva de la imagen principal.
*/}}
{{- define "mercastats.imageTag" -}}
{{- .Values.image.tag | default .Chart.AppVersion | toString }}
{{- end }}

{{/*
Referencia completa de una imagen, respetando global.imageRegistry y digest.
Uso: include "mercastats.image" (dict "image" .Values.image "context" $ "defaultTag" "x")
*/}}
{{- define "mercastats.image" -}}
{{- $registry := .context.Values.global.imageRegistry | default .image.registry -}}
{{- $repository := .image.repository -}}
{{- $tag := .image.tag | default .defaultTag | toString -}}
{{- $ref := ternary $repository (printf "%s/%s" $registry $repository) (empty $registry) -}}
{{- if .image.digest }}
{{- printf "%s@%s" $ref .image.digest }}
{{- else }}
{{- printf "%s:%s" $ref $tag }}
{{- end }}
{{- end }}

{{/*
Imagen principal de MercaStats.
*/}}
{{- define "mercastats.mainImage" -}}
{{- include "mercastats.image" (dict "image" .Values.image "context" . "defaultTag" .Chart.AppVersion) }}
{{- end }}

{{/*
Imagen de un componente auxiliar que, si no se define, reutiliza la principal.
Uso: include "mercastats.auxImage" (dict "image" .Values.tests.image "context" $)
*/}}
{{- define "mercastats.auxImage" -}}
{{- if .image.repository }}
{{- include "mercastats.image" (dict "image" .image "context" .context "defaultTag" "latest") }}
{{- else }}
{{- include "mercastats.mainImage" .context }}
{{- end }}
{{- end }}

{{/*
Política de descarga de una imagen auxiliar.
*/}}
{{- define "mercastats.auxPullPolicy" -}}
{{- .image.pullPolicy | default .context.Values.image.pullPolicy }}
{{- end }}

{{/*
imagePullSecrets (global + del chart, sin duplicados). Admite cadenas u objetos.
*/}}
{{- define "mercastats.imagePullSecrets" -}}
{{- $secrets := list }}
{{- range (concat (.Values.global.imagePullSecrets | default list) (.Values.imagePullSecrets | default list)) }}
{{- $name := "" }}
{{- if typeIs "string" . }}
{{- $name = tpl . $ }}
{{- else if kindIs "map" . }}
{{- $name = tpl (get . "name" | toString) $ }}
{{- end }}
{{- if and $name (not (has $name $secrets)) }}
{{- $secrets = append $secrets $name }}
{{- end }}
{{- end }}
{{- if $secrets }}
imagePullSecrets:
{{- range $secrets }}
  - name: {{ . }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Nombre del ServiceAccount.
*/}}
{{- define "mercastats.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "mercastats.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Nombre del Secret con los valores sensibles.
*/}}
{{- define "mercastats.secretName" -}}
{{- if .Values.existingSecret }}
{{- tpl .Values.existingSecret . }}
{{- else }}
{{- include "mercastats.fullname" . }}
{{- end }}
{{- end }}

{{/*
Indica si hay que adaptar los securityContext a OpenShift ("true" o "").
*/}}
{{- define "mercastats.adaptForOpenShift" -}}
{{- $mode := .Values.global.compatibility.openshift.adaptSecurityContext | default "auto" | toString }}
{{- if eq $mode "force" }}
{{- true }}
{{- else if and (eq $mode "auto") (.Capabilities.APIVersions.Has "security.openshift.io/v1") }}
{{- true }}
{{- end }}
{{- end }}

{{/*
Renderiza un securityContext quitando los IDs fijos cuando se adapta a OpenShift.
Uso: include "mercastats.securityContext" (dict "secContext" .Values.podSecurityContext "context" $)
*/}}
{{- define "mercastats.securityContext" -}}
{{- $sc := deepCopy (.secContext | default dict) }}
{{- if include "mercastats.adaptForOpenShift" .context }}
{{- $sc = omit $sc "runAsUser" "runAsGroup" "fsGroup" "seLinuxOptions" }}
{{- end }}
{{- with $sc }}
{{- toYaml . }}
{{- end }}
{{- end }}

{{/*
URL pública efectiva: config.baseUrl o el primer host del Ingress.
*/}}
{{- define "mercastats.baseUrl" -}}
{{- if .Values.config.baseUrl }}
{{- tpl .Values.config.baseUrl . | trimSuffix "/" }}
{{- else if and .Values.ingress.enabled .Values.ingress.hosts }}
{{- $host := tpl ((first .Values.ingress.hosts).host | default "" | toString) . }}
{{- if $host }}
{{- $tls := false }}
{{- range .Values.ingress.tls }}
{{- range .hosts }}
{{- if eq (tpl (toString .) $) $host }}
{{- $tls = true }}
{{- end }}
{{- end }}
{{- end }}
{{- printf "%s://%s" (ternary "https" "http" $tls) $host }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Valor efectivo de COOKIE_SECURE.
*/}}
{{- define "mercastats.cookieSecure" -}}
{{- $v := .Values.config.auth.cookieSecure }}
{{- if or (eq (toString $v) "auto") (eq (toString $v) "") }}
{{- hasPrefix "https://" (include "mercastats.baseUrl" .) }}
{{- else }}
{{- toString $v | lower }}
{{- end }}
{{- end }}

{{/*
Nombre del PVC de un volumen de persistencia.
Uso: include "mercastats.claimName" (dict "persistence" .Values.persistence.data "suffix" "data" "context" $)
*/}}
{{- define "mercastats.claimName" -}}
{{- if .persistence.existingClaim }}
{{- tpl .persistence.existingClaim .context }}
{{- else }}
{{- printf "%s-%s" (include "mercastats.fullname" .context) .suffix | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Fuente de un volumen de persistencia (PVC, volumen propio o emptyDir).
Uso: include "mercastats.volumeSource" (dict "persistence" .Values.persistence.data "suffix" "data" "context" $)
*/}}
{{- define "mercastats.volumeSource" -}}
{{- if .persistence.volume }}
{{- include "mercastats.tplvalues.render" (dict "value" .persistence.volume "context" .context) }}
{{- else }}
persistentVolumeClaim:
  claimName: {{ include "mercastats.claimName" . }}
{{- end }}
{{- end }}

{{/*
Indica si el chart debe crear el PVC de un volumen de persistencia ("true" o "").
*/}}
{{- define "mercastats.createClaim" -}}
{{- if and .enabled (not .existingClaim) (not .volume) }}
{{- true }}
{{- end }}
{{- end }}

{{/*
Sonda HTTP sobre /api/health.
Uso: include "mercastats.probe" (dict "probe" .Values.livenessProbe "custom" .Values.customLivenessProbe "context" $)
*/}}
{{- define "mercastats.probe" -}}
{{- if .custom }}
{{- include "mercastats.tplvalues.render" (dict "value" .custom "context" .context) }}
{{- else }}
httpGet:
  path: /api/health
  port: http
{{- toYaml (omit .probe "enabled") | nindent 0 }}
{{- end }}
{{- end }}

{{/*
Comprobaciones de los valores: fallan con un mensaje claro antes de desplegar.
*/}}
{{- define "mercastats.validateValues" -}}
{{- $errors := list }}
{{- if gt (int .Values.replicaCount) 1 }}
{{- $errors = append $errors "replicaCount: MercaStats usa SQLite y solo admite 1 réplica (o 0 para pararlo)." }}
{{- end }}
{{- $mode := .Values.config.auth.mode | toString | lower }}
{{- if not (has $mode (list "local" "proxy" "none")) }}
{{- $errors = append $errors (printf "config.auth.mode: '%s' no es válido. Usa local, proxy o none." $mode) }}
{{- end }}
{{- if and (eq $mode "proxy") (empty .Values.config.auth.proxy.trustedProxies) }}
{{- $errors = append $errors "config.auth.proxy.trustedProxies: con auth.mode=proxy indica la IP o el rango CIDR de los pods de tu proxy (ingress controller / outpost). Por defecto solo se confía en 127.0.0.1 y todas las peticiones recibirían 403." }}
{{- end }}
{{- if contains "," (.Values.config.auth.proxy.userHeader | toString) }}
{{- $errors = append $errors "config.auth.proxy.userHeader: solo admite una cabecera, no una lista." }}
{{- end }}
{{- if .Values.config.imap.enabled }}
{{- if or (empty .Values.config.imap.host) (empty .Values.config.imap.user) }}
{{- $errors = append $errors "config.imap: con imap.enabled=true hacen falta imap.host e imap.user." }}
{{- end }}
{{- if and (empty .Values.config.imap.password) (empty .Values.existingSecret) }}
{{- $errors = append $errors "config.imap.password: con imap.enabled=true indica la contraseña o un existingSecret con la clave secretKeys.imapPassword." }}
{{- end }}
{{- end }}
{{- if .Values.config.gmail.enabled }}
{{- if empty .Values.config.gmail.clientId }}
{{- $errors = append $errors "config.gmail.clientId: con gmail.enabled=true hace falta el Client ID." }}
{{- end }}
{{- if and (empty .Values.config.gmail.clientSecret) (empty .Values.existingSecret) }}
{{- $errors = append $errors "config.gmail.clientSecret: con gmail.enabled=true indica el Client Secret o un existingSecret con la clave secretKeys.gmailClientSecret." }}
{{- end }}
{{- if and (empty .Values.config.gmail.redirectUri) (empty (include "mercastats.baseUrl" .)) }}
{{- $errors = append $errors "config.gmail: indica config.baseUrl, config.gmail.redirectUri o activa el Ingress para construir la URI de redirección de OAuth." }}
{{- end }}
{{- end }}
{{- if and .Values.backup.enabled (not .Values.persistence.data.enabled) }}
{{- $errors = append $errors "backup.enabled: las copias necesitan persistence.data.enabled=true." }}
{{- end }}
{{- if $errors }}
{{- fail (printf "\n\nERROR en los valores del chart mercastats:\n  - %s\n" (join "\n  - " $errors)) }}
{{- end }}
{{- end }}
