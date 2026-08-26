package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for loaded plugins and their ComponentTemplate definitions. */
@RestController
@RequestMapping("/api/v1/plugin")
@Tag(name = "Plugin")
public class PluginController {
    private final KuudraApp app;

    public PluginController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出已加载插件")
    @GetMapping
    List<KuudraApp.Plugin> plugins() {
        return app.plugins();
    }

    @Operation(summary = "获取插件")
    @GetMapping("/{namespace}/{pluginId}")
    KuudraApp.Plugin plugin(
            @PathVariable("namespace") String namespace,
            @PathVariable("pluginId") String pluginId) {
        return app.plugin(namespace, pluginId)
                .orElseThrow(() -> notFound("Plugin", namespace + "/" + pluginId));
    }

    @Operation(summary = "列出 ComponentTemplate")
    @GetMapping("/component-templates")
    List<KuudraApp.Component> componentTemplates() {
        return app.components();
    }

    @Operation(summary = "获取 ComponentTemplate")
    @GetMapping("/component-templates/{type}/{namespace}/{name}")
    KuudraApp.Component componentTemplate(
            @PathVariable("type") String type,
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponent(reference).orElseThrow(() -> notFound("ComponentTemplate", reference));
    }

    @Operation(summary = "获取 ComponentTemplate 说明文档")
    @GetMapping("/component-templates/{type}/{namespace}/{name}/documentation")
    KuudraApp.ComponentDocumentation documentation(
            @PathVariable("type") String type,
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponentDocumentation(reference)
                .orElseThrow(() -> notFound("ComponentTemplate", reference));
    }
}
