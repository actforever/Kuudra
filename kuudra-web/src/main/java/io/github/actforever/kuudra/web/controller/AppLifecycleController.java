package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.api.app.AppSnapshot;
import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for App kernel lifecycle and observation. */
@RestController
@RequestMapping("/api/v1/app")
public class AppLifecycleController {
    private final KuudraApp app;

    public AppLifecycleController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "获取 App 快照", tags = "App 生命周期")
    @GetMapping
    AppSnapshot snapshot() {
        return app.snapshot();
    }

    @Operation(summary = "获取内核详细状态", tags = "App 生命周期")
    @GetMapping("/status")
    KuudraApp.Status status() {
        return app.status();
    }

    @Operation(summary = "获取暂停安全点生成的内核检查点", tags = "App 生命周期")
    @GetMapping("/checkpoint")
    KuudraApp.KernelCheckpoint checkpoint() {
        return app.checkpoint().orElseThrow(() -> notFound("Kernel checkpoint", "current"));
    }

    @Operation(summary = "启动 App 内核", tags = "App 生命周期")
    @PostMapping("/start")
    AppSnapshot start() {
        app.start();
        return app.snapshot();
    }

    @Operation(summary = "停止 App 内核", tags = "App 生命周期")
    @PostMapping("/stop")
    AppSnapshot stop() {
        app.stop();
        return app.snapshot();
    }

    @Operation(summary = "暂停 App 内核", tags = "App 生命周期")
    @PostMapping("/pause")
    AppSnapshot pause() {
        app.pause();
        return app.snapshot();
    }

    @Operation(summary = "恢复 App 内核", tags = "App 生命周期")
    @PostMapping("/resume")
    AppSnapshot resume() {
        app.resume();
        return app.snapshot();
    }

    @Operation(summary = "重启 App 内核", tags = "App 生命周期")
    @PostMapping("/restart")
    AppSnapshot restart() {
        app.restart();
        return app.snapshot();
    }
}
