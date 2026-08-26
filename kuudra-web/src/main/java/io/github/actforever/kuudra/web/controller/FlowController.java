package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for Flow declarations and kernel resource documentation. */
@RestController
@RequestMapping("/api/v1/app")
public class FlowController {
    private final KuudraApp app;

    public FlowController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出全部 Flow", tags = "Flow 管理")
    @GetMapping("/flows")
    List<KuudraApp.Flow> flows() {
        return app.flows();
    }

    @Operation(summary = "获取 Flow", tags = "Flow 管理")
    @GetMapping("/flows/{flowId}")
    KuudraApp.Flow flow(@PathVariable("flowId") String flowId) {
        return app.flow(flowId).orElseThrow(() -> notFound("Flow", flowId));
    }

    @Operation(summary = "列出命名空间内的 Flow", tags = "Flow 管理")
    @GetMapping("/namespaces/{namespace}/flows")
    List<KuudraApp.Flow> namespacedFlows(@PathVariable("namespace") String namespace) {
        return app.flows(namespace);
    }

    @Operation(summary = "获取命名空间内的 Flow", tags = "Flow 管理")
    @GetMapping("/namespaces/{namespace}/flows/{name}")
    KuudraApp.Flow namespacedFlow(
            @PathVariable("namespace") String namespace, @PathVariable("name") String name) {
        return app.flow(namespace, name).orElseThrow(() -> notFound("Flow", namespace + "/" + name));
    }

    @Operation(summary = "列出内核资源说明文档", tags = "Flow 管理")
    @GetMapping("/resource-documentation")
    List<KuudraApp.ResourceDocumentation> resourceDocumentations() {
        return app.resourceDocumentations();
    }

    @Operation(summary = "获取指定内核资源说明文档", tags = "Flow 管理")
    @GetMapping("/resource-documentation/{namespace}/{kind}")
    KuudraApp.ResourceDocumentation resourceDocumentation(
            @PathVariable("namespace") String namespace, @PathVariable("kind") String kind) {
        return app.resourceDocumentation(namespace, kind)
                .orElseThrow(() -> notFound("Resource documentation", namespace + "/" + kind));
    }
}
