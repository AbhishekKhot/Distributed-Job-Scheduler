{{- define "kairos.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "kairos.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "kairos.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "kairos.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "kairos.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: kairos
{{- end }}

{{- define "kairos.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kairos.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
app.kubernetes.io/part-of: kairos
{{- end }}

{{- define "kairos.componentName" -}}
{{- printf "%s-%s" (include "kairos.fullname" .root) .component | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "kairos.serviceAccountName" -}}
{{- include "kairos.componentName" . }}
{{- end }}

{{- define "kairos.image" -}}
{{- $image := index .root.Values.images .component -}}
{{- printf "%s:%s" $image.repository .root.Values.images.tag }}
{{- end }}

{{- define "kairos.runtimeSecretName" -}}
{{- .Values.runtimeSecrets.existingSecret -}}
{{- end }}

{{- define "kairos.ownerSecretName" -}}
{{- .Values.ownerSecrets.existingSecret -}}
{{- end }}

{{- define "kairos.commonEnv" -}}
{{- if .Values.connections.springProfilesActive }}
- name: SPRING_PROFILES_ACTIVE
  value: {{ .Values.connections.springProfilesActive | quote }}
{{- end }}
- name: KAIROS_CONTROL_DB_URL
  value: {{ .Values.connections.controlDatabaseUrl | quote }}
{{- range $index, $url := .Values.connections.cellDatabaseUrls }}
- name: {{ printf "KAIROS_CELL_%d_DB_URL" $index }}
  value: {{ $url | quote }}
{{- end }}
- name: KAIROS_DB_USERNAME
  value: {{ .Values.connections.databaseUsername | quote }}
- name: KAIROS_KAFKA_BOOTSTRAP_SERVERS
  value: {{ .Values.connections.kafka.bootstrapServers | quote }}
- name: KAIROS_KAFKA_SECURITY_PROTOCOL
  value: {{ .Values.connections.kafka.securityProtocol | quote }}
- name: KAIROS_KAFKA_SASL_MECHANISM
  value: {{ .Values.connections.kafka.saslMechanism | quote }}
{{- if .Values.connections.kafka.trustCertificateFile }}
- name: KAIROS_KAFKA_TRUST_CERTIFICATE
  value: {{ printf "/var/run/secrets/kairos/%s" .Values.connections.kafka.trustCertificateFile | quote }}
{{- end }}
- name: KAIROS_REDIS_HOST
  value: {{ .Values.connections.redis.host | quote }}
- name: KAIROS_REDIS_PORT
  value: {{ .Values.connections.redis.port | quote }}
- name: KAIROS_REDIS_USERNAME
  value: {{ .Values.connections.redis.username | quote }}
- name: KAIROS_REDIS_TLS
  value: {{ .Values.connections.redis.tls | quote }}
{{- if .Values.connections.redis.trustCertificateFile }}
- name: KAIROS_REDIS_TRUST_CERTIFICATE
  value: {{ printf "/var/run/secrets/kairos/%s" .Values.connections.redis.trustCertificateFile | quote }}
{{- end }}
- name: KAIROS_KEK_URI
  value: {{ .Values.connections.kekUri | quote }}
{{- end }}

{{- define "kairos.secretVolume" -}}
- name: runtime-secrets
{{- if .root.Values.runtimeSecrets.csi.enabled }}
  csi:
    driver: {{ .root.Values.runtimeSecrets.csi.driver }}
    readOnly: true
    volumeAttributes:
      secretProviderClass: {{ include "kairos.componentName" . }}
{{- else }}
  secret:
    secretName: {{ include "kairos.runtimeSecretName" .root }}
{{- end }}
{{- end }}

{{- define "kairos.ownerSecretVolume" -}}
- name: owner-secrets
{{- if .Values.ownerSecrets.csi.enabled }}
  csi:
    driver: {{ .Values.ownerSecrets.csi.driver }}
    readOnly: true
    volumeAttributes:
      secretProviderClass: {{ include "kairos.fullname" . }}-owner
{{- else }}
  secret:
    secretName: {{ include "kairos.ownerSecretName" . }}
{{- end }}
{{- end }}

{{- define "kairos.securityContext" -}}
securityContext:
{{ toYaml .Values.containerSecurityContext | indent 2 }}
{{- end }}
