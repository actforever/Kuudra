package io.github.actforever.kuudra.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** Dashboard-facing proxy for the standalone App daemon. */
@RestController
@RequestMapping("/api/v1/app")
class AppController {
    private final AppHttpClient app;
    AppController(AppHttpClient app) { this.app = app; }
    @GetMapping Object snapshot() { return request(() -> app.get("/api/v1/app")); }
    @PostMapping("/start") Object start() { return request(() -> app.post("/api/v1/app/start")); }
    @PostMapping("/stop") Object stop() { return request(() -> app.post("/api/v1/app/stop")); }
    @PostMapping("/restart") Object restart() { return request(() -> app.post("/api/v1/app/restart")); }
    @PostMapping("/terminate") Object terminate() { return request(() -> app.post("/api/v1/app/terminate")); }
    @GetMapping("/flows") Object flows() { return request(() -> app.get("/api/v1/app/flows")); }
    @GetMapping("/flows/{flowId}") Object flow(@PathVariable String flowId) { return request(() -> app.get("/api/v1/app/flows/" + flowId)); }
    @PostMapping("/flows/{flowId}/start") Object startFlow(@PathVariable String flowId) { return request(() -> app.post("/api/v1/app/flows/" + flowId + "/start")); }
    @PostMapping("/flows/{flowId}/pause") Object pauseFlow(@PathVariable String flowId) { return request(() -> app.post("/api/v1/app/flows/" + flowId + "/pause")); }
    @PostMapping("/flows/{flowId}/resume") Object resumeFlow(@PathVariable String flowId) { return request(() -> app.post("/api/v1/app/flows/" + flowId + "/resume")); }
    @PostMapping("/flows/{flowId}/stop") Object stopFlow(@PathVariable String flowId) { return request(() -> app.post("/api/v1/app/flows/" + flowId + "/stop")); }
    @PostMapping("/sessions/{sessionId}/cancel") Object cancel(@PathVariable String sessionId) { return request(() -> app.post("/api/v1/app/sessions/" + sessionId + "/cancel")); }
    private static Object request(java.util.function.Supplier<Object> call) {
        try { return call.get(); } catch (RestClientException failure) { return Map.of("status", "UNREACHABLE", "detail", failure.getMessage()); }
    }
}
