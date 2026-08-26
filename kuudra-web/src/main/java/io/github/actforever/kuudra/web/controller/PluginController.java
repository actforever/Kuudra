package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.call;
import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for loaded plugins, registered components and their documentation. */
@RestController
@RequestMapping("/api/v1/app")
public class PluginController {
    private final KuudraApp app;

    public PluginController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出已加载插件", tags = "插件与组件")
    @GetMapping("/plugins")
    List<KuudraApp.Plugin> plugins() {
        return app.plugins();
    }

    @Operation(summary = "获取插件及其组件", tags = "插件与组件")
    @GetMapping("/plugins/{namespace}/{pluginId}")
    KuudraApp.Plugin plugin(
            @PathVariable("namespace") String namespace, @PathVariable("pluginId") String pluginId) {
        return app.plugin(namespace, pluginId)
                .orElseThrow(() -> notFound("Plugin", namespace + "/" + pluginId));
    }

    @Operation(summary = "列出插件组件", tags = "插件与组件")
    @GetMapping("/plugins/{namespace}/{pluginId}/components")
    List<KuudraApp.Component> pluginComponents(
            @PathVariable("namespace") String namespace, @PathVariable("pluginId") String pluginId) {
        return call(() -> app.pluginComponents(namespace, pluginId), "Plugin", namespace + "/" + pluginId);
    }

    @Operation(summary = "按插件身份获取完整组件文档", tags = "插件与组件")
    @GetMapping("/plugins/{namespace}/{pluginId}/components/{type}/{name}/documentation")
    KuudraApp.ComponentDocumentation pluginComponentDocumentation(
            @PathVariable("namespace") String namespace, @PathVariable("pluginId") String pluginId,
            @PathVariable("type") String type, @PathVariable("name") String name) {
        return app.pluginComponentDocumentation(namespace, pluginId, type, name).orElseThrow(() ->
                notFound("Plugin Component", namespace + "/" + pluginId + "/" + type + "/" + name));
    }

    @Operation(summary = "列出全部插件组件", tags = "插件与组件")
    @GetMapping("/components")
    List<KuudraApp.Component> components() {
        return app.components();
    }

    @Operation(summary = "获取组件结构化文档", tags = "插件与组件")
    @GetMapping("/components/{type}/{namespace}/{name}")
    KuudraApp.Component component(
            @PathVariable("type") String type, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponent(reference).orElseThrow(() -> notFound("Component", reference));
    }

    @Operation(summary = "获取完整组件说明文档", tags = "插件与组件")
    @GetMapping("/components/{type}/{namespace}/{name}/documentation")
    KuudraApp.ComponentDocumentation componentDocumentation(
            @PathVariable("type") String type, @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponentDocumentation(reference).orElseThrow(() -> notFound("Component", reference));
    }
}
