package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

@RestController
@RequestMapping("/api/v1/runtime/profiles")
@Tag(name = "Runtime")
public final class ProfileController {
    private final KuudraApp app;

    public ProfileController(KuudraApp app) { this.app = app; }

    @Operation(summary = "列出 KuudraProfile")
    @GetMapping
    List<KuudraApp.Profile> profiles() { return app.profiles(); }

    @Operation(summary = "获取 KuudraProfile")
    @GetMapping("/{name}")
    KuudraApp.Profile profile(@PathVariable("name") String name) {
        return app.profile(name).orElseThrow(() -> notFound("KuudraProfile", name));
    }

    @Operation(summary = "异步激活 KuudraProfile")
    @ApiResponse(responseCode = "202", description = "激活请求已接受")
    @PostMapping("/{name}/activate")
    ResponseEntity<ActivationRequest> activate(@PathVariable("name") String name) {
        UUID requestId = UUID.randomUUID();
        app.activateProfile(name);
        return ResponseEntity.accepted().body(new ActivationRequest(requestId, name, "ACCEPTED", Instant.now()));
    }

    public record ActivationRequest(UUID requestId, String profile, String status, Instant acceptedAt) { }
}
