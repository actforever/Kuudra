package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for kernel resource documentation. */
@RestController
@RequestMapping("/api/v1/resource-documentation")
@Tag(name = "Resource Documentation")
public class ResourceDocumentationController {
    private final KuudraApp app;

    public ResourceDocumentationController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出内核资源说明文档")
    @GetMapping
    List<KuudraApp.ResourceDocumentation> resourceDocumentations() {
        return app.resourceDocumentations();
    }

    @Operation(summary = "获取内核资源说明文档")
    @GetMapping("/{namespace}/{kind}")
    KuudraApp.ResourceDocumentation resourceDocumentation(
            @PathVariable("namespace") String namespace,
            @PathVariable("kind") String kind) {
        return app.resourceDocumentation(namespace, kind)
                .orElseThrow(() -> notFound("Resource documentation", namespace + "/" + kind));
    }
}
