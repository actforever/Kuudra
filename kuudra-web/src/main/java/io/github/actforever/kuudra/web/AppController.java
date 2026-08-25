package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.api.AppSnapshot;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The sole HTTP adapter for the App facade; Runtime is never an HTTP resource. */
@RestController
@RequestMapping("/api/v1/app")
class AppController {
    private final KuudraApp app;
    AppController(KuudraApp app) { this.app = app; }
    @Operation(summary = "获取 App 快照", tags = "App 生命周期")
    @GetMapping AppSnapshot snapshot() { return app.snapshot(); }
    @Operation(summary = "获取内核详细状态", tags = "App 生命周期")
    @GetMapping("/status") KuudraApp.Status status() { return app.status(); }
    @Operation(summary = "获取暂停安全点生成的内核检查点", tags = "App 生命周期")
    @GetMapping("/checkpoint") KuudraApp.KernelCheckpoint checkpoint() {
        return app.checkpoint().orElseThrow(() -> notFound("Kernel checkpoint", "current"));
    }
    @Operation(summary = "启动 App 内核", tags = "App 生命周期")
    @PostMapping("/start") AppSnapshot start() { app.start(); return app.snapshot(); }
    @Operation(summary = "停止 App 内核", tags = "App 生命周期")
    @PostMapping("/stop") AppSnapshot stop() { app.stop(); return app.snapshot(); }
    @Operation(summary = "暂停 App 内核", tags = "App 生命周期")
    @PostMapping("/pause") AppSnapshot pause() { app.pause(); return app.snapshot(); }
    @Operation(summary = "恢复 App 内核", tags = "App 生命周期")
    @PostMapping("/resume") AppSnapshot resume() { app.resume(); return app.snapshot(); }
    @Operation(summary = "重启 App 内核", tags = "App 生命周期")
    @PostMapping("/restart") AppSnapshot restart() { app.restart(); return app.snapshot(); }
    @Operation(summary = "列出全部 Flow", tags = "Flow 管理")
    @GetMapping("/flows") List<KuudraApp.Flow> flows() { return app.flows(); }
    @Operation(summary = "获取 Flow", tags = "Flow 管理")
    @GetMapping("/flows/{flowId}") KuudraApp.Flow flow(@PathVariable("flowId") String flowId) { return app.flow(flowId).orElseThrow(() -> notFound("Flow", flowId)); }
    @Operation(summary = "列出命名空间内的 Flow", tags = "Flow 管理")
    @GetMapping("/namespaces/{namespace}/flows") List<KuudraApp.Flow> namespacedFlows(@PathVariable("namespace") String namespace) { return app.flows(namespace); }
    @Operation(summary = "获取命名空间内的 Flow", tags = "Flow 管理")
    @GetMapping("/namespaces/{namespace}/flows/{name}") KuudraApp.Flow namespacedFlow(
            @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
        return app.flow(namespace, name).orElseThrow(() -> notFound("Flow", namespace + "/" + name));
    }
    @Operation(summary = "列出全部 EventSource 资源", tags = "EventSource 资源")
    @GetMapping("/resources/event-sources") List<KuudraApp.Resource> eventSources() { return app.eventSources(); }
    @Operation(summary = "列出 Flow 的 EventSource 资源", tags = "EventSource 资源")
    @GetMapping("/flows/{flowId}/resources/event-sources") List<KuudraApp.Resource> flowEventSources(@PathVariable("flowId") String flowId) { return call(() -> app.eventSources(flowId), "Flow", flowId); }
    @Operation(summary = "获取 EventSource 资源", tags = "EventSource 资源")
    @GetMapping("/flows/{flowId}/resources/event-sources/{resourceId}") KuudraApp.Resource eventSource(@PathVariable("flowId") String flowId, @PathVariable("resourceId") String resourceId) { return call(() -> app.eventSource(flowId, resourceId), "EventSource", flowId + "/" + resourceId); }
    @Operation(summary = "启动 EventSource 资源", tags = "EventSource 资源")
    @PostMapping("/flows/{flowId}/resources/event-sources/{resourceId}/start") KuudraApp.Resource startEventSource(@PathVariable("flowId") String flowId, @PathVariable("resourceId") String resourceId) { return call(() -> app.startEventSource(flowId, resourceId), "EventSource", flowId + "/" + resourceId); }
    @Operation(summary = "停止 EventSource 资源", tags = "EventSource 资源")
    @PostMapping("/flows/{flowId}/resources/event-sources/{resourceId}/stop") KuudraApp.Resource stopEventSource(@PathVariable("flowId") String flowId, @PathVariable("resourceId") String resourceId) { return call(() -> app.stopEventSource(flowId, resourceId), "EventSource", flowId + "/" + resourceId); }
    @Operation(summary = "列出全部 Component 资源实例", tags = "Component 资源")
    @GetMapping("/resources/components") List<KuudraApp.ComponentResource> componentResources() { return app.componentResources(); }
    @Operation(summary = "按类型列出 Component 资源实例", tags = "Component 资源")
    @GetMapping("/resources/components/{type}") List<KuudraApp.ComponentResource> componentResources(
            @PathVariable("type") String type) { return app.componentResources(type); }
    @Operation(summary = "获取 Component 资源实例", tags = "Component 资源")
    @GetMapping("/resources/components/{type}/{namespace}/{name}") KuudraApp.ComponentResource componentResource(
            @PathVariable("type") String type, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        return app.componentResource(type, namespace, name)
                .orElseThrow(() -> notFound("Component resource", type + "/" + namespace + "/" + name));
    }
    @Operation(summary = "列出命名空间内的资源", tags = "Component 资源")
    @GetMapping("/namespaces/{namespace}/resources") List<KuudraApp.ComponentResource> namespacedResources(
            @PathVariable("namespace") String namespace) { return app.resourcesInNamespace(namespace); }
    @Operation(summary = "按 kind/namespace/name 获取资源", tags = "Component 资源")
    @GetMapping("/resources/{kind}/{namespace}/{name}") KuudraApp.ComponentResource resource(
            @PathVariable("kind") String kind, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        return app.resource(kind, namespace, name).orElseThrow(() -> notFound("Resource", kind + "/" + namespace + "/" + name));
    }
    @Operation(summary = "修改资源期望状态并触发 App 调谐", tags = "Component 资源")
    @PostMapping("/resources/{kind}/{namespace}/{name}/desired-state/{desiredState}") KuudraApp.ComponentResource desiredState(
            @PathVariable("kind") String kind, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name, @PathVariable("desiredState") String desiredState) {
        return call(() -> app.setDesiredState(kind, namespace, name, desiredState), "Resource", kind + "/" + namespace + "/" + name);
    }
    @Operation(summary = "获取 Session", tags = "Session 管理")
    @GetMapping("/sessions/{sessionId}") KuudraApp.Session session(@PathVariable("sessionId") UUID sessionId) { return app.session(sessionId).orElseThrow(() -> notFound("Session", sessionId.toString())); }
    @Operation(summary = "请求取消 Session", tags = "Session 管理")
    @PostMapping("/sessions/{sessionId}/cancel") Map<String, Object> cancel(@PathVariable("sessionId") UUID sessionId) { if (!app.cancelSession(sessionId)) throw notFound("active session", sessionId.toString()); return Map.of("sessionId", sessionId.toString(), "cancellationRequested", true); }
    @Operation(summary = "暂停 Session", tags = "Session 管理")
    @PostMapping("/sessions/{sessionId}/pause") KuudraApp.Session pauseSession(@PathVariable("sessionId") UUID sessionId) { if (!app.pauseSession(sessionId)) throw notFound("active session", sessionId.toString()); return session(sessionId); }
    @Operation(summary = "恢复 Session", tags = "Session 管理")
    @PostMapping("/sessions/{sessionId}/resume") KuudraApp.Session resumeSession(@PathVariable("sessionId") UUID sessionId) { if (!app.resumeSession(sessionId)) throw notFound("paused session", sessionId.toString()); return session(sessionId); }
    @Operation(summary = "查询资源调谐状态", tags = "Component 资源")
    @GetMapping("/resources/state") List<io.github.actforever.kuudra.state.ResourceStateStore.ResourceState> resourceStates() { return app.resourceStates(); }
    @Operation(summary = "列出已加载插件", tags = "插件与组件")
    @GetMapping("/plugins") List<KuudraApp.Plugin> plugins() { return app.plugins(); }
    @Operation(summary = "获取插件及其组件", tags = "插件与组件")
    @GetMapping("/plugins/{namespace}/{pluginId}") KuudraApp.Plugin plugin(
            @PathVariable("namespace") String namespace, @PathVariable("pluginId") String pluginId) {
        return app.plugin(namespace, pluginId).orElseThrow(() -> notFound("Plugin", namespace + "/" + pluginId));
    }
    @Operation(summary = "列出插件组件", tags = "插件与组件")
    @GetMapping("/plugins/{namespace}/{pluginId}/components") List<KuudraApp.Component> pluginComponents(
            @PathVariable("namespace") String namespace, @PathVariable("pluginId") String pluginId) {
        return call(() -> app.pluginComponents(namespace, pluginId), "Plugin", namespace + "/" + pluginId);
    }
    @Operation(summary = "按插件身份获取完整组件文档", tags = "插件与组件")
    @GetMapping("/plugins/{namespace}/{pluginId}/components/{type}/{name}/documentation")
    KuudraApp.ComponentDocumentation pluginComponentDocumentation(
            @PathVariable("namespace") String namespace, @PathVariable("pluginId") String pluginId,
            @PathVariable("type") String type, @PathVariable("name") String name) {
        return app.pluginComponentDocumentation(namespace, pluginId, type, name).orElseThrow(() ->
                notFound("Plugin Component", namespace + "/" + pluginId + "/" + type + "/" + name));
    }
    @Operation(summary = "列出全部插件组件", tags = "插件与组件")
    @GetMapping("/components") List<KuudraApp.Component> components() { return app.components(); }
    @Operation(summary = "获取组件结构化文档", tags = "插件与组件")
    @GetMapping("/components/{type}/{namespace}/{name}") KuudraApp.Component component(
            @PathVariable("type") String type, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponent(reference).orElseThrow(() -> notFound("Component", reference));
    }
    @Operation(summary = "获取完整组件说明文档", tags = "插件与组件")
    @GetMapping("/components/{type}/{namespace}/{name}/documentation")
    KuudraApp.ComponentDocumentation componentDocumentation(
            @PathVariable("type") String type, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponentDocumentation(reference).orElseThrow(() -> notFound("Component", reference));
    }
    @Operation(summary = "订阅系统事件", tags = "系统事件")
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        EventStreamSubscription stream = new EventStreamSubscription(emitter);
        stream.attach(app.systemEvents().subscribe(stream::send));
        emitter.onCompletion(stream::close); emitter.onTimeout(stream::close); emitter.onError(error -> stream.close());
        return emitter;
    }
    static final class EventStreamSubscription {
        private final SseEmitter emitter;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicReference<AutoCloseable> subscription = new java.util.concurrent.atomic.AtomicReference<>();
        EventStreamSubscription(SseEmitter emitter) { this.emitter = emitter; }
        void attach(AutoCloseable value) {
            if (!subscription.compareAndSet(null, value)) { closeQuietly(value); return; }
            if (closed.get()) closeQuietly(subscription.getAndSet(null));
        }
        void send(SystemEvent event) {
            if (closed.get()) return;
            try { emitter.send(SseEmitter.event().id(event.id().toString()).name(event.type()).data(event)); }
            catch (IOException | IllegalStateException disconnected) { close(); }
        }
        void close() {
            closed.set(true);
            closeQuietly(subscription.getAndSet(null));
        }
        boolean closed() { return closed.get(); }
        private static void closeQuietly(AutoCloseable value) {
            if (value != null) try { value.close(); } catch (Exception ignored) { }
        }
    }
    private static <T> T call(java.util.concurrent.Callable<T> call, String type, String id) { try { return call.call(); } catch (IllegalArgumentException error) { throw notFound(type, id); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static ResponseStatusException notFound(String type, String id) { return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id); }
}
