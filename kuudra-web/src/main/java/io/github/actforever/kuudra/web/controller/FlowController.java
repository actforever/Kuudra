package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for Flow resources. */
@RestController
@RequestMapping("/api/v1/runtime/flows")
@Tag(name = "Flows")
public class FlowController {
    private final KuudraApp app;

    public FlowController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出 Flow")
    @GetMapping
    List<KuudraApp.Flow> flows(@RequestParam(name = "namespace", required = false) String namespace) {
        return namespace == null ? app.flows() : app.flows(namespace);
    }

    @Operation(summary = "获取 Flow")
    @GetMapping("/{namespace}/{name}")
    KuudraApp.Flow flow(
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        return app.flow(namespace, name).orElseThrow(() -> notFound("Flow", namespace + "/" + name));
    }
}
