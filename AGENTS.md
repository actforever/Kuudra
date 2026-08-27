# Kuudra Agent Handoff Guide

## Maintenance contract

- This file is a living handoff document, not a static specification. After an Agent makes an important architectural, module, build, configuration, plugin, lifecycle, or deployment change, update the relevant sections here in the same change set.
- `docs/` is the project's design and operational documentation. Keep it synchronized with implementation changes; do not treat code as the only source of truth.
- Preserve unrelated user changes. `docs/session-arch.png` is a tracked architecture asset; do not overwrite it unless explicitly requested.
- Use Chinese commit messages. Create a commit at meaningful implementation milestones, after verification.

## Repository identity and layout

The project is the ongoing replacement of the former Orcana/GTAV macro application. Its product and Maven identity is **Kuudra**.

The first stable kernel release is `v0.4.0`. New plugins should depend on the stable `io.github.actforever:kuudra-api:v0.4.0` and `io.github.actforever:kuudra-plugin:v0.4.0` artifacts unless they intentionally target a later release.

The physical workspace may temporarily still be named `orcana` because Windows/IDE file handles blocked the requested rename. Treat it as a Kuudra repository; do not attempt another root-directory rename or delete nested Git metadata without explicit user coordination and a released workspace.

The tracked Maven reactor is:

| Module | Responsibility |
| --- | --- |
| `kuudra-api` | Shared public contracts, grouped into `action`, `app`, `component`, `context`, `event`, `lifecycle`, `runtime`, `session`, and `system` subpackages; only `KuudraException` remains at the API root. |
| `kuudra-i18n` | Framework-neutral message resolvers, JSON catalogs, placeholder interpolation, composition and packaged English messages. |
| `kuudra-config` | Format-neutral configuration model and YAML loader. |
| `kuudra-state` | MyBatis-backed SQLite desired/observed resource StateStore used by App reconciliation. SQL belongs in Mapper interfaces; persistence rows use Lombok and must not leak through the public StateStore API. |
| `kuudra-plugin` | Plugin metadata, ClassLoader archive loader, annotations, component registry, dependency-aware lifecycle manager. |
| `kuudra-runtime` | Dual-domain Flow graph, task queue, SessionManager, SessionCoordinator and asynchronous EventHandler scheduling. |
| `kuudra-app` | Framework-independent façade that owns a Runtime and applies external configuration. |
| `kuudra-web` | The sole HTTP REST/SSE adapter. It exposes **App**, never Runtime. |
| `kuudra-logging` | Spring-independent colored console logging, SystemEvent projection, and per-run file archival. |

`plugins/` is intentionally excluded by the root `.gitignore`. It is a local Maven aggregator for plugin implementations. The external `kuudra-official-plugins` workspace contains the ordinary deployable default, HelloWorld and logging plugins plus examples; none are implicitly registered by App. The official pass-through boundaries are `ingress/kuudra-official/plain-ingress` and `egress/kuudra-official/plain-egress`. Plugin builds expect `kuudra-api` and `kuudra-plugin` artifacts in the local Maven repository.

For packaged Web, the fixed plugin directory is `<jar-directory>/.kuudra/plugins`: every JAR is strictly loaded. A plugin home is `<plugins>/<namespace>/<plugin-id>` and is created only when that plugin enters initialization. Invalid/non-Kuudra JARs are fatal startup errors. `PluginContext.home()` and `PluginComponentContext.plugin().home()` are the supported persistence locations. Do not reintroduce configurable plugin directories or a collision with the build-only `plugins/` directory.

## Architecture decisions already made

- The domain is event-driven. Extension points are `EventSource`, `EventInterpreter`, `EventAdapter`, `Ingress`, `EventHandler`, and `Egress`.
- `KuudraEvent` is the immutable business message. Runtime uses the sealed `KuudraEventWrapper` hierarchy (`RawEventWrapper`/`SessionEventWrapper`) to make execution domains explicit; never add a nullable Session back to the event entity.
- Runtime data has four logical scopes: immutable Event data, mutable Session context, mutable Flow context and mutable Global context. RAW nodes may read Event/Flow/Global; SESSION nodes additionally read Session. `${path}` searches only currently available scopes, while `${event#path}`, `${session#path}`, `${flow#path}`, and `${global#path}` are strict. There is no `rawEvent#` scope.
- Context writes use the extensible `ContextCodec`; the default JSON codec stores an immutable JSON-compatible tree and typed `get(..., Class<T>)` performs conversion on demand. `TypedValueMap` is the common read-only map lookup/conversion abstraction used by event/component configuration and plugin component initialization; do not duplicate manual number/boolean parsing in components. Shared plugin POJOs must be defined by a declared dependency so dependents resolve the same `Class<?>`; do not store raw plugin object references in runtime contexts.
- Ingress is the only RAW-to-SESSION boundary and Egress the only SESSION-to-RAW boundary. Ingress computes admission/grouping only; Runtime-owned `SessionManager` creates sessions and owns leases, while `SessionCoordinator` owns bounded group scheduling.
- Session has no parent-child lifecycle. Egress preserves causal `EventLineage`; a later Ingress creates an independent Session.
- Component references use `type/namespace/name`, for example `event-source/hello-world/loop-emitter` and `event-handler/hello-world/console-printer`.
- A `KuudraFlow` is the runtime scheduling unit. A single `KuudraRuntime` is owned by the active `KuudraApp`.
- EventHandlers execute asynchronously. `EventHandler.handle` returns `CompletionStage<Void>` and may call `ActionContext.emit(KuudraEvent)` until that stage completes. Runtime preserves the current Session and lineage. Runtime work leases, not business events, determine Session completion. `EventContext`/`ActionContext` expose `ExecutionControl`: synchronous Adapter/Interpreter/Ingress/Egress poll without blocking, while long-running asynchronous handlers may park at `checkpoint()` without releasing their Session lease.
- Plugin archives use `META-INF/kuudra-plugin/metadata.toml`, dependency-aware ClassLoaders, annotation-discovered components, and declared dependency ordering. Plugin identity is always `namespace/pluginId`; equal plugin IDs in different namespaces are legal, and App/Web lookup paths must include both fields. `[[dependencies]]` entries carry namespace, plugin ID, mandatory flag, and a Forge/Maven-style version range. Plugin versions are dot-separated numeric segments with optional `-prerelease`/`+build` suffixes and no leading `v`. Every JAR in `<home-directory>/plugins` is loaded; dependency identity and version compatibility are validated before ClassLoader creation. A dependency plugin's classes and resource enumeration are visible to its dependents, and invalid archives, ranges, cycles, duplicate identities, incompatible versions and missing mandatory dependencies are errors. Successful starts are recorded incrementally so a later dependent failure cleans itself and rolls back already-active dependencies. Annotation-created instances may implement `PluginComponentLifecycle`; their `initialize` runs after plugin activation, receives the immutable Component `options` through `PluginComponentContext.configuration()`, and their reverse-order `destroy` runs before plugin shutdown. The former standalone Action SPI and ActionActor adapter had no manifest/App/Runtime production path and must not be reintroduced without a complete resource and execution model.
- Plugin components may declare structured `@ComponentDoc` and `@EventEmission` metadata. `@ComponentDoc.configuration` uses `@SpecProperty` to document component-owned `spec.options` paths, compile-time `Class<?>` types, required/default/allowed values, descriptions and examples. Dot/`[]` paths describe nested objects and arrays; shared POJO types must come from declared plugin dependencies. This is documentation metadata and not yet a reflective or generic validation schema. The registry exposes immutable plugin/component views through App and Web. Plugin code logs through the identity-bound `PluginLogger` supplied by `PluginContext`/`PluginComponentContext`; it publishes `plugin.log` SystemEvents rather than binding plugins to a logging framework.
- Flow registration precompiles node option placeholder syntax into immutable `PlaceholderResolver.CompiledMap` instances. Event execution performs only dynamic four-scope lookup and result assembly. Keep regex scanning and expression path splitting out of the Runtime event hot path.
- Node options preserve native YAML numbers, booleans, maps and lists. Quoted strings shaped as JSON objects/arrays are parsed through the active `ContextCodec`; static JSON is parsed at Flow registration, while JSON containing placeholders is parsed after event-time interpolation. Numeric/boolean strings remain strings.
- `kuudra-web` is an adapter only. Its lifecycle is conceptually independent from App lifecycle: stopping App closes Runtime/plugins but must not make HTTP lifecycle endpoints disappear. SSE client disconnects are normal transport cleanup: unsubscribe silently and do not call `completeWithError` for send-side IO disconnects.
- `kuudra-web` HTTP adapters are split under the `controller` package. `/api/v1/kuudra` groups kernel lifecycle, observation and built-in resource documentation; `/api/v1/runtime` groups Flow, manifest Component resources and Session; `/api/v1/plugin` groups Plugin and plugin-provided ComponentTemplate definitions. ComponentTemplate response `kind` values use manifest-ready PascalCase (`EventSource`, `EventHandler`, etc.), while canonical component references retain lowercase kebab-case prefixes. EventSource instances use the generic Runtime Component API rather than a duplicate HTTP controller. The Runtime URL domain is organizational only: every Controller depends on `KuudraApp` and must not expose a Runtime object.
- Runtime/App kernel failures exposed across module boundaries use the unchecked `KuudraException` and retain their cause. Keep environment/config-format/IO exceptions distinct until they cross the kernel boundary.
- Kuudra Web OpenAPI provides an aggregate default `all` group and domain `kuudra`, `runtime`, `plugin`, and `system-events` groups. Knife4j uses ordered Chinese display names while the stable group identifiers continue to back `/v3/api-docs/{group}`. Controller-level OpenAPI tags consolidate the left navigation into `Kuudra`, `Runtime`, `Plugin`, and `System Events`; operation summaries remain Chinese. The `runtime` group merges Flow, manifest Component resources, and Sessions; the `plugin` group merges loaded plugins and ComponentTemplate definitions. `/runtime/components` exposes every manifest Component and its actual state/imports/capabilities. Keep tags and operation summaries synchronized when endpoints change; grouping must not expose a Runtime object.
- Runtime, plugin and App lifecycle observability is expressed as `SystemEvent`; do not inject concrete loggers into those modules for ordinary lifecycle messages. The App owns the sole subscribable `SystemEventBus` and injects only the write-only API-level `SystemEventPublisher` into Runtime and plugin management. Runtime must not own or expose a second event bus. `SystemEventLevel.AUTO` preserves legacy type-based severity mapping; detailed reconciliation, component construction and Runtime task-path diagnostics must use explicit `DEBUG` and must not include full Event/context payloads. `kuudra-logging` owns a private Logback context and is an App-bus output adapter exposing framework-neutral `KuudraLogConfiguration`/`KuudraLogLevel` APIs. Root `logging.level`, `logging.console-enabled`, and `logging.file-enabled` settings control the App log session; the log directory remains fixed. With file output enabled, it writes `<home-directory>/logs/latest.log` and archives it as `yyyy-MM-dd-N.log.gz` on normal kernel stop. The stopped run remains readable as `latest.log` until the next kernel start deletes that file and creates a new one. Home initialization must ensure `logs/` exists even when file output is disabled.
- Web Context closure (including Ctrl-C) must publish `web.shutdown.requested` before Spring destroys the App bean. Graceful shutdown must retain stage-level App/Runtime SystemEvents. Normal App/Runtime shutdown sub-stages and plugin/component stopping/destruction are DEBUG diagnostics; `app.stopping/app.stopped`, Session drain timeouts, and failure events remain visible at the default INFO threshold. Session drain waits are bounded by `runtime.shutdown-session-drain-timeout-ms`; plugin/component lifecycle stages currently rely on their returned CompletionStages and must publish their DEBUG start event before joining.
- Home self-check records every actually created required directory as `home.directory.created` and a restored packaged config as `home.configuration.created`; these are INFO/AUTO events published after the log session opens and must not be replayed on restart. SystemEvent producers must not embed human-language log sentences. Treat the stable event `type` as a future I18n message key and structured `data` fields as template arguments; current console/file rendering remains English-key based.
- Every enabled periodic reconciliation cycle emits TRACE `reconciliation.cycle.started/completed`. Actual component observed-state transitions emit DEBUG `component.state.changed` for detailed diagnostics and INFO/AUTO `resource.state.changed` for default operational visibility. Preserve these levels and the shared `resource/from/to/desiredState` fields when changing reconciliation.
- Plugin archive scan `plugin.scan.started/completed`, successful `plugin.initialized/starting` stages, and normal shutdown stage details are DEBUG diagnostics. `plugin.active` remains INFO/AUTO so the default startup log identifies every plugin that actually became active by namespace/ID. Do not lower top-level App stop boundaries, drain timeouts, or failures.
- `kuudra-i18n` packages default English as `i18n/en_US.json`. App loads `<home>/locale/<preferred-locale>.json`, falls back to packaged `en_US`, and keeps an externally supplied Resolver at highest priority. Plugin catalogs live at `META-INF/kuudra-plugin/i18n/xx_XX.json`, are identity-scoped as `plugin.<namespace>.<pluginId>.*`, and are used through `PluginLogger.message(...)`.

See `docs/kuudra-event-architecture.md`, `docs/kuudra-architecture.md`, and `docs/kuudra-app-management.md` before changing these boundaries.

Public API package ownership is documented in `docs/kuudra-api-layout.md`. Keep plugin imports and external demos synchronized when moving a public contract; do not repopulate the `io.github.actforever.kuudra.api` root package with unrelated interfaces.

## Current runnable bootstrap path

The minimal end-to-end path is implemented:

```text
kuudra-web
  -> KuudraApp
  -> config.yaml + manifests/**/*.yaml
  -> plugin JAR scan
  -> metadata/dependency resolution and plugin startup
  -> Flow compilation and EventSource registration
  -> RawEventWrapper -> Ingress -> SessionEventWrapper -> EventHandler -> optional Egress
```

- App configuration is owned entirely by `KuudraApp`; Web does not source Kuudra settings from Spring. Configuration is deeply merged in ascending priority: packaged `kuudra-app/src/main/resources/config.yaml`, `<home-directory>/config.yaml`, then an explicit `KuudraConfigResource` or configuration path passed while creating the App. For packaged Web, relative paths use the executable JAR directory as their base; standalone App defaults to the working directory.
- Global YAML contains root `home-directory`, `banner-enabled`, runtime queue/worker/timing settings, `max-event-hops`, SessionCoordinator defaults, resource namespace selection, reconciliation enable/interval, StateStore busy timeout, logging, I18n preferred locale and `global-context`. App config keys use lowercase kebab-case; K8s-style resource manifests use standard camelCase fields such as `apiVersion` and `desiredState`. The packaged config is the complete Chinese-commented user template. App initialization ensures fixed `plugins/`, `manifests/`, `logs/`, `state/`, and `locale/` directories exist and restores a missing home `config.yaml` from packaged defaults. Do not recreate a top-level `flows/` directory.
- Each Flow imports concrete resource kinds and declares `edges`. Supported kinds are `EventSource`, `EventInterpreter`, `EventAdapter`, `Ingress`, `EventHandler`, and `Egress`; `spec.type` and `kind: Component` are invalid. The external `kuudra-default-plugin` is loaded from its JAR as `kuudra-official/default`; without that JAR the kernel exposes no default components. An EventAdapter declares its deployment `domain` (`RAW` or `SESSION`) and cannot change it.
- Flow is an immutable routing declaration and has no lifecycle state. Kernel, Component and Session execution controls are independent state axes. App kernel pause is a coarse Runtime event gate: it preserves and does not mutate component desired/observed states or Session states. Component pause is fine-grained desired-state reconciliation and Session pause affects only that Session. Resource queries distinguish observed `status` from gated `effectiveStatus` and expose suspension reasons. Stable App lifecycle is `CREATED -> RUNNING -> STOPPING -> STOPPED`, with `STARTING` and the `PAUSING/PAUSED/RESUMING` non-destructive subflow. Stop/restart must preempt an in-progress or completed pause. Stop always follows the same graceful component/plugin shutdown path regardless of RUNNING or PAUSED; restart has no force-clear branch and strictly performs that normal `stop()` followed by `start()`. App must not hold its monitor while waiting for the Runtime pause barrier. The checkpoint is observation data, not StateStore persistence.
- Cross-Flow reuse is explicit: plugin definitions provide instance constraints, Component manifests define named App-owned instances, and Flow manifests import them. Sharing requires `shareable` and `threadSafe`; one EventSource can fan out to multiple Flow targets and starts/stops once.
- K8s-style resources use camelCase keys (`apiVersion`, `desiredState`) under recursively discovered `<home>/manifests/`. Resource identity and canonical routing address are `kind/namespace/name`. Namespace is an enforced resource boundary: a Flow may import only resources in its own namespace. An import's `namespace` is optional and defaults to the Flow namespace; the explicit field remains supported but cannot currently cross the boundary. Flow `spec.imports` references concrete resource identities and resources never reference Flow. Startup validates identities, namespace boundaries, references, kinds, limits and sharing safety. There is no legacy `kind: Component`, legacy Flow schema, or separate Flow configuration directory.
- `resource-selection.namespace-mode` is `ALL` or `INCLUDE`; INCLUDE accepts one or more resource namespaces. Manifest parsing and StateStore replacement always retain the complete authoritative set. App materializes/reconciles only selected namespaces, marks unselected resources `EXCLUDED`, exposes `selected` on Component/Flow queries, and rejects desired-state controls for excluded resources. Plugin namespaces are unrelated and plugins remain globally loaded.
- A manifest file may contain multiple YAML documents separated by `---`; duplicate identities are still rejected across every file and document. Manifest diagnostics include file, document, nearby line, resource identity, field path, and an expected-shape hint. `desiredState` applies only to Component resources and is capability-derived from the plugin definition: components without runtime `Lifecycle` support `active/inactive`, `Lifecycle` components support `running/stopped`, and `PausableLifecycle` adds `paused`. Transitional observed states such as `STARTING`, `STOPPING`, `PAUSING`, and `RESUMING` are never valid desired states. Plugin component documentation exposes the same `supportedDesiredStates` used by App validation and reconciliation. Flow rejects `desiredState` because it is routing, not a state machine.
- `<home-directory>/state/kuudra.db` is the embedded SQLite StateStore. On every start, including the start half of restart, App reloads the complete `<home>/manifests` directory; that set is authoritative and transactionally replaces persisted desired resources, including overriding conflicting runtime API changes and deleting identities absent from disk. The App desired-state API can update that set for the current run. The App—not Runtime—reconciles instances, advances `observedGeneration` only after success, and records failures without claiming convergence. A configurable fixed-delay App loop retries generations that are not observed or are `FAILED`; it does not rescan manifest files. Resource/control-plane queries use StateStore and remain available after Runtime stop, while Session and live metrics remain Runtime-backed. `INACTIVE` preserves the resource instance and Flow binding but closes the Runtime execution gate; resource deletion, not inactivity, owns final instance destruction. StateStore never contains Sessions, event payloads, pause checkpoints, or plugin-owned data.
- The built-in `event-handler/kuudra-official/system-control` converts routed Event configuration into requests on the narrow `PluginRuntimeServices` control port. Supported actions cover kernel pause/resume/stop and current-Session pause/resume/cancel. Plugins must not depend on `KuudraApp` directly.
- Component and Flow resources live under fixed `<home-directory>/manifests`. Plugin JARs live under fixed `<home-directory>/plugins`; they are local deployment artifacts and are not part of the core reactor.
- The exact startup procedure and failure behavior are documented in `docs/kuudra-bootstrap.md`.
- Logging event coverage, isolation and file rotation are documented in `docs/kuudra-logging.md`.
- The repeatable real-plugin verification matrix is documented in `docs/kuudra-e2e-verification.md`; keep it aligned with lifecycle, reconciliation, manifest reload and HTTP control semantics.

Current scope is a usable minimal kernel, not the complete long-term design. JSON/TOML loaders, reload/migration, static cycle diagnostics, `kuudra.system.*` handling, and cross-language bridges remain future work. All Flows are peers. Runtime compiles placeholders with the node input domain at Flow registration; keep parsing out of the event hot path and match changes with tests.

## Build and verification

Core reactor:

```powershell
mvn test -DskipTests=false
```

Surefire skips test execution by default while still compiling test sources. Use `-DskipTests=false` whenever tests must run.

Local plugin aggregator (after core artifacts are installed):

```powershell
mvn -pl kuudra-api,kuudra-plugin -am install -DskipTests
mvn -f plugins/pom.xml clean package
```

The current machine has previously failed full forked tests because of a small Windows paging file. Running Surefire in-process can then fail Spring/Mockito's ByteBuddy self-attach requirement. These are environment failures, not established product failures. Prefer targeted module tests and a real packaged Web bootstrap verification; report the exact command and limitation in handoff/final output.

For the HelloWorld smoke test: build the plugin, place its JAR in `.kuudra/plugins/`, write Component and Flow manifests under `.kuudra/manifests/`, launch `kuudra-web`, then query `GET /api/v1/kuudra/status`.

## Working rules

- Prefer `rg` for searches and `apply_patch` for source/document edits.
- Do not reset or discard a dirty worktree. Avoid destructive operations; resolve exact paths first.
- Keep `pom.xml` module boundaries intentional. Core must not regain plugin implementation modules.
- When changing configuration schema, update the loader, model, sample YAML, tests, and `docs/kuudra-bootstrap.md` together.
- Keep packaged `kuudra-app/src/main/resources/config.yaml` a complete Chinese-commented configuration template. Every new root configuration field must include a default, an inline Chinese explanation, loader/model coverage, and a matching entry in `docs/kuudra-bootstrap.md`.
- When changing public component contracts or Flow/session semantics, update `kuudra-api`, runtime tests, architecture docs, and this file together.
- When changing plugin discovery/metadata/lifecycle, update the plugin module, plugin build instructions, and examples together.
- HTTP Controllers must call the App façade even when their resources are organized under `/api/v1/runtime`; do not inject or expose Runtime through Web.
- Resource controls must be modeled as App resources (`type`, Flow scope, resource id, component reference and status). Keep the concrete API resource-oriented so a future `kuudractl get event-source` is a direct adapter rather than a second control model.
- `KuudraConfigResource` is the framework-neutral, highest-priority App configuration entry point. `KuudraApp` merges it over home and packaged defaults. Do not add a Spring dependency to `kuudra-app` or adapt Spring configuration into Kuudra configuration.
- After completing requested modifications, create meaningful milestone commits with Chinese messages after verification. When one request contains several independently reviewable points, commit them separately by concern instead of collapsing everything into one coarse commit. Commit messages should describe the concrete behavior or boundary changed; use a short explanatory body when the reason, compatibility impact, or verification is not obvious from the subject.
