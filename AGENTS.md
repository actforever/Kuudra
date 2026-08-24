# Kuudra Agent Handoff Guide

## Maintenance contract

- This file is a living handoff document, not a static specification. After an Agent makes an important architectural, module, build, configuration, plugin, lifecycle, or deployment change, update the relevant sections here in the same change set.
- `docs/` is the project's design and operational documentation. Keep it synchronized with implementation changes; do not treat code as the only source of truth.
- Preserve unrelated user changes. `docs/session-arch.png` is a tracked architecture asset; do not overwrite it unless explicitly requested.
- Use Chinese commit messages. Create a commit at meaningful implementation milestones, after verification.

## Repository identity and layout

The project is the ongoing replacement of the former Orcana/GTAV macro application. Its product and Maven identity is **Kuudra**.

The physical workspace may temporarily still be named `orcana` because Windows/IDE file handles blocked the requested rename. Treat it as a Kuudra repository; do not attempt another root-directory rename or delete nested Git metadata without explicit user coordination and a released workspace.

The tracked Maven reactor is:

| Module | Responsibility |
| --- | --- |
| `kuudra-api` | Shared public contracts: KuudraEvent wrappers, component interfaces, session and App snapshots. |
| `kuudra-config` | Format-neutral configuration model and YAML loader. |
| `kuudra-plugin` | Plugin metadata, ClassLoader archive loader, annotations, component registry, dependency-aware lifecycle manager. |
| `kuudra-runtime` | Dual-domain Flow graph, task queue, SessionManager, SessionCoordinator and asynchronous EventHandler scheduling. |
| `kuudra-app` | Framework-independent façade that owns a Runtime and applies external configuration. |
| `kuudra-web` | The sole HTTP REST/SSE adapter. It exposes **App**, never Runtime. |
| `kuudra-logging` | Spring-independent colored console logging, SystemEvent projection, and per-run file archival. |

`plugins/` is intentionally excluded by the root `.gitignore`. It is a local Maven aggregator for plugin implementations, currently containing `kuudra-hello-world-plugin`. It is not part of the Kuudra core reactor. Plugin builds expect `kuudra-api` and `kuudra-plugin` artifacts to be available in the local Maven repository.

For packaged Web, the fixed plugin directory is `<jar-directory>/.kuudra/plugins`: every JAR is strictly loaded. A plugin home is `<plugins>/<namespace>/<plugin-id>` and is created only when that plugin enters initialization. Invalid/non-Kuudra JARs are fatal startup errors. `PluginContext.home()` and `PluginComponentContext.plugin().home()` are the supported persistence locations. Do not reintroduce configurable plugin directories or a collision with the build-only `plugins/` directory.

## Architecture decisions already made

- The domain is event-driven. Extension points are `EventSource`, `EventInterpreter`, `EventAdapter`, `Ingress`, `EventHandler`, and `Egress`.
- `KuudraEvent` is the immutable business message. Runtime uses the sealed `KuudraEventWrapper` hierarchy (`RawEventWrapper`/`SessionEventWrapper`) to make execution domains explicit; never add a nullable Session back to the event entity.
- Runtime data has four logical scopes: immutable Event data, mutable Session context, mutable Flow context and mutable Global context. RAW nodes may read Event/Flow/Global; SESSION nodes additionally read Session. `${path}` searches only currently available scopes, while `${event#path}`, `${session#path}`, `${flow#path}`, and `${global#path}` are strict. There is no `rawEvent#` scope.
- Context writes use the extensible `ContextCodec`; the default JSON codec stores an immutable JSON-compatible tree and typed `get(..., Class<T>)` performs conversion on demand. `TypedValueMap` is the common read-only map lookup/conversion abstraction used by event/action configuration, Action arguments, and plugin component initialization; do not duplicate manual number/boolean parsing in components. Shared plugin POJOs must be defined by a declared dependency so dependents resolve the same `Class<?>`; do not store raw plugin object references in runtime contexts.
- Ingress is the only RAW-to-SESSION boundary and Egress the only SESSION-to-RAW boundary. Ingress computes admission/grouping only; Runtime-owned `SessionManager` creates sessions and owns leases, while `SessionCoordinator` owns bounded group scheduling.
- Session has no parent-child lifecycle. Egress preserves causal `EventLineage`; a later Ingress creates an independent Session.
- Component references use `type/namespace/name`, for example `event-source/hello-world/loop-emitter` and `event-handler/hello-world/console-printer`.
- A `KuudraFlow` is the runtime scheduling unit. A single `KuudraRuntime` is owned by the active `KuudraApp`.
- EventHandlers execute asynchronously. `EventHandler.handle` returns `CompletionStage<Void>` and may call `ActionContext.emit(KuudraEvent)` until that stage completes. Runtime preserves the current Session and lineage. Runtime work leases, not business events, determine Session completion.
- Plugin archives use `META-INF/kuudra-plugin/metadata.toml`, dependency-aware ClassLoaders, annotation-discovered components, and declared dependency ordering. Every JAR in `<home-directory>/plugins` is loaded; a dependency plugin's classes and resource enumeration are visible to its dependents, and invalid archives, cycles, duplicate IDs and missing dependencies are errors. Successful starts are recorded incrementally so a later dependent failure cleans itself and rolls back already-active dependencies. Annotation-created instances may implement `PluginComponentLifecycle`; their `initialize` runs after plugin activation, receives the immutable Component `options` through `PluginComponentContext.configuration()`, and their reverse-order `destroy` runs before plugin shutdown. Plugin Actions remain Java-based for now; cross-language execution is a future bridge concern.
- Plugin components may declare structured `@ComponentDoc` and `@EventEmission` metadata. The registry exposes immutable plugin/component views through App and Web. Plugin code logs through the identity-bound `PluginLogger` supplied by `PluginContext`/`PluginComponentContext`; it publishes `plugin.log` SystemEvents rather than binding plugins to a logging framework.
- Flow registration precompiles node option placeholder syntax into immutable `PlaceholderResolver.CompiledMap` instances. Event execution performs only dynamic four-scope lookup and result assembly. Keep regex scanning and expression path splitting out of the Runtime event hot path.
- Node options preserve native YAML numbers, booleans, maps and lists. Quoted strings shaped as JSON objects/arrays are parsed through the active `ContextCodec`; static JSON is parsed at Flow registration, while JSON containing placeholders is parsed after event-time interpolation. Numeric/boolean strings remain strings.
- `kuudra-web` is an adapter only. Its lifecycle is conceptually independent from App lifecycle: stopping App closes Runtime/plugins but must not make HTTP lifecycle endpoints disappear.
- Runtime/App kernel failures exposed across module boundaries use the unchecked `KuudraException` and retain their cause. Keep environment/config-format/IO exceptions distinct until they cross the kernel boundary.
- Kuudra Web OpenAPI provides an aggregate default `all` group and stable `app-lifecycle`, `flows`, `event-sources`, `sessions`, `system-events`, and `plugins` groups. Keep Chinese tags/operation summaries synchronized when endpoints change; grouping must not change REST paths or expose Runtime.
- Runtime, plugin and App lifecycle observability is expressed as `SystemEvent`; do not inject concrete loggers into those modules for ordinary lifecycle messages. `kuudra-logging` owns a private Logback context and exposes framework-neutral `KuudraLogConfiguration`/`KuudraLogLevel` APIs. Root `logging.level`, `logging.console-enabled`, and `logging.file-enabled` settings control the App log session; the log directory remains fixed. With file output enabled, it writes `<home-directory>/logs/latest.log` and archives it as `yyyy-MM-dd-N.log.gz` on normal kernel stop. The stopped run remains readable as `latest.log` until the next kernel start deletes that file and creates a new one. Home initialization must ensure `logs/` exists even when file output is disabled.

See `docs/kuudra-event-architecture.md`, `docs/kuudra-architecture.md`, and `docs/kuudra-app-management.md` before changing these boundaries.

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
- Global YAML contains root `home-directory`, runtime queue/worker settings, `max-event-hops`, SessionCoordinator defaults, logging level/output switches and `global-context`. App config keys use lowercase kebab-case; K8s-style resource manifests use standard camelCase fields such as `apiVersion` and `desiredState`. App initialization ensures fixed `plugins/`, `manifests/`, `logs/`, and `state/` directories exist and restores a missing home `config.yaml` from packaged defaults. Do not recreate a top-level `flows/` directory.
- Each Flow imports Component resources and declares `edges`. Supported component types are `event-source`, `event-interpreter`, `event-adapter`, `ingress`, `event-handler`, and `egress`. Core boundaries use `core/default`. An EventAdapter declares its deployment `domain` (`RAW` or `SESSION`) and cannot change it.
- Flow is a scope for component names, routing and sessions; starting, pausing or stopping a Flow changes its routing/session gate and does not implicitly start or stop its resources. Event sources are queried and controlled through App resource APIs and `/api/v1/app/flows/{flowId}/resources/event-sources/...`.
- Cross-Flow reuse is explicit: plugin definitions provide instance constraints, Component manifests define named App-owned instances, and Flow manifests import them. Sharing requires `shareable` and `threadSafe`; one EventSource can fan out to multiple Flow targets and starts/stops once.
- K8s-style resources use camelCase keys (`apiVersion`, `desiredState`) under recursively discovered `<home>/manifests/`. Both Component and Flow are resource kinds, but dependency direction is strict: Flow `spec.imports` references Component identities; Component resources never reference Flow. Startup validates identities, references, kinds, limits and sharing safety. There is no legacy Flow schema or separate Flow configuration directory.
- Component and Flow resources live under fixed `<home-directory>/manifests`. Plugin JARs live under fixed `<home-directory>/plugins`; they are local deployment artifacts and are not part of the core reactor.
- The exact startup procedure and failure behavior are documented in `docs/kuudra-bootstrap.md`.
- Logging event coverage, isolation and file rotation are documented in `docs/kuudra-logging.md`.

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

For the HelloWorld smoke test: build the plugin, place its JAR in `.kuudra/plugins/`, write Component and Flow manifests under `.kuudra/manifests/`, launch `kuudra-web`, then query `GET /api/v1/app/status`.

## Working rules

- Prefer `rg` for searches and `apply_patch` for source/document edits.
- Do not reset or discard a dirty worktree. Avoid destructive operations; resolve exact paths first.
- Keep `pom.xml` module boundaries intentional. Core must not regain plugin implementation modules.
- When changing configuration schema, update the loader, model, sample YAML, tests, and `docs/kuudra-bootstrap.md` together.
- When changing public component contracts or Flow/session semantics, update `kuudra-api`, runtime tests, architecture docs, and this file together.
- When changing plugin discovery/metadata/lifecycle, update the plugin module, plugin build instructions, and examples together.
- HTTP endpoints must be phrased in terms of App. Do not add Runtime-named Web APIs.
- Resource controls must be modeled as App resources (`type`, Flow scope, resource id, component reference and status). Keep the concrete API resource-oriented so a future `kuudractl get event-source` is a direct adapter rather than a second control model.
- `KuudraConfigResource` is the framework-neutral, highest-priority App configuration entry point. `KuudraApp` merges it over home and packaged defaults. Do not add a Spring dependency to `kuudra-app` or adapt Spring configuration into Kuudra configuration.
- After completing requested modifications, create a meaningful milestone commit with a Chinese commit message after verification.
