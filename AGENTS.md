# Kuudra Agent Handoff Guide

## Maintenance contract

- This file is a living handoff document, not a static specification. After an Agent makes an important architectural, module, build, configuration, plugin, lifecycle, or deployment change, update the relevant sections here in the same change set.
- `docs/` is the project's design and operational documentation. Keep it synchronized with implementation changes; do not treat code as the only source of truth.
- Preserve unrelated user changes. In particular, `docs/session-arch.png` is currently user-owned and untracked; do not add, remove, or overwrite it unless explicitly requested.
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
| `kuudra-logging` | Logging support. |

`plugins/` is intentionally excluded by the root `.gitignore`. It is a local Maven aggregator for plugin implementations, currently containing `kuudra-hello-world-plugin`. It is not part of the Kuudra core reactor. Plugin builds expect `kuudra-api` and `kuudra-plugin` artifacts to be available in the local Maven repository.

Runtime-created plugin homes live under `.kuudra/plugin-homes`, not under `plugins/`. Do not reintroduce that collision.

## Architecture decisions already made

- The domain is event-driven. Main extension points are `EventSource`, `EventAdapter`, `EventProcessor`, and `Actor`.
- `Event` is the common message model. `EventData` is immutable and namespace-keyed to prevent payload collisions between plugins.
- `SessionAllocator` is the only Flow node that creates sessions. Events without a session must pass it before reaching an Actor.
- Actor-originated Events normally inherit their session when routed directly to another Actor. Routing an event back to an EventProcessor or SessionAllocator detaches its session and records lineage, so a new child session can be allocated.
- Component references must use `type/namespace/name`, for example `event-source/hello-world/loop-emitter` and `actor/hello-world/console-printer`.
- A `KuudraFlow` is the runtime scheduling unit. A single `KuudraRuntime` is owned by the active `KuudraApp`.
- Actors execute asynchronously. Ordering is preserved within one session by default; independent sessions may proceed in parallel.
- Plugin archives use `META-INF/kuudra-plugin/metadata.toml`, isolated ClassLoaders, annotation-discovered components, and declared dependency ordering. Plugin Actions remain Java-based for now; cross-language execution is a future bridge concern.
- `kuudra-web` is an adapter only. Its lifecycle is conceptually independent from App lifecycle: stopping App closes Runtime/plugins but must not make HTTP lifecycle endpoints disappear.

See `docs/kuudra-event-architecture.md`, `docs/kuudra-architecture.md`, and `docs/kuudra-app-management.md` before changing these boundaries.

## Current runnable bootstrap path

The minimal end-to-end path is implemented:

```text
kuudra-web
  -> KuudraApp
  -> kuudra.yaml + flows/*.yaml
  -> plugin JAR scan
  -> metadata/dependency resolution and plugin startup
  -> Flow compilation and EventSource registration
  -> Event -> SessionAllocator -> Actor
```

- `KUUDRA_CONFIG_PATH` points `kuudra-web` to a `kuudra.yaml`; without it, Web creates an empty App that can still be controlled through HTTP.
- Global YAML contains runtime queue/worker settings, plugin directories, `flowsDirectory`, and `globalContext`.
- Each Flow YAML declares nodes, edges, and source bindings. Node types currently supported by the compiler: `event-adapter`, `event-processor`, `session-allocator`, `actor`.
- Examples live in `examples/kuudra.yaml` and `examples/flows/hello-world.yaml`. JARs in `examples/plugins/` are ignored and must be built/copied locally.
- The exact startup procedure and failure behavior are documented in `docs/kuudra-bootstrap.md`.

Current scope is a usable minimal kernel, not the complete long-term design. JSON/TOML loaders, placeholder evaluation/injection, reload/migration, richer control-plane Flow behavior, and cross-language bridges remain future work. Do not claim them as implemented without adding code, tests, and documentation.

## Build and verification

Core reactor:

```powershell
mvn test
```

Local plugin aggregator (after core artifacts are installed):

```powershell
mvn -pl kuudra-api,kuudra-plugin -am install -DskipTests
mvn -f plugins/pom.xml clean package
```

The current machine has previously failed full forked tests because of a small Windows paging file. Running Surefire in-process can then fail Spring/Mockito's ByteBuddy self-attach requirement. These are environment failures, not established product failures. Prefer targeted module tests and a real packaged Web bootstrap verification; report the exact command and limitation in handoff/final output.

For the HelloWorld smoke test: build the plugin, copy its JAR into `examples/plugins/`, set `KUUDRA_CONFIG_PATH` to the absolute path of `examples/kuudra.yaml`, launch `kuudra-web`, then query `GET /api/v1/app/status`. Expected result: `RUNNING`, one `hello-world` Flow, and HelloWorld Actor output.

## Working rules

- Prefer `rg` for searches and `apply_patch` for source/document edits.
- Do not reset or discard a dirty worktree. Avoid destructive operations; resolve exact paths first.
- Keep `pom.xml` module boundaries intentional. Core must not regain plugin implementation modules.
- When changing configuration schema, update the loader, model, sample YAML, tests, and `docs/kuudra-bootstrap.md` together.
- When changing public component contracts or Flow/session semantics, update `kuudra-api`, runtime tests, architecture docs, and this file together.
- When changing plugin discovery/metadata/lifecycle, update the plugin module, plugin build instructions, and examples together.
- HTTP endpoints must be phrased in terms of App. Do not add Runtime-named Web APIs.
