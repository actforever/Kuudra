package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.api.FlowSnapshot;
import io.github.actforever.kuudra.api.SessionSnapshot;
import io.github.actforever.kuudra.runtime.KuudraRuntime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Kuudra Runtime")
class RuntimeController {
    private final KuudraRuntime runtime;

    RuntimeController(KuudraRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/runtime/health")
    @Operation(summary = "读取运行时健康状态")
    Map<String, Object> health() {
        return Map.of("status", "UP", "queuedTasks", runtime.queuedTasks(), "flows", runtime.flows().size());
    }

    @GetMapping("/flows")
    @Operation(summary = "列出已装配的 Flow")
    List<FlowSnapshot> flows() {
        return runtime.flows();
    }

    @GetMapping("/flows/{flowId}")
    @Operation(summary = "读取一个 Flow 的状态")
    FlowSnapshot flow(@PathVariable String flowId) {
        return runtime.flow(flowId).orElseThrow(() -> notFound("Flow", flowId));
    }

    @PostMapping("/flows/{flowId}/activate")
    @Operation(summary = "启用 Flow")
    FlowSnapshot activate(@PathVariable String flowId) { runtime.activateFlow(flowId); return flow(flowId); }

    @PostMapping("/flows/{flowId}/pause")
    @Operation(summary = "暂停 Flow")
    FlowSnapshot pause(@PathVariable String flowId) { runtime.pauseFlow(flowId); return flow(flowId); }

    @PostMapping("/flows/{flowId}/resume")
    @Operation(summary = "恢复 Flow")
    FlowSnapshot resume(@PathVariable String flowId) { runtime.resumeFlow(flowId); return flow(flowId); }

    @PostMapping("/flows/{flowId}/stop")
    @Operation(summary = "协作停止并排空 Flow")
    FlowSnapshot stop(@PathVariable String flowId) { runtime.stopFlow(flowId); return flow(flowId); }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "读取会话状态")
    SessionSnapshot session(@PathVariable UUID sessionId) {
        return runtime.session(sessionId).orElseThrow(() -> notFound("Session", sessionId.toString()));
    }

    @PostMapping("/sessions/{sessionId}/cancel")
    @Operation(summary = "请求协作取消会话")
    Map<String, Object> cancel(@PathVariable UUID sessionId) {
        if (!runtime.cancel(sessionId)) throw notFound("active session", sessionId.toString());
        return Map.of("sessionId", sessionId.toString(), "cancellationRequested", true);
    }

    private static ResponseStatusException notFound(String type, String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, type + " not found: " + id);
    }
}
