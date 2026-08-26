package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.call;

/** HTTP adapter for EventSource resource observation and controls. */
@RestController
@RequestMapping("/api/v1/app")
public class EventSourceController {
    private final KuudraApp app;

    public EventSourceController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出全部 EventSource 资源", tags = "EventSource 资源")
    @GetMapping("/resources/event-sources")
    List<KuudraApp.Resource> eventSources() {
        return app.eventSources();
    }

    @Operation(summary = "列出 Flow 的 EventSource 资源", tags = "EventSource 资源")
    @GetMapping("/flows/{flowId}/resources/event-sources")
    List<KuudraApp.Resource> flowEventSources(@PathVariable("flowId") String flowId) {
        return call(() -> app.eventSources(flowId), "Flow", flowId);
    }

    @Operation(summary = "获取 EventSource 资源", tags = "EventSource 资源")
    @GetMapping("/flows/{flowId}/resources/event-sources/{resourceId}")
    KuudraApp.Resource eventSource(
            @PathVariable("flowId") String flowId, @PathVariable("resourceId") String resourceId) {
        return call(() -> app.eventSource(flowId, resourceId), "EventSource", flowId + "/" + resourceId);
    }

    @Operation(summary = "启动 EventSource 资源", tags = "EventSource 资源")
    @PostMapping("/flows/{flowId}/resources/event-sources/{resourceId}/start")
    KuudraApp.Resource startEventSource(
            @PathVariable("flowId") String flowId, @PathVariable("resourceId") String resourceId) {
        return call(() -> app.startEventSource(flowId, resourceId), "EventSource", flowId + "/" + resourceId);
    }

    @Operation(summary = "停止 EventSource 资源", tags = "EventSource 资源")
    @PostMapping("/flows/{flowId}/resources/event-sources/{resourceId}/stop")
    KuudraApp.Resource stopEventSource(
            @PathVariable("flowId") String flowId, @PathVariable("resourceId") String resourceId) {
        return call(() -> app.stopEventSource(flowId, resourceId), "EventSource", flowId + "/" + resourceId);
    }
}
