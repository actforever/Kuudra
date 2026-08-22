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
| `kuudra-api` | Shared public contracts: Event, component interfaces, session and App snapshots. |
| `kuudra-config` | Format-neutral configuration model and YAML loader. |
| `kuudra-plugin` | Plugin metadata, ClassLoader archive loader, annotations, component registry, dependency-aware lifecycle manager. |
| `kuudra-runtime` | Event task queue, Flow graph, session allocation/lifecycle, asynchronous Actor scheduling. |
| `kuudra-app` | Framework-independent façade that owns a Runtime and applies external configuration. |
| `kuudra-web` | The sole HTTP REST/SSE adapter. It exposes **App**, never Runtime. |
| `kuudra-logging` | Spring-independent colored console logging, SystemEvent projection, and per-run file archival. |

`plugins/` is intentionally excluded by the root `.gitignore`. It is a local Maven aggregator for plugin implementations, currently containing `kuudra-hello-world-plugin`. It is not part of the Kuudra core reactor. Plugin builds expect `kuudra-api` and `kuudra-plugin` artifacts to be available in the local Maven repository.

For packaged Web, the fixed plugin directory is `<jar-directory>/.kuudra/plugins`: every JAR is strictly loaded, while plugin-id-named homes share that directory and are created only when the plugin enters initialization. Invalid/non-Kuudra JARs are fatal startup errors. `PluginContext.home()` and `PluginComponentContext.plugin().home()` are the supported persistence locations. Do not reintroduce configurable plugin directories or a collision with the build-only `plugins/` directory.

## Architecture decisions already made

- The domain is event-driven. Main extension points are `EventSource`, `EventAdapter`, `EventProcessor`, and `Actor`.
- `Event` is the common message model. `EventData` is immutable and namespace-keyed to prevent payload collisions between plugins.
- Runtime data has four scopes: immutable derived Event data, mutable Session context, mutable Flow context shared across sessions, and mutable Global context shared across flows. Configuration placeholders are read-only: `${path}` searches Event -> Session -> Flow -> Global, while `${event#path}`, `${session#path}`, `${flow#path}`, and `${global#path}` are strict. Components write through context APIs, never through YAML.
- Context writes use the extensible `ContextCodec`; the default JSON codec stores an immutable JSON-compatible tree and typed `get(..., Class<T>)` performs conversion on demand. Shared plugin POJOs must be defined by a declared dependency so dependents resolve the same `Class<?>`; do not store raw plugin object references in runtime contexts.
- `SessionAllocator` is the only Flow node that creates sessions. Events without a session must pass it before reaching an Actor.
- Actor-originated Events normally inherit their session when routed directly to another Actor. Routing an event back to an EventProcessor or SessionAllocator detaches its session and records lineage, so a new child session can be allocated.
- Component references must use `type/namespace/name`, for example `event-source/hello-world/loop-emitter` and `actor/hello-world/console-printer`.
- A `KuudraFlow` is the runtime scheduling unit. A single `KuudraRuntime` is owned by the active `KuudraApp`.
- Actors execute asynchronously. `Actor.act` returns `CompletionStage<Void>` and may call `ActionContext.emit(Event)` at any point; Runtime automatically applies the current Session and lineage. Ordering is preserved within one session by default; independent sessions may proceed in parallel.
- Plugin archives use `META-INF/kuudra-plugin/metadata.toml`, dependency-aware ClassLoaders, annotation-discovered components, and declared dependency ordering. Every JAR in `<home-directory>/plugins` is loaded; a dependency plugin's classes and resource enumeration are visible to its dependents, and invalid archives, cycles, duplicate IDs and missing dependencies are errors. Successful starts are recorded incrementally so a later dependent failure cleans itself and rolls back already-active dependencies. Annotation-created instances may implement `PluginComponentLifecycle`; their `initialize` runs after plugin activation and their reverse-order `destroy` runs before plugin shutdown. Plugin Actions remain Java-based for now; cross-language execution is a future bridge concern.
- Flow registration precompiles node option placeholder syntax into immutable `PlaceholderResolver.CompiledMap` instances. Event execution performs only dynamic four-scope lookup and result assembly. Keep regex scanning and expression path splitting out of the Runtime event hot path.
- Node options preserve native YAML numbers, booleans, maps and lists. Quoted strings shaped as JSON objects/arrays are parsed through the active `ContextCodec`; static JSON is parsed at Flow registration, while JSON containing placeholders is parsed after event-time interpolation. Numeric/boolean strings remain strings.
- `kuudra-web` is an adapter only. Its lifecycle is conceptually independent from App lifecycle: stopping App closes Runtime/plugins but must not make HTTP lifecycle endpoints disappear.
- Runtime, plugin and App lifecycle observability is expressed as `SystemEvent`; do not inject concrete loggers into those modules for ordinary lifecycle messages. `kuudra-logging` owns a private Logback context, renders bold colored `[KUUDRA]` console lines, writes `<home-directory>/logs/latest.log`, and archives it as `yyyy-MM-dd-N.log.gz` on normal kernel stop. The stopped run remains readable as `latest.log` until the next kernel start deletes that file and creates a new one. Home initialization must ensure `logs/` exists.

See `docs/kuudra-event-architecture.md`, `docs/kuudra-architecture.md`, and `docs/kuudra-app-management.md` before changing these boundaries.

## Current runnable bootstrap path

The minimal end-to-end path is implemented:

```text
kuudra-web
  -> KuudraApp
  -> config.yaml + flows/*.yaml
  -> plugin JAR scan
  -> metadata/dependency resolution and plugin startup
  -> Flow compilation and EventSource registration
  -> Event -> SessionAllocator -> Actor
```

- App configuration is owned entirely by `KuudraApp`; Web does not source Kuudra settings from Spring. Configuration is deeply merged in ascending priority: packaged `kuudra-app/src/main/resources/config.yaml`, `<home-directory>/config.yaml`, then an explicit `KuudraConfigResource` or configuration path passed while creating the App. For packaged Web, relative paths use the executable JAR directory as their base; standalone App defaults to the working directory.
- Global YAML contains root `home-directory`, runtime queue/worker settings and `global-context`. All schema keys use lowercase kebab-case. The packaged default sets `home-directory: .kuudra`. App initialization always ensures the home, fixed `plugins/`, and fixed `flows/` directories exist; if home `config.yaml` is absent, it copies the packaged `config.yaml` with create-only semantics and never overwrites an existing file. Deleting a broken home config and restarting restores packaged defaults.
- Each Flow YAML uses Compose-style `components` and `routes`. An `event-source` component is a separately controlled resource; other node types currently supported by the compiler are `event-adapter`, `event-processor`, `session-allocator`, and `actor`. The component `type` is declared by the node and `component` uses `namespace/component-id` (for example `hello-world/loop-emitter`), not the former duplicated type-prefixed form.
- Flow is a scope for component names, routing and sessions; starting, pausing or stopping a Flow changes its routing/session gate and does not implicitly start or stop its resources. Event sources are queried and controlled through App resource APIs and `/api/v1/app/flows/{flowId}/resources/event-sources/...`.
- Flow files live under fixed `<home-directory>/flows`. Plugin JARs live under fixed `<home-directory>/plugins`; they are local deployment artifacts and are not part of the core reactor.
- The exact startup procedure and failure behavior are documented in `docs/kuudra-bootstrap.md`.
- Logging event coverage, isolation and file rotation are documented in `docs/kuudra-logging.md`.

Current scope is a usable minimal kernel, not the complete long-term design. JSON/TOML loaders, reload/migration, `kuudra.system.*` Event handling, and cross-language bridges remain future work. All Flows are peers; do not reintroduce a control-plane Flow without an explicit architecture decision. YAML preserves placeholder templates; Runtime compiles their syntax at Flow registration and resolves values against Event, Session, global and Flow scopes for each execution. Supported syntax, performance model and limitations are documented in `docs/kuudra-bootstrap.md`; do not change them without matching tests.

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

For the HelloWorld smoke test: build the plugin, place its JAR in `.kuudra/plugins/`, write `.kuudra/config.yaml` and the referenced Flow YAML under `.kuudra/flows/`, launch `kuudra-web`, then query `GET /api/v1/app/status`. Expected result: `RUNNING`, one `hello-world` Flow, and HelloWorld Actor output.

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
