package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.api.AppSnapshot;
import io.github.actforever.kuudra.api.SystemEvent;
import io.github.actforever.kuudra.app.KuudraApp;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The sole HTTP adapter for the App facade; Runtime is never an HTTP resource. */
@RestController
@RequestMapping("/api/v1/app")
class AppController {
    private final KuudraApp app;
    AppController(KuudraApp app) { this.app = app; }
    @GetMapping AppSnapshot snapshot() { return app.snapshot(); }
    @PostMapping("/start") AppSnapshot start() { app.start(); return app.snapshot(); }
    @PostMapping("/stop") AppSnapshot stop() { app.stop(); return app.snapshot(); }
    @PostMapping("/restart") AppSnapshot restart() { app.restart(); return app.snapshot(); }
    @GetMapping("/flows") List<KuudraApp.Flow> flows() { return app.flows(); }
    @GetMapping("/flows/{flowId}") KuudraApp.Flow flow(@PathVariable String flowId) { return app.flow(flowId).orElseThrow(() -> notFound("Flow", flowId)); }
    @PostMapping("/flows/{flowId}/start") KuudraApp.Flow startFlow(@PathVariable String flowId) { app.activateFlow(flowId); return flow(flowId); }
    @PostMapping("/flows/{flowId}/pause") KuudraApp.Flow pauseFlow(@PathVariable String flowId) { app.pauseFlow(flowId); return flow(flowId); }
    @PostMapping("/flows/{flowId}/resume") KuudraApp.Flow resumeFlow(@PathVariable String flowId) { app.resumeFlow(flowId); return flow(flowId); }
    @PostMapping("/flows/{flowId}/stop") KuudraApp.Flow stopFlow(@PathVariable String flowId) { app.stopFlow(flowId); return flow(flowId); }
    @GetMapping("/sessions/{sessionId}") KuudraApp.Session session(@PathVariable UUID sessionId) { return app.session(sessionId).orElseThrow(() -> notFound("Session", sessionId.toString())); }
    @PostMapping("/sessions/{sessionId}/cancel") Map<String, Object> cancel(@PathVariable UUID sessionId) { if (!app.cancelSession(sessionId)) throw notFound("active session", sessionId.toString()); return Map.of("sessionId", sessionId.toString(), "cancellationRequested", true); }
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events() {
        SseEmitter emitter = new SseEmitter(0L);
        AutoCloseable subscription = app.systemEvents().subscribe(event -> send(emitter, event));
        Runnable cleanup = () -> { try { subscription.close(); } catch (Exception ignored) { } };
        emitter.onCompletion(cleanup); emitter.onTimeout(cleanup); emitter.onError(error -> cleanup.run()); return emitter;
    }
    private static void send(SseEmitter emitter, SystemEvent event) { try { emitter.send(SseEmitter.event().id(event.id().toString()).name(event.type()).data(event)); } catch (IOException failure) { emitter.completeWithError(failure); } }
    private static ResponseStatusException notFound(String type, String id) { return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id); }
}
