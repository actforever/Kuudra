package io.github.actforever.kuudra.web.controller;

import io.github.actforever.kuudra.app.KuudraApp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static io.github.actforever.kuudra.web.controller.ControllerSupport.notFound;

/** HTTP adapter for loaded plugins and their ResourceTemplate definitions. */
@RestController
@RequestMapping("/api/v1/plugin")
@Tag(name = "Plugin")
public final class PluginController {
    private final KuudraApp app;
    public PluginController(KuudraApp app) { this.app = app; }

    @Operation(summary = "列出已加载插件")
    @GetMapping
    List<KuudraApp.Plugin> plugins() { return app.plugins(); }

    @Operation(summary = "获取插件")
    @GetMapping("/{namespace}/{pluginId}")
    KuudraApp.Plugin plugin(@PathVariable("namespace") String namespace,
                            @PathVariable("pluginId") String pluginId) {
        return app.plugin(namespace, pluginId)
                .orElseThrow(() -> notFound("Plugin", namespace + "/" + pluginId));
    }

    @Operation(summary = "列出 ResourceTemplate")
    @GetMapping("/resource-templates")
    List<KuudraApp.ResourceTemplate> resourceTemplates() { return app.resourceTemplates(); }

    @Operation(summary = "获取 ResourceTemplate")
    @GetMapping("/resource-templates/{type}/{namespace}/{pluginId}/{name}")
    KuudraApp.ResourceTemplate resourceTemplate(@PathVariable("type") String type,
                                                 @PathVariable("namespace") String namespace,
                                                 @PathVariable("pluginId") String pluginId,
                                                 @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + pluginId + "/" + name;
        return app.resourceTemplates().stream().filter(template -> template.reference().equals(reference)).findFirst()
                .orElseThrow(() -> notFound("ResourceTemplate", reference));
    }
}
