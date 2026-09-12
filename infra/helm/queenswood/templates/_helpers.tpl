{{/*
Common naming + labels for Queenswood templates.
*/}}

{{- define "queenswood.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "queenswood.labels" -}}
{{ include "queenswood.podLabels" . }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- end -}}

{{/*
Labels for a pod template, which is everything above except the chart
version. A pod template is immutable on a Job, so a label that moves
with the chart makes every version bump a rejected apply -- and the
Job names hash the pod spec, which these labels are not part of, so
the name does not move to absorb it. On a Deployment the same label
costs a rolling restart per bump. No selector references it: they
match on instance and component.
*/}}
{{- define "queenswood.podLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* Force Always when tag is `:latest` so re-pulls actually happen */}}
{{- define "queenswood.imagePullPolicy" -}}
{{- if eq .Values.image.tag "latest" -}}Always{{- else -}}{{ .Values.image.pullPolicy }}{{- end -}}
{{- end -}}

{{- define "queenswood.serviceFullname" -}}
{{- $svc := .svc -}}
{{- $root := .root -}}
{{- printf "%s-%s" $root.Release.Name $svc | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
OTLP traces endpoint. An explicit otel.endpoint wins; otherwise the
in-chart Jaeger's Service is used when enabled. Empty disables the SDK
rather than failing, so no fallback is required.
*/}}
{{- define "queenswood.otelEndpoint" -}}
{{- if .Values.otel.endpoint -}}
{{ .Values.otel.endpoint }}
{{- else if .Values.jaeger.enabled -}}
http://{{ .Release.Name }}-jaeger:4318/v1/traces
{{- end -}}
{{- end -}}

{{/*
Kafka bootstrap servers. When the in-chart broker is enabled its
Service is `<release>-kafka` on :9092; otherwise services point at
an external broker via kafka.bootstrapServers.
*/}}
{{- define "queenswood.kafkaBootstrapServers" -}}
{{- if .Values.kafka.enabled -}}
{{ .Release.Name }}-kafka:9092
{{- else -}}
{{- required "kafka.bootstrapServers required when kafka.enabled=false" .Values.kafka.bootstrapServers -}}
{{- end -}}
{{- end -}}

{{/*
FDB cluster file ConfigMap name (the operator writes one with this
name once the FoundationDBCluster CR is reconciled).
*/}}
{{- define "queenswood.fdbClusterConfigMap" -}}
{{- printf "%s-fdb-config" .Release.Name -}}
{{- end -}}

{{/*
The blobstore URL the restore Job reads from. The backup CR does not
use this: the operator assembles its own URL from the structured fields
of `blobStoreConfiguration`.

The container is deliberately NOT the one this cluster backs up to.
`fdb.restore.backupName` names the generation the recorded version
belongs to, and only `fdb.backup.backupName` names where this cluster
writes. Sharing one container across generations is what made a restore
unreliable: a rebuilt cluster numbers its versions again from near
zero, so `fdbbackup describe` -- which picks the highest restorable
version -- keeps answering with the OLDEST generation present, not the
newest. Empty falls back to the backup container, which is correct only
where there has been exactly one generation.

Plaintext by construction (port 80, secure_connection=0): FDB's
blobstore client has no usable trust store and the operator cannot pass
it a CA, so the files are encrypted instead. See fdb-backup.yaml.
*/}}
{{- define "queenswood.fdbRestoreUrl" -}}
{{- $b := .Values.fdb.backup -}}
{{- $r := .Values.fdb.restore -}}
{{- if and $r.version (not $r.backupName) -}}
{{- fail "fdb.restore.version was given without fdb.restore.backupName. A version names a point inside one backup container, and each cluster generation now has its own -- so a version alone cannot say which container to look in. Falling back to the container this cluster writes to would read a different generation and restore the wrong data, or silently find nothing. Record the generation alongside the version (gcp-fdb-export does), or clear the version to restore that container's latest point." -}}
{{- end -}}
{{- $container := $r.backupName | default $b.backupName -}}
{{- $params := printf "bucket=%s&region=%s&secure_connection=0" $b.bucket $b.region -}}
{{- /* The endpoint through the same helper the backup uses. Read
       straight off the value, it is empty unless somebody set it --
       the fallback to <release>-s3proxy lives in the helper -- so the
       restore built blobstore://key@:80/, with no host at all. Never
       caught because no restore has run: the Job finds a non-empty
       destination, exits 0, and the URL is never dialled. */}}
{{- $endpoint := include "queenswood.fdbBackupEndpoint" . -}}
{{- printf "blobstore://%s@%s:%v/%s?%s" $b.accessKeyId $endpoint $b.port $container $params -}}
{{- end -}}

{{/*
Names of the one-shot Jobs, defined once because four templates refer
to them and a name that disagrees is a gate that waits forever.

Each name carries a hash of that Job's own rendered pod spec. A pod
template is immutable once the Job exists, so anything changing what
the Job runs must also change its name or `helm upgrade` fails trying
to patch it -- and the spec is the only thing that tracks that without
being told. The previous suffix listed the inputs it knew about (image
tag, chart version, the restore and realm-import gates), which held
until a value outside the list reached a Job: `keycloak.baseUrl` did,
twice, and each time the release failed mid-upgrade and had to be
cleared by deleting the Job by hand.

A consequence worth knowing: a chart version bump that leaves both pod
specs untouched reuses the same names, because there is genuinely
nothing to replace. That only holds while nothing outside the spec
moves with the version -- `helm.sh/chart` in the pod template labels
did, and made every bump a rejected apply against an immutable
`spec.template`. Pod templates take `queenswood.podLabels` for that
reason.
*/}}
{{- define "queenswood.migratorJobName" -}}
{{- printf "%s-migrator-%s" .Release.Name (include "queenswood.migratorPodSpec" . | sha256sum | trunc 10) -}}
{{- end -}}

{{- define "queenswood.bootstrapJobName" -}}
{{- printf "%s-bootstrap-%s" .Release.Name (include "queenswood.bootstrapPodSpec" . | sha256sum | trunc 10) -}}
{{- end -}}

{{/*
Name of the FDB restore Job. Defined once because the migrator gates on
it: the restore Job renders it as its own name, and `wait-for-restore`
resolves the same string. Two derivations of one name is a gate that
waits for a Job nobody created -- which is exactly what happened when
the name became content-addressed here and stayed version-derived
there.

Content-addressed on (container, version) rather than version alone,
because a restore can now be requested with no version at all -- the
container's latest point -- and two such restores from different
generations must not collide on one name.
*/}}
{{- define "queenswood.fdbRestoreJobName" -}}
{{- $r := .Values.fdb.restore -}}
{{- printf "%s-fdb-restore-%s" .Release.Name (printf "%s|%s" ($r.backupName | default "") ($r.version | default "") | sha256sum | trunc 10) -}}
{{- end -}}

{{/*
Whether a restore is being asked for at all. The flag rather than the
target, so the target can be left behind as a record of where this
cluster's data came from without being live -- and so that switching it
off does not mean deleting keys, which is how a null `restore:` block
took the whole render down.
*/}}
{{- define "queenswood.fdbRestoreRequested" -}}
{{- if .Values.fdb.restore.enabled -}}true{{- end -}}
{{- end -}}

{{- /*
Which Keycloak this release talks to.

  dev       the bundled single-pod Keycloak, H2 and start-dev, for kind
  operator  an instance the Keycloak Operator manages, on `postgres`
  external  Keycloak lives elsewhere; nothing is rendered for it and
            baseUrl is supplied

Checked once so an unknown value stops the render rather than falling
through to whichever branch happens to be last.
*/ -}}
{{- define "queenswood.keycloakMode" -}}
{{- $m := .Values.keycloak.mode -}}
{{- if not (has $m (list "dev" "operator" "external")) -}}
{{- fail (printf "keycloak.mode %q is not one of dev, operator, external" $m) -}}
{{- end -}}
{{ $m }}
{{- end -}}

{{- /*
The Service both modes publish Keycloak on. The operator names it after
its `Keycloak` resource and the dev Deployment is given the same name,
so nothing that reaches Keycloak has to know which mode is running.
*/ -}}
{{- define "queenswood.keycloakServiceName" -}}
{{ .Release.Name }}-keycloak-service
{{- end -}}

{{- /*
Where this release reaches Keycloak from inside the cluster: bank-api's
JWKS fetch and admin REST calls, and the bootstrap Job. The dev instance
serves under /keycloak so the console SPA's same-origin proxy carries a
consistent prefix; the operator's serves at the root of its Service.
*/ -}}
{{- define "queenswood.keycloakBaseUrl" -}}
{{- if .Values.keycloak.baseUrl -}}
{{ .Values.keycloak.baseUrl }}
{{- else if eq (include "queenswood.keycloakMode" .) "dev" -}}
http://{{ include "queenswood.keycloakServiceName" . }}:8080/keycloak
{{- else if eq (include "queenswood.keycloakMode" .) "operator" -}}
http://{{ include "queenswood.keycloakServiceName" . }}:8080
{{- else -}}
{{ required "keycloak.baseUrl is required when keycloak.mode is external" .Values.keycloak.baseUrl }}
{{- end -}}
{{- end -}}

{{- /*
The platform's own published hostnames, comma-separated, which no
tenant's webhook address may point at. The three the Gateway
terminates on, because those are what a tenant could reach the
installation by.
*/ -}}
{{- define "queenswood.webhookPlatformHosts" -}}
{{- $hosts := list -}}
{{- range (list $.Values.gateway.host $.Values.gateway.consoleHost $.Values.gateway.apiHost) -}}
{{- if . -}}{{- $hosts = append $hosts . -}}{{- end -}}
{{- end -}}
{{ join "," (uniq $hosts) }}
{{- end -}}

{{- /*
Keycloak's published hostname. The subdomain is fixed -- Keycloak is at
`keycloak.` wherever it runs -- and the domain is supplied, because
where this installation lives is not the chart's to know.
*/ -}}
{{- define "queenswood.keycloakHost" -}}
{{ .Values.keycloak.host.subdomain }}.{{ required "keycloak.host.domain is required to publish Keycloak on a hostname" .Values.keycloak.host.domain }}
{{- end -}}

{{- /*
The issuer: what Keycloak embeds in every token's `iss` claim however
the request reached it, and therefore the exact string every verifier
compares against. Derived once here so the instance and the services
reading its tokens cannot disagree -- which is the whole reason
Keycloak lives in this chart rather than beside it.

A published hostname is the issuer when there is one, because that is
what a browser is redirected to; without one, everything that reads a
token is in this cluster and the Service is. Supplying a domain
therefore moves the issuer, which is intended -- tokens minted under
the old one stop verifying.
*/ -}}
{{- define "queenswood.keycloakIssuer" -}}
{{- if .Values.keycloak.issuer -}}
{{ .Values.keycloak.issuer }}
{{- else if and (eq (include "queenswood.keycloakMode" .) "operator") .Values.keycloak.host.domain -}}
https://{{ include "queenswood.keycloakHost" . }}
{{- else -}}
{{ include "queenswood.keycloakBaseUrl" . }}
{{- end -}}
{{- end -}}

{{- /*
The console's deployed origin, merged into the console client by the
realm import. Derived from the hostname the gateway already publishes the SPA
on, because the two cannot disagree: Keycloak validates the redirect_uri
against this list strictly, and a mismatch fails as a login refused with
`Invalid parameter: redirect_uri` rather than as anything naming a
hostname.

Empty where the console is not published, which is a console reached
through a port-forward. The deployed realm carries no localhost entries
of its own any more, so the dev bundle adds them at render time from
`keycloak.dev.consoleRedirectUris` instead.
*/ -}}
{{- define "queenswood.consoleRedirectUri" -}}
{{- if .Values.keycloak.consoleRedirectUri -}}
{{ .Values.keycloak.consoleRedirectUri }}
{{- else if and .Values.gateway.enabled .Values.gateway.consoleHost -}}
https://{{ .Values.gateway.consoleHost }}/*
{{- end -}}
{{- end -}}

{{- /*
The operator app's deployed origin, merged into the `queenswood-app`
client by the realm import, and the ops-realm mirror of the console's.

Supplied whole or not at all: unlike the console's there is no gateway
fallback, because this chart deploys no operator SPA and so publishes no
hostname to derive one from. Empty adds nothing, which is what an
environment with no operator app wants -- and is the default.
*/ -}}
{{- define "queenswood.opsRedirectUri" -}}
{{- if .Values.keycloak.opsRedirectUri -}}
{{ .Values.keycloak.opsRedirectUri }}
{{- end -}}
{{- end -}}

{{- /*
A committed realm with local redirect URIs appended to one client, for
the dev bundle only.

The realm pair a deployment imports carries none, so nothing widens a
deployed realm by accident. The dev bundle serves both SPAs on the
developer's machine, so it needs them -- and it imports the files at
startup rather than over the Admin API, so there is nowhere else to add
them.

Takes `realm` (the file's contents), `clientId` and `uris`. Parses,
rebuilds the client list with the URIs appended to the matching client,
and renders it back. Returns the input untouched when `uris` is empty,
so nothing is round-tripped for nothing.
*/ -}}
{{- define "queenswood.devRealmWithRedirects" -}}
{{- if .uris -}}
{{- $parsed := .realm | fromJson -}}
{{- $clientId := .clientId -}}
{{- $uris := .uris -}}
{{- $clients := list -}}
{{- range $parsed.clients -}}
{{- if eq .clientId $clientId -}}
{{- $clients = append $clients (merge (dict "redirectUris" (concat (default (list) .redirectUris) $uris | uniq)) .) -}}
{{- else -}}
{{- $clients = append $clients . -}}
{{- end -}}
{{- end -}}
{{ set $parsed "clients" $clients | toJson }}
{{- else -}}
{{ .realm }}
{{- end -}}
{{- end -}}

{{- /*
Where the browser reaches Keycloak, which has to be the issuer: the SPA
compares the `iss` of the token it is given against the authority it
asked, and a mismatch fails as a login that never completes rather than
as a configuration error.

Derived for the same reason the issuer is. It was a value nobody set,
and its default -- a localhost port-forward, correct for a bare
`helm install` and for nothing else -- is what a published console
served until something overrode it. Publishing Keycloak now moves this
with everything else.

Without a domain there is no published hostname, and the browser reaches
the SPA through a port-forward, so the same-origin proxy path is still
the only thing it can resolve.
*/ -}}
{{- define "queenswood.consoleKeycloakUrl" -}}
{{- if .Values.console.keycloakPublicUrl -}}
{{ .Values.console.keycloakPublicUrl }}
{{- else if and (eq (include "queenswood.keycloakMode" .) "operator") .Values.keycloak.host.domain -}}
{{ include "queenswood.keycloakIssuer" . }}
{{- else -}}
http://localhost:8081/keycloak
{{- end -}}
{{- end -}}

{{- /*
The Secret holding admin REST credentials for the bootstrap Job's
signing-key push. In operator mode that is the bootstrap admin, which
is the supplied one where there is one: the operator mints a Secret of
its own only while nothing supplies `spec.bootstrapAdmin`, so naming
its generated one unconditionally leaves this Job with a secretKeyRef
to an object that does not exist. The dev instance has neither, and the
dev credentials are used instead.
*/ -}}
{{- define "queenswood.keycloakAdminSecret" -}}
{{- if .Values.keycloak.adminSecret.name -}}
{{ .Values.keycloak.adminSecret.name }}
{{- else if eq (include "queenswood.keycloakMode" .) "operator" -}}
{{ include "queenswood.keycloakBootstrapAdminSecret" . }}
{{- end -}}
{{- end -}}

{{- /*
Postgres, for whatever in this release wants a relational database.
Instance-scoped rather than Keycloak's: a Cloud SQL instance hosts many
databases, and the proxy in front of it is shared by everything that
reaches one.

  off      no database is provisioned by or for this release
  local    an in-chart StatefulSet, for kind
  cloudsql the Cloud SQL Auth Proxy in front of an instance the
           installation's composite provisioned
*/ -}}
{{- define "queenswood.postgresProvider" -}}
{{- $p := .Values.postgres.provider -}}
{{- if not (has $p (list "off" "local" "cloudsql")) -}}
{{- fail (printf "postgres.provider %q is not one of off, local, cloudsql" $p) -}}
{{- end -}}
{{ $p }}
{{- end -}}

{{- define "queenswood.postgresHost" -}}
{{- $p := include "queenswood.postgresProvider" . -}}
{{- if eq $p "local" -}}
{{ .Release.Name }}-postgres
{{- else if eq $p "cloudsql" -}}
{{ .Release.Name }}-cloudsql
{{- else -}}
{{- fail "postgres.provider is off, so nothing in this release may ask for a database host" -}}
{{- end -}}
{{- end -}}

{{- /*
The database user. Cloud SQL names an IAM service account user after
the account's address with the `.gserviceaccount.com` suffix removed,
so it is derived from the one value that also annotates the proxy's
Kubernetes service account -- the two cannot then name different
principals, and no password exists on either side.
*/ -}}
{{- define "queenswood.postgresUser" -}}
{{- $p := include "queenswood.postgresProvider" . -}}
{{- if eq $p "cloudsql" -}}
{{- $sa := required "postgres.cloudsql.proxy.serviceAccount is required" .Values.postgres.cloudsql.proxy.serviceAccount -}}
{{- trimSuffix ".gserviceaccount.com" $sa -}}
{{- else -}}
{{ .Values.postgres.local.user }}
{{- end -}}
{{- end -}}

{{- define "queenswood.keycloakExpectedIssuer" -}}
{{- default (printf "%s/realms/%s" (include "queenswood.keycloakIssuer" .) .Values.keycloak.realm) .Values.keycloak.expectedIssuer -}}
{{- end -}}

{{- define "queenswood.keycloakOpsExpectedIssuer" -}}
{{- default (printf "%s/realms/%s" (include "queenswood.keycloakIssuer" .) .Values.keycloak.opsRealm) .Values.keycloak.opsExpectedIssuer -}}
{{- end -}}

{{- /*
The Kubernetes service account the Cloud SQL Auth Proxy runs as, and
the one name in this chart that another repository has to spell. So it
is a value rather than a derivation: deriving it from the release name
means the installation manifest must know what the Argo Application is
called, which is a coupling nothing checks -- both halves of Workload
Identity report healthy while naming different accounts, and only a 403
on a real connection says otherwise.
*/ -}}
{{- define "queenswood.postgresProxyServiceAccount" -}}
{{ required "postgres.cloudsql.proxy.kubernetesServiceAccount is required" .Values.postgres.cloudsql.proxy.kubernetesServiceAccount }}
{{- end -}}

{{- /*
Name of the SQL grant Job, hashed over what it runs for the same reason
the migrator and bootstrap Jobs are: a pod template is immutable, so a
Job whose content changes must also change its name or the apply is
rejected.
*/ -}}
{{- define "queenswood.sqlGrantJobName" -}}
{{- $spec := printf "%s|%s|%s" .Values.postgres.cloudsql.proxy.connectionName (include "queenswood.postgresUser" .) (join "," .Values.postgres.cloudsql.grant.databaseRoles) -}}
{{- printf "%s-sql-grant-%s" .Release.Name ($spec | sha256sum | trunc 10) -}}
{{- end -}}

{{- /*
The Secret holding the private_key_jwt signing pair for the
`queenswood-admin` client. Named here because three templates reach
it: the Secret itself, the bootstrap Job that fills it, and the
service Deployments that mount it.
*/ -}}
{{- define "queenswood.keycloakAdminKeySecret" -}}
{{ .Release.Name }}-keycloak-admin
{{- end -}}

{{- /*
The Secret carrying Keycloak's bootstrap admin: the one supplied where
`keycloak.bootstrapAdmin.secretName` names it, and the operator's
generated one otherwise. Two templates reach it -- the `Keycloak`
resource that names it and the realm-import Job that reads it.

It refuses the Secret above, whose name a bootstrap admin's is one
character from. That one holds the `queenswood-admin` client's signing
pair, declared without `data` and left to the bootstrap Job under
server-side apply, so an ExternalSecret pointed at the same name either
fails on the immutable type or takes the object over and replaces what
bootstrap wrote -- a realm that starts and a bank that cannot sign a
client assertion.
*/ -}}
{{- define "queenswood.keycloakBootstrapAdminSecret" -}}
{{- $supplied := .Values.keycloak.bootstrapAdmin.secretName -}}
{{- if eq $supplied (include "queenswood.keycloakAdminKeySecret" .) -}}
{{- fail (printf "keycloak.bootstrapAdmin.secretName is %s, which is where the queenswood-admin client's signing pair lives. Name the bootstrap admin's Secret something else." $supplied) -}}
{{- end -}}
{{- $supplied | default (printf "%s-keycloak-initial-admin" .Release.Name) -}}
{{- end -}}

{{- /*
ServiceAccount the bootstrap Job runs as. Its own rather than the
namespace default, because it writes the signing pair into the Secret
above and that grant must not reach every pod in the namespace.
*/ -}}
{{- define "queenswood.bootstrapServiceAccount" -}}
{{ .Release.Name }}-bootstrap
{{- end -}}

{{- /*
Where the FDB backup agents send their requests: the in-cluster proxy
rather than storage.googleapis.com. An explicit endpoint wins, for a
cluster reaching a proxy of its own; otherwise the Service this chart
renders. See templates/s3proxy.yaml.
*/ -}}
{{- define "queenswood.fdbBackupEndpoint" -}}
{{- if .Values.fdb.backup.endpoint -}}
{{ .Values.fdb.backup.endpoint }}
{{- else -}}
{{ .Release.Name }}-s3proxy
{{- end -}}
{{- end -}}
