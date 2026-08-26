package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.github.actforever.kuudra.state.ResourceStateStore;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.call;
import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for manifest Component resource observation and desired-state controls. */
@RestController
@RequestMapping("/api/v1/app")
public class ComponentResourceController {
    private final KuudraApp app;

    public ComponentResourceController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出全部 Component 资源实例", tags = "Component 资源")
    @GetMapping("/resources/components")
    List<KuudraApp.ComponentResource> componentResources() {
        return app.componentResources();
    }

    @Operation(summary = "按类型列出 Component 资源实例", tags = "Component 资源")
    @GetMapping("/resources/components/{type}")
    List<KuudraApp.ComponentResource> componentResources(@PathVariable("type") String type) {
        return app.componentResources(type);
    }

    @Operation(summary = "获取 Component 资源实例", tags = "Component 资源")
    @GetMapping("/resources/components/{type}/{namespace}/{name}")
    KuudraApp.ComponentResource componentResource(
            @PathVariable("type") String type, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        return app.componentResource(type, namespace, name)
                .orElseThrow(() -> notFound("Component resource", type + "/" + namespace + "/" + name));
    }

    @Operation(summary = "列出命名空间内的资源", tags = "Component 资源")
    @GetMapping("/namespaces/{namespace}/resources")
    List<KuudraApp.ComponentResource> namespacedResources(@PathVariable("namespace") String namespace) {
        return app.resourcesInNamespace(namespace);
    }

    @Operation(summary = "按 kind/namespace/name 获取资源", tags = "Component 资源")
    @GetMapping("/resources/{kind}/{namespace}/{name}")
    KuudraApp.ComponentResource resource(
            @PathVariable("kind") String kind, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        return app.resource(kind, namespace, name)
                .orElseThrow(() -> notFound("Resource", kind + "/" + namespace + "/" + name));
    }

    @Operation(summary = "修改资源期望状态并触发 App 调谐", tags = "Component 资源")
    @PostMapping("/resources/{kind}/{namespace}/{name}/desired-state/{desiredState}")
    KuudraApp.ComponentResource desiredState(
            @PathVariable("kind") String kind, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name, @PathVariable("desiredState") String desiredState) {
        return call(() -> app.setDesiredState(kind, namespace, name, desiredState),
                "Resource", kind + "/" + namespace + "/" + name);
    }

    @Operation(summary = "查询资源调谐状态", tags = "Component 资源")
    @GetMapping("/resources/state")
    List<ResourceStateStore.ResourceState> resourceStates() {
        return app.resourceStates();
    }
}
