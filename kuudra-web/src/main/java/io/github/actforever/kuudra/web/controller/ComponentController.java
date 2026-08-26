package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.github.actforever.kuudra.state.ResourceStateStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.call;
import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for manifest-declared Component instances. */
@RestController
@RequestMapping("/api/v1/runtime/components")
@Tag(name = "Components")
public class ComponentController {
    private final KuudraApp app;

    public ComponentController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出 Component 实例")
    @GetMapping
    List<KuudraApp.ComponentResource> components(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "namespace", required = false) String namespace) {
        if (type != null && namespace != null) {
            return app.componentResources(type).stream()
                    .filter(component -> component.namespace().equals(namespace))
                    .toList();
        }
        if (type != null) return app.componentResources(type);
        if (namespace != null) return app.resourcesInNamespace(namespace);
        return app.componentResources();
    }

    @Operation(summary = "获取 Component 实例")
    @GetMapping("/{kind}/{namespace}/{name}")
    KuudraApp.ComponentResource component(
            @PathVariable("kind") String kind,
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        return app.resource(kind, namespace, name)
                .orElseThrow(() -> notFound("Component", kind + "/" + namespace + "/" + name));
    }

    @Operation(summary = "修改 Component 实例期望状态并触发 App 调谐")
    @PostMapping("/{kind}/{namespace}/{name}/desired-state/{desiredState}")
    KuudraApp.ComponentResource desiredState(
            @PathVariable("kind") String kind,
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name,
            @PathVariable("desiredState") String desiredState) {
        return call(() -> app.setDesiredState(kind, namespace, name, desiredState),
                "Component", kind + "/" + namespace + "/" + name);
    }

    @Operation(summary = "查询 Component 实例调谐状态")
    @GetMapping("/reconciliation-states")
    List<ResourceStateStore.ResourceState> reconciliationStates() {
        return app.resourceStates();
    }
}
