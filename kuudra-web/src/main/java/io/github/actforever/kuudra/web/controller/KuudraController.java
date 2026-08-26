package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.api.app.AppSnapshot;
import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for the Kuudra kernel lifecycle, observation and built-in resource documentation. */
@RestController
@RequestMapping("/api/v1/kuudra")
@Tag(name = "Kuudra")
public class KuudraController {
    private final KuudraApp app;

    public KuudraController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "获取 Kuudra 内核快照")
    @GetMapping
    AppSnapshot snapshot() {
        return app.snapshot();
    }

    @Operation(summary = "获取 Kuudra 内核详细状态")
    @GetMapping("/status")
    KuudraApp.Status status() {
        return app.status();
    }

    @Operation(summary = "获取暂停安全点生成的内核检查点")
    @GetMapping("/checkpoint")
    KuudraApp.KernelCheckpoint checkpoint() {
        return app.checkpoint().orElseThrow(() -> notFound("Kernel checkpoint", "current"));
    }

    @Operation(summary = "列出内核资源说明文档")
    @GetMapping("/resource-documentation")
    List<KuudraApp.ResourceDocumentation> resourceDocumentations() {
        return app.resourceDocumentations();
    }

    @Operation(summary = "获取内核资源说明文档")
    @GetMapping("/resource-documentation/{namespace}/{kind}")
    KuudraApp.ResourceDocumentation resourceDocumentation(
            @PathVariable("namespace") String namespace,
            @PathVariable("kind") String kind) {
        return app.resourceDocumentation(namespace, kind)
                .orElseThrow(() -> notFound("Resource documentation", namespace + "/" + kind));
    }

    @Operation(summary = "启动 Kuudra 内核")
    @PostMapping("/start")
    AppSnapshot start() {
        app.start();
        return app.snapshot();
    }

    @Operation(summary = "停止 Kuudra 内核")
    @PostMapping("/stop")
    AppSnapshot stop() {
        app.stop();
        return app.snapshot();
    }

    @Operation(summary = "暂停 Kuudra 内核")
    @PostMapping("/pause")
    AppSnapshot pause() {
        app.pause();
        return app.snapshot();
    }

    @Operation(summary = "恢复 Kuudra 内核")
    @PostMapping("/resume")
    AppSnapshot resume() {
        app.resume();
        return app.snapshot();
    }

    @Operation(summary = "重启 Kuudra 内核")
    @PostMapping("/restart")
    AppSnapshot restart() {
        app.restart();
        return app.snapshot();
    }
}
