# Kuudra Agent Handoff Guide

## Maintenance contract

- This file is a living handoff document, not a static specification. After an Agent makes an important architectural, module, build, configuration, plugin, lifecycle, or deployment change, update the relevant sections here in the same change set.
- `docs/` is the project's design and operational documentation. Keep it synchronized with implementation changes; do not treat code as the only source of truth.
- `docs/kuudra-user-guide.md` is the user-facing configuration and usage entry point. Any change to App configuration, manifest schema, resource kinds, namespace selection, Ability/Session semantics, Resource desired states, official example Resources, startup commands, or HTTP verification endpoints must update and revalidate the corresponding guide text and runnable YAML in the same change set.
- Preserve unrelated user changes. `docs/session-arch.png` is a tracked architecture asset; do not overwrite it unless explicitly requested.
- Use Chinese commit messages. Create a commit at meaningful implementation milestones, after verification.

## Repository identity and layout

The project is the ongoing replacement of the former Orcana/GTAV macro application. Its product and Maven identity is **Kuudra**.

The first stable kernel release is `v0.4.0`; the current stable kernel release is `v0.4.4`. The active development line is `v0.5.1-alpha-1`; released plugins should continue to use stable `v0.4.4` until the v0.5 line stabilizes.

Stable releases use `vX.X.X`. Every intermediate development version must use the exact `vX.X.X-alpha-N` form, with `N` increasing from 1; do not publish or document `SNAPSHOT` as an intermediate version. After a stable release, begin the next development line at the next intended stable version's `-alpha-1` identifier.

The single Maven/kernel version source is `.mvn/maven.config` (`revision`). The reactor and every child parent coordinate use `${revision}`; the flatten plugin resolves it for published POMs, and `kuudra-app` filters the same value into `META-INF/kuudra/version.properties` for startup logging and `KuudraApp.version()`. Version changes must update only that revision value and must verify both Maven evaluation and the runtime startup line.

The physical workspace may temporarily still be named `orcana` because Windows/IDE file handles blocked the requested rename. Treat it as a Kuudra repository; do not attempt another root-directory rename or delete nested Git metadata without explicit user coordination and a released workspace.

The tracked Maven reactor is:

| Module | Responsibility |
| --- | --- |
| `kuudra-api` | Shared public contracts, grouped into `action`, `app`, `component`, `context`, `event`, `lifecycle`, `runtime`, `session`, and `system` subpackages; only `KuudraException` remains at the API root. |
| `kuudra-i18n` | Framework-neutral message resolvers, JSON catalogs, placeholder interpolation, composition and packaged English messages. |
| `kuudra-config` | Format-neutral configuration model and YAML loader. |
| `kuudra-state` | MyBatis-backed SQLite desired/observed resource StateStore used by App reconciliation. SQL belongs in Mapper interfaces; persistence rows use Lombok and must not leak through the public StateStore API. |
| `kuudra-plugin` | Plugin metadata, ClassLoader archive loader, annotations, component registry, dependency-aware lifecycle manager. |
| `kuudra-runtime` | Dual-domain Ability graph, task queue, SessionManager, SessionCoordinator and asynchronous named Controller-handler scheduling. |
| `kuudra-app` | Framework-independent façade that owns a Runtime and applies external configuration. |
| `kuudra-web` | The sole HTTP REST/SSE adapter. It exposes **App**, never Runtime. |
| `kuudra-logging` | Spring-independent colored console logging, SystemEvent projection, and per-run file archival. |

`plugins/` is intentionally excluded by the root `.gitignore`. Plugin implementations are split into sibling independent Maven reactors at version `0.2.0-alpha-2`: `kuudra-official-plugins` contains only default, conditional-boundary, HelloWorld, logging and Session probe; `kuudra-audio-plugins` contains audio-host/player; `kuudra-windows-plugins` contains windows-native-host plus process/network control; `kuudra-automation-plugins` contains user-interaction-spec, JNativeHook, macro-spec/Kotlin and AWT Robot. None are implicitly registered by App. The default pass-through boundaries are `ingress/kuudra-official/plain-ingress` and `egress/kuudra-official/plain-egress`. The separate `kuudra-official/conditional-boundary` plugin provides `conditional-ingress` and `conditional-egress`; conditional Ingress may declare Session dependency requirements but never accesses SessionManager directly. `kuudra-official/session-probe` provides finite deterministic Event production and a cooperative long-running Handler for repeatable scheduling/dependency diagnostics; keep that active probe separate from the passive logging plugin. Every reactor builds independently after `kuudra-api` and `kuudra-plugin` artifacts are installed locally.

The external `actforever/windows-native-host` plugin embeds a self-contained .NET 8 `win-x64` privileged broker and exports only typed, owner-scoped native capabilities to declared dependent plugins. `actforever/process-control` provides `start`, `terminate`, `suspend` and `resume`: executable identity, launch arguments, working directory, process name, optional window-title selector, match policy and elevation choice are all static Resource options; Events may choose only an alias and optional PID. Normal start uses a no-shell JVM child, elevated start and termination use fixed typed RPC, every termination revalidates the image path, and started processes intentionally outlive the Resource. `actforever/network-control` consumes the separate `NETWORK_CONTROL` capability to provide program-scoped outbound firewall blocks, atomic multi-adapter disable, category-specific restore and owner restore through five named handlers. Loading the host JAR must never trigger UAC: elevation occurs only when a selected dependent Resource is initialized with `allowElevation: true`. Keep executable paths and adapter GUID/name selectors in static allowlists. Network adapter claims are reference-counted across owners, originally-disabled adapters must remain disabled, accepted operations must be atomically registered before Resource stop snapshots and drains them, and an atomic network recovery journal must cover broker/JVM failure. The broker must accept both bootstrap pipes concurrently while monitoring the launching JVM and a bounded connection timeout so a half-connected bootstrap cannot leave an orphan process. Do not turn this boundary into an arbitrary PowerShell/shell executor. Non-elevated UI overlays remain ordinary plugins.

The Windows native host layering, dependency ClassLoader linkage, embedded C# build, authenticated dual Named Pipe protocol, Win32 execution and recovery boundaries are documented in `docs/kuudra-windows-native-host.md`; keep it synchronized with the external parent and dependent plugins.

The external `actforever/audio-host` plugin mirrors the capability-host layering without elevation: it exports owner-scoped, single-track playback leases backed by Java Sound and bundled MP3/Vorbis decoder SPIs. Different leases may mix concurrently; reference-counted pause tokens isolate user, Resource lifecycle and Session pause reasons. `actforever/audio-player` is the dependent Controller wrapper with `play`, `play-random`, `pause`, `resume`, `stop`, and `set-volume` handlers. It scans `.wav/.mp3/.ogg` only under its own plugin home and never turns paths into arbitrary process execution. Keep `docs/kuudra-audio.md` and the external plugin READMEs synchronized.

For packaged Web, the fixed plugin directory is `<jar-directory>/.kuudra/plugins`: every JAR is strictly loaded. A plugin home is `<plugins>/<namespace>/<plugin-id>` and is created only when that plugin enters initialization. Invalid/non-Kuudra JARs are fatal startup errors. `PluginContext.home()` and `ResourceContext.plugin().home()` are the supported persistence locations. Do not reintroduce configurable plugin directories or a collision with the build-only `plugins/` directory.

## Architecture decisions already made

- The domain is event-driven. Resource extension points are `EventSource`, `EventInterpreter`, `EventAdapter`, `Ingress`, `Controller`, and `Egress`; `@EventHandler` names methods within a Controller and is not a Resource kind.
- The v0.5 resource model is documented in `docs/kuudra-ability-architecture.md`. Plugin archives publish `ResourceTemplate`s; App materializes `Resource`s on demand from Ability claims. A type-level `@Controller` exposes one or more named method-level `@EventHandler` entries with the strict `(KuudraEvent, EventHandlerContext) -> CompletionStage<Void>` signature. All resource implementations use the unified `ResourceLifecycle`; static Resource `options` and dynamic Ability-node `arguments` are distinct.
- `KuudraEvent` is the immutable business message. Runtime uses the sealed `KuudraEventWrapper` hierarchy (`RawEventWrapper`/`SessionEventWrapper`) to make execution domains explicit; never add a nullable Session back to the event entity.
- Runtime data has four logical scopes: immutable Event data, mutable Session context, mutable Ability context and mutable Global context. RAW nodes may read Event/Ability/Global; SESSION nodes additionally read Session. `${path}` searches only currently available scopes, while `${event#path}`, `${session#path}`, `${ability#path}`, and `${global#path}` are strict. `flow#` remains only as a transitional alias in `ContextValueReference`; new manifests and documentation must use `ability#`. There is no `rawEvent#` scope.
- Context writes use the extensible `ContextCodec`; the default JSON codec stores an immutable JSON-compatible tree and typed `get(..., Class<T>)` performs conversion on demand. `TypedValueMap` is the common read-only map lookup/conversion abstraction used by event/component configuration and plugin component initialization; do not duplicate manual number/boolean parsing in components. Shared plugin POJOs must be defined by a declared dependency so dependents resolve the same `Class<?>`; do not store raw plugin object references in runtime contexts.
- Ingress is the only RAW-to-SESSION boundary and Egress the only SESSION-to-RAW boundary. Every v1alpha2 Ingress node explicitly selects `CREATE` or `JOIN`; CREATE owns scheduling/dependency declarations and JOIN targets a CREATE ingress in the same Ability. Runtime-owned `SessionManager` creates sessions and owns leases, while the single `SessionCoordinator` executes scheduling and maintains the active dependency graph.
- Session has no implicit parent-child lifecycle. Egress preserves causal `EventLineage`; a later CREATE creates an independent Session and JOIN adds work to exactly one matching active Session. Group scheduling is evaluated before dependency resolution. Active dependencies support `UNIQUE/LATEST/ALL` and `CANCEL_DEPENDENT/CANCEL_REQUIRED/CANCEL_BOTH`.
- Resource `spec.template` uses `plugin-namespace/plugin-id/template-name`; the canonical registry reference adds the kind prefix as `type/plugin-namespace/plugin-id/template-name`. Do not reintroduce `spec.component`.
- An Ability is the runtime scheduling and control unit. A single `KuudraRuntime` is owned by the active `KuudraApp`.
- Controller handlers execute asynchronously. A named `@EventHandler` method returns `CompletionStage<Void>` and uses `EventHandlerContext` for arguments, execution control, emission and current-Session control. Runtime work leases, not business events, determine Session completion. Adapter/Ingress/Egress remain synchronous and must return quickly. EventInterpreter is intentionally different: it actively emits through `EventInterpreterContext`, while Runtime owns a serial state/buffer/timer scope for each `ability/revision/node`; delayed callbacks do not retain a Session lease. Long-running handlers may park at `checkpoint()` without releasing their Session lease.
- Plugin archives use `META-INF/kuudra-plugin/metadata.toml`, dependency-aware ClassLoaders, annotation-discovered ResourceTemplates, and declared dependency ordering. Plugin identity is always `namespace/pluginId`; equal plugin IDs in different namespaces are legal. Dependencies carry namespace, plugin ID, mandatory flag, and version range. Every JAR in `<home-directory>/plugins` is loaded and validated before ClassLoader creation. A dependency plugin's classes and resources are visible to dependents. App constructs claimed Resources, calls their `ResourceLifecycle` in order, and destroys them in reverse order; plugin activation alone must not initialize a Resource or trigger UAC.
- ResourceTemplate scanning skips `META-INF/versions/**`. `META-INF/kuudra-plugin/resources.idx`, when present, exclusively lists scanned Resource implementation classes; empty/comment-only means no ResourceTemplates. The older `components.idx` remains plugin-entrypoint scan metadata only and must not suppress v0.5 Resource discovery.
- Resources declare structured `@ResourceDoc`, `@SpecProperty` and `@EventEmission` metadata. `options` document static Resource configuration; Controller handler `arguments` document dynamic node inputs. Shared POJO types must come from declared plugin dependencies. Plugin code logs through the identity-bound `PluginLogger` supplied by `PluginContext`/`ResourceContext`.
- Platform-neutral input contracts live in `actforever/user-interaction-spec`, not the core API. `actforever/jnativehook` has a mandatory dependency on that contract, emits logical press/release and mouse events, and keeps native scalar diagnostics in a separate `jnativehook` EventData namespace. Device EventSources capture and normalize only; stateless filtering belongs to Adapter and gesture/sequence state belongs to Interpreter. The official sequential Interpreter resets for an out-of-order declared member, ignores unrelated Events by default, and supports strict contiguous commands through `resetOnUnmatched: true`. Mouse motion exists only when its resource is declared and supports COALESCE, THROTTLE and UNLIMITED output strategies. `actforever/macro-spec` owns the language-neutral immutable macro IR and frontend registry; `actforever/macro-kotlin` compiles trusted local `.kt` builder files into that IR. `actforever/awt-robot` consumes either YAML or frontend-produced IR, serializes every physical action across component instances, releases held input at cooperative pause/cancel/failure boundaries, and registers injected signatures through the shared contract. Scripts compile during component initialization and changed-file restart, never per Event. JNativeHook drops matching in-process synthetic events by default and may emit them with `synthetic=true` for diagnostics; do not bypass this feedback protection when adding another simulator.
- EventInterpreter uses the strict `void interpret(KuudraEvent, EventInterpreterContext)` contract. Immediate and delayed results both use `context.emit`; aggregate interpretations pass every causal Event so Runtime can merge lineage. Interpreter node inputs and scheduled callbacks are serialized. Ability/Resource/DATA-kernel pause, disable, unregister and shutdown cancel timers, clear node state/buffers and revoke earlier contexts. A shared Interpreter Resource may serve multiple nodes, but their Runtime scopes must never share progress.
- Ability registration precompiles node `arguments` placeholder syntax into immutable `PlaceholderResolver.CompiledMap` instances. Event execution performs only dynamic Event/Session/Ability/Global lookup and result assembly. Long-running Handler control flow that must observe later writes uses precompiled `ContextValueReference`; do not repeatedly parse reference paths inside loops. Keep regex scanning and expression path splitting out of the Runtime event hot path.
- Resource `options` are static, reject placeholders, and are consumed only during initialization. Ability-node `arguments` preserve native YAML numbers, booleans, maps and lists and may contain execution-time placeholders. Numeric/boolean strings remain strings.
- `kuudra-web` is an adapter only. Its lifecycle is conceptually independent from App lifecycle: stopping App closes Runtime/plugins but must not make HTTP lifecycle endpoints disappear. SSE client disconnects are normal transport cleanup: unsubscribe silently and do not call `completeWithError` for send-side IO disconnects.
- `kuudra-web` HTTP adapters are split under the `controller` package. `/api/v1/kuudra` groups kernel lifecycle and observation; `/api/v1/runtime` groups Ability, manifest Resource and Session; `/api/v1/plugin` groups Plugin and plugin-provided ResourceTemplate definitions. ResourceTemplate response `kind` values use manifest-ready PascalCase (`EventSource`, `Controller`, etc.), while canonical template references retain lowercase kebab-case prefixes. The Runtime URL domain is organizational only: every Web Controller depends on `KuudraApp` and must not expose a Runtime object.
- Runtime/App kernel failures exposed across module boundaries use the unchecked `KuudraException` and retain their cause. Keep environment/config-format/IO exceptions distinct until they cross the kernel boundary.
- Kuudra Web OpenAPI provides an aggregate default `all` group and domain `kuudra`, `runtime`, `plugin`, and `system-events` groups. Knife4j uses ordered Chinese display names while stable group identifiers continue to back `/v3/api-docs/{group}`. The `runtime` group merges Ability, manifest Resource and Session; the `plugin` group merges loaded plugins and ResourceTemplate definitions. `/runtime/resources` exposes actual lifecycle state and claims, while `/runtime/abilities` exposes claim/control state. Ability mutation returns HTTP 202. Keep tags and summaries synchronized when endpoints change.
- Runtime, plugin and App lifecycle observability is expressed as `SystemEvent`; do not inject concrete loggers into those modules for ordinary lifecycle messages. The App owns the sole subscribable `SystemEventBus` and injects only the write-only API-level `SystemEventPublisher` into Runtime and plugin management. Runtime must not own or expose a second event bus. `SystemEventLevel.AUTO` preserves legacy type-based severity mapping; detailed reconciliation, component construction and Runtime task-path diagnostics must use explicit `DEBUG` and must not include full Event/context payloads. `kuudra-logging` owns a private Logback context and is an App-bus output adapter exposing framework-neutral `KuudraLogConfiguration`/`KuudraLogLevel` APIs. Root `logging.level`, `logging.console-enabled`, and `logging.file-enabled` settings control the App log session; the log directory remains fixed. With file output enabled, it writes `<home-directory>/logs/latest.log` and archives it as `yyyy-MM-dd-N.log.gz` on normal kernel stop. The stopped run remains readable as `latest.log` until the next kernel start deletes that file and creates a new one. Home initialization must ensure `logs/` exists even when file output is disabled.
- Web Context closure (including Ctrl-C) must publish `web.shutdown.requested` before Spring destroys the App bean. Graceful shutdown must retain stage-level App/Runtime SystemEvents. Normal App/Runtime shutdown sub-stages and plugin/component stopping/destruction are DEBUG diagnostics; `app.stopping/app.stopped`, Session drain timeouts, and failure events remain visible at the default INFO threshold. Session drain waits are bounded by `runtime.shutdown-session-drain-timeout-ms`; plugin/component lifecycle stages currently rely on their returned CompletionStages and must publish their DEBUG start event before joining.
- Home self-check records every actually created required directory as `home.directory.created` and a restored packaged config as `home.configuration.created`; these are INFO/AUTO events published after the log session opens and must not be replayed on restart. SystemEvent producers must not embed human-language log sentences. Treat the stable event `type` as a future I18n message key and structured `data` fields as template arguments; current console/file rendering remains English-key based.
- The legacy v1alpha1 fixed-delay reconciler must never decode a v1alpha2 StateStore. v1alpha2 `AbilityManager` reconciles synchronously whenever Profile/direct claims change. Resource lifecycle failures mark only affected Abilities FAILED and remain observable through structured SystemEvents.
- Plugin archive scan `plugin.scan.started/completed`, successful `plugin.initialized/starting` stages, and normal shutdown stage details are DEBUG diagnostics. `plugin.active` remains INFO/AUTO so the default startup log identifies every plugin that actually became active by namespace/ID. Do not lower top-level App stop boundaries, drain timeouts, or failures.
- `kuudra-i18n` packages default English as `i18n/en_US.json`. App loads `<home>/locale/<preferred-locale>.json`, falls back to packaged `en_US`, and keeps an externally supplied Resolver at highest priority. Plugin catalogs live at `META-INF/kuudra-plugin/i18n/xx_XX.json`, are identity-scoped as `plugin.<namespace>.<pluginId>.*`, and are used through `PluginLogger.message(...)`.

See `docs/kuudra-event-architecture.md`, `docs/kuudra-architecture.md`, and `docs/kuudra-app-management.md` before changing these boundaries.

Macro IR, Kotlin authoring and AWT execution boundaries are documented in `docs/kuudra-macro.md`; keep it synchronized with the external plugins.

Public API package ownership is documented in `docs/kuudra-api-layout.md`. Keep plugin imports and external demos synchronized when moving a public contract; do not repopulate the `io.github.actforever.kuudra.api` root package with unrelated interfaces.

## Current runnable bootstrap path

The minimal end-to-end path is implemented:

```text
kuudra-web
  -> KuudraApp
  -> config.yaml + manifests/**/*.yaml + abilities/**/*.yaml + abilities/profiles/**/*.yaml
  -> plugin JAR scan
  -> metadata/dependency resolution and plugin startup
  -> Ability claims -> on-demand Resource materialization
  -> Ability compilation and EventSource registration
  -> RawEventWrapper -> Ingress CREATE/JOIN -> SessionEventWrapper -> named Controller handler -> optional Egress
```

- App configuration is owned entirely by `KuudraApp`; Web does not source Kuudra settings from Spring. Configuration is deeply merged in ascending priority: packaged `kuudra-app/src/main/resources/config.yaml`, `<home-directory>/config.yaml`, then an explicit `KuudraConfigResource` or configuration path passed while creating the App. For packaged Web, relative paths use the executable JAR directory as their base; standalone App defaults to the working directory.
- Global YAML contains root `home-directory`, `banner-enabled`, runtime queue/worker/timing settings, `max-event-hops`, Ability drain/cancel grace/Resource lifecycle timeouts, selected `ability-profiles`, directly selected `abilities`, reconciliation, StateStore, logging, I18n and `global-context`. App config keys use lowercase kebab-case; v1alpha2 resource manifests use camelCase. App initialization ensures fixed `plugins/`, `manifests/`, `abilities/`, `abilities/profiles/`, `logs/`, `state/`, and `locale/` directories exist. Do not reintroduce `resource-selection`, root SessionCoordinator defaults, or a top-level `flows/` directory.
- An Ability declares optional Resource aliases, nodes and edges. Alias values and direct node Resource references accept either a complete `kind/namespace/name` string or `{kind, namespace, name}` object; Resource namespace never defaults from Ability namespace. Claims are derived only from distinct node references, so unused aliases are valid but do not materialize Resources. Supported Resource kinds are `EventSource`, `EventInterpreter`, `EventAdapter`, `Ingress`, `Controller`, and `Egress`. Controller nodes must select `handler`; every Ingress node must select CREATE or JOIN. EventAdapter domain is inferred from topology. Do not reintroduce Flow resources, `kind: EventHandler`, `spec.component`, `desiredState`, `resource-selection`, or `SessionCoordinationPolicy` manifests.
- Ability claim state, Resource lifecycle state, Session state and kernel state are independent axes. Selected Profiles and root `abilities` configuration claims take union; runtime direct overrides take precedence and `inherit` restores that union. Together they determine ENABLED/PAUSED/DISABLED, while Resource lifecycle is the merge of all active claims. Stable App lifecycle and graceful stop/restart semantics remain unchanged.
- `kind/namespace/name` identifies one App-owned Resource instance. Multiple Ability aliases may claim the same identity only with identical static `options`; different names create different instances. `ResourcePolicy.allowParallel=false` serializes invocations of the shared instance across Abilities.
- `spec.executionClass` is `DATA` by default or `CONTROL`. Kernel pause gates DATA work; CONTROL remains routable while App is PAUSED. Use CONTROL only for bounded control paths.
- Multi-document YAML and recursive discovery remain supported. `<home>/manifests` is authoritative for Resource declarations, `<home>/abilities` for Ability declarations, and `<home>/abilities/profiles` for global AbilityProfiles. StateStore persists their generations/observed generations but never Sessions, event payloads or contexts.
- The built-in `controller/kuudra-official/default/system-control` exposes the `control` handler over the narrow `PluginRuntimeServices` port. Plugins must not depend on `KuudraApp` directly.
- Resource manifests live under fixed `<home-directory>/manifests`; Ability manifests live under fixed `<home-directory>/abilities`; global AbilityProfile manifests live under fixed `<home-directory>/abilities/profiles`. Plugin JARs live under fixed `<home-directory>/plugins`; they are local deployment artifacts and are not part of the core reactor.
- The exact startup procedure and failure behavior are documented in `docs/kuudra-bootstrap.md`.
- Logging event coverage, isolation and file rotation are documented in `docs/kuudra-logging.md`.
- The repeatable real-plugin verification matrix is documented in `docs/kuudra-e2e-verification.md`; keep it aligned with lifecycle, reconciliation, manifest reload and HTTP control semantics.

Current scope is a usable minimal kernel, not the complete long-term design. JSON/TOML loaders, reload/migration, static cycle diagnostics, `kuudra.system.*` handling, and a generic cross-language Resource SPI remain future work. The external Windows native host incubates only typed native-capability RPC: Java still owns Resource lifecycle, Event/Context access and scheduling, and the broker is neither a second Runtime nor an Event bridge. Runtime compiles placeholders at Ability registration; keep parsing out of the event hot path and match changes with tests.

## About creating commit

When creating Git commits:

- Keep the repository owner's configured Git identity as the commit author and committer.
- Add the following trailer to every commit created with Codex assistance:

  Co-authored-by: Codex <codex@openai.com>

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

For the HelloWorld smoke test: build the plugin, place its JAR in `.kuudra/plugins/`, write Resources under `.kuudra/manifests/`, Ability under `.kuudra/abilities/`, and Profile under `.kuudra/abilities/profiles/`, launch `kuudra-web`, then query `GET /api/v1/kuudra/status` and the Runtime Ability/Resource endpoints.

## Working rules

- Prefer `rg` for searches and `apply_patch` for source/document edits.
- Do not reset or discard a dirty worktree. Avoid destructive operations; resolve exact paths first.
- Keep `pom.xml` module boundaries intentional. Core must not regain plugin implementation modules.
- When changing configuration schema, update the loader, model, sample YAML, tests, and `docs/kuudra-bootstrap.md` together.
- Keep packaged `kuudra-app/src/main/resources/config.yaml` a complete Chinese-commented configuration template. Every new root configuration field must include a default, an inline Chinese explanation, loader/model coverage, and a matching entry in `docs/kuudra-bootstrap.md`.
- When changing public Resource contracts or Ability/Session semantics, update `kuudra-api`, runtime tests, architecture docs, and this file together.
- When changing plugin discovery/metadata/lifecycle, update the plugin module, plugin build instructions, and examples together.
- HTTP Controllers must call the App façade even when their resources are organized under `/api/v1/runtime`; do not inject or expose Runtime through Web.
- Resource controls must be modeled as App Resources (`kind`, namespace/name identity, template reference, claims and lifecycle state). Keep the concrete API resource-oriented so a future `kuudractl get event-source` is a direct adapter rather than a second control model.
- `KuudraConfigResource` is the framework-neutral, highest-priority App configuration entry point. `KuudraApp` merges it over home and packaged defaults. Do not add a Spring dependency to `kuudra-app` or adapt Spring configuration into Kuudra configuration.
- After completing requested modifications, create meaningful milestone commits with Chinese messages after verification. When one request contains several independently reviewable points, commit them separately by concern instead of collapsing everything into one coarse commit. Commit messages should describe the concrete behavior or boundary changed; use a short explanatory body when the reason, compatibility impact, or verification is not obvious from the subject.
