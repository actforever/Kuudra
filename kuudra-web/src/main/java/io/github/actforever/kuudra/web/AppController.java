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
    @Operation(summary = "启动 App 内核", tags = "App 生命周期")
    @PostMapping("/start") AppSnapshot start() { app.start(); return app.snapshot(); }
    @Operation(summary = "停止 App 内核", tags = "App 生命周期")
    @PostMapping("/stop") AppSnapshot stop() { app.stop(); return app.snapshot(); }
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
    @Operation(summary = "启动命名空间内的 Flow", tags = "Flow 管理")
    @PostMapping("/namespaces/{namespace}/flows/{name}/start") KuudraApp.Flow startNamespacedFlow(
            @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
        app.activateFlow(namespace + "/" + name); return namespacedFlow(namespace, name);
    }
    @Operation(summary = "暂停命名空间内的 Flow", tags = "Flow 管理")
    @PostMapping("/namespaces/{namespace}/flows/{name}/pause") KuudraApp.Flow pauseNamespacedFlow(
            @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
        app.pauseFlow(namespace + "/" + name); return namespacedFlow(namespace, name);
    }
    @Operation(summary = "恢复命名空间内的 Flow", tags = "Flow 管理")
    @PostMapping("/namespaces/{namespace}/flows/{name}/resume") KuudraApp.Flow resumeNamespacedFlow(
            @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
        app.resumeFlow(namespace + "/" + name); return namespacedFlow(namespace, name);
    }
    @Operation(summary = "停止命名空间内的 Flow", tags = "Flow 管理")
    @PostMapping("/namespaces/{namespace}/flows/{name}/stop") KuudraApp.Flow stopNamespacedFlow(
            @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
        app.stopFlow(namespace + "/" + name); return namespacedFlow(namespace, name);
    }
    @Operation(summary = "启动 Flow", tags = "Flow 管理")
    @PostMapping("/flows/{flowId}/start") KuudraApp.Flow startFlow(@PathVariable("flowId") String flowId) { app.activateFlow(flowId); return flow(flowId); }
    @Operation(summary = "暂停 Flow", tags = "Flow 管理")
    @PostMapping("/flows/{flowId}/pause") KuudraApp.Flow pauseFlow(@PathVariable("flowId") String flowId) { app.pauseFlow(flowId); return flow(flowId); }
    @Operation(summary = "恢复 Flow", tags = "Flow 管理")
    @PostMapping("/flows/{flowId}/resume") KuudraApp.Flow resumeFlow(@PathVariable("flowId") String flowId) { app.resumeFlow(flowId); return flow(flowId); }
    @Operation(summary = "停止 Flow", tags = "Flow 管理")
    @PostMapping("/flows/{flowId}/stop") KuudraApp.Flow stopFlow(@PathVariable("flowId") String flowId) { app.stopFlow(flowId); return flow(flowId); }
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
    @Operation(summary = "获取 Session", tags = "Session 管理")
    @GetMapping("/sessions/{sessionId}") KuudraApp.Session session(@PathVariable("sessionId") UUID sessionId) { return app.session(sessionId).orElseThrow(() -> notFound("Session", sessionId.toString())); }
    @Operation(summary = "请求取消 Session", tags = "Session 管理")
    @PostMapping("/sessions/{sessionId}/cancel") Map<String, Object> cancel(@PathVariable("sessionId") UUID sessionId) { if (!app.cancelSession(sessionId)) throw notFound("active session", sessionId.toString()); return Map.of("sessionId", sessionId.toString(), "cancellationRequested", true); }
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
    @Operation(summary = "列出全部插件组件", tags = "插件与组件")
    @GetMapping("/components") List<KuudraApp.Component> components() { return app.components(); }
    @Operation(summary = "获取组件结构化文档", tags = "插件与组件")
    @GetMapping("/components/{type}/{namespace}/{name}") KuudraApp.Component component(
            @PathVariable("type") String type, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponent(reference).orElseThrow(() -> notFound("Component", reference));
    }
    @Operation(summary = "订阅系统事件", tags = "系统事件")
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        AutoCloseable subscription = app.systemEvents().subscribe(event -> send(emitter, event));
        Runnable cleanup = () -> { try { subscription.close(); } catch (Exception ignored) { } };
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(error -> cleanup.run()); return emitter;
    }
    private static void send(SseEmitter emitter, SystemEvent event) { try { emitter.send(SseEmitter.event().id(event.id().toString()).name(event.type()).data(event)); } catch (IOException failure) { emitter.completeWithError(failure); } }
    private static <T> T call(java.util.concurrent.Callable<T> call, String type, String id) { try { return call.call(); } catch (IllegalArgumentException error) { throw notFound(type, id); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static ResponseStatusException notFound(String type, String id) { return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id); }
}
