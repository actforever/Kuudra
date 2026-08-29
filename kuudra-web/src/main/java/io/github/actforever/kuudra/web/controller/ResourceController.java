package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

@RestController
@RequestMapping("/api/v1/runtime/resources")
@Tag(name = "Runtime")
public final class ResourceController {
    private final KuudraApp app;
    public ResourceController(KuudraApp app) { this.app = app; }

    @Operation(summary = "列出 Resource")
    @GetMapping
    List<KuudraApp.ManifestResource> resources() { return app.manifestResources(); }

    @Operation(summary = "获取 Resource")
    @GetMapping("/{kind}/{namespace}/{name}")
    KuudraApp.ManifestResource resource(@PathVariable String kind, @PathVariable String namespace,
                                        @PathVariable String name) {
        String id = kind + "/" + namespace + "/" + name;
        return app.manifestResources().stream().filter(resource -> resource.id().equals(id)).findFirst()
                .orElseThrow(() -> notFound("Resource", id));
    }
}
