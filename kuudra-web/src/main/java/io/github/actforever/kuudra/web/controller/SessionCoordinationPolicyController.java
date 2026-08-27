package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for declarative SessionCoordinationPolicy resources. */
@RestController
@RequestMapping("/api/v1/runtime/session-coordination-policies")
@Tag(name = "Runtime")
public final class SessionCoordinationPolicyController {
    private final KuudraApp app;

    public SessionCoordinationPolicyController(KuudraApp app) { this.app = app; }

    @Operation(summary = "列出 Session 协调策略")
    @GetMapping
    List<KuudraApp.CoordinationPolicy> list() { return app.sessionCoordinationPolicies(); }

    @Operation(summary = "获取 Session 协调策略")
    @GetMapping("/{namespace}/{name}")
    KuudraApp.CoordinationPolicy get(@PathVariable String namespace, @PathVariable String name) {
        return app.sessionCoordinationPolicy(namespace, name)
                .orElseThrow(() -> notFound("SessionCoordinationPolicy", namespace + "/" + name));
    }
}
