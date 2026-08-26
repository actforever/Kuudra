package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for live Session observation and controls. */
@RestController
@RequestMapping("/api/v1/runtime/sessions")
@Tag(name = "Sessions")
public class SessionController {
    private final KuudraApp app;

    public SessionController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "获取 Session")
    @GetMapping("/{sessionId}")
    KuudraApp.Session session(@PathVariable("sessionId") UUID sessionId) {
        return app.session(sessionId).orElseThrow(() -> notFound("Session", sessionId.toString()));
    }

    @Operation(summary = "请求取消 Session")
    @PostMapping("/{sessionId}/cancel")
    Map<String, Object> cancel(@PathVariable("sessionId") UUID sessionId) {
        if (!app.cancelSession(sessionId)) throw notFound("active session", sessionId.toString());
        return Map.of("sessionId", sessionId.toString(), "cancellationRequested", true);
    }

    @Operation(summary = "暂停 Session")
    @PostMapping("/{sessionId}/pause")
    KuudraApp.Session pause(@PathVariable("sessionId") UUID sessionId) {
        if (!app.pauseSession(sessionId)) throw notFound("active session", sessionId.toString());
        return session(sessionId);
    }

    @Operation(summary = "恢复 Session")
    @PostMapping("/{sessionId}/resume")
    KuudraApp.Session resume(@PathVariable("sessionId") UUID sessionId) {
        if (!app.resumeSession(sessionId)) throw notFound("paused session", sessionId.toString());
        return session(sessionId);
    }
}
