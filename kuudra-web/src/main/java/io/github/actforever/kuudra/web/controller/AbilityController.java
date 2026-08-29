package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

@RestController
@RequestMapping("/api/v1/runtime/abilities")
@Tag(name = "Runtime")
public final class AbilityController {
    private final KuudraApp app;
    public AbilityController(KuudraApp app) { this.app = app; }

    @Operation(summary = "列出 Ability")
    @GetMapping
    List<KuudraApp.Ability> abilities() { return app.abilities(); }

    @Operation(summary = "获取 Ability")
    @GetMapping("/{namespace}/{name}")
    KuudraApp.Ability ability(@PathVariable String namespace, @PathVariable String name) {
        return app.ability(namespace, name).orElseThrow(() -> notFound("Ability", namespace + "/" + name));
    }

    @Operation(summary = "异步控制 Ability")
    @ApiResponse(responseCode = "202", description = "控制请求已接受")
    @PostMapping("/{namespace}/{name}/{action}")
    ResponseEntity<ControlRequest> control(@PathVariable String namespace, @PathVariable String name,
                                           @PathVariable String action) {
        String ability = namespace + "/" + name;
        UUID requestId = UUID.randomUUID();
        app.controlAbility(namespace, name, action);
        return ResponseEntity.accepted().body(new ControlRequest(requestId, ability, action, "ACCEPTED", Instant.now()));
    }

    public record ControlRequest(UUID requestId, String ability, String action, String status, Instant acceptedAt) { }
}
