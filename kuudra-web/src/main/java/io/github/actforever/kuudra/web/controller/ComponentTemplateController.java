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

/** HTTP adapter for plugin-provided Component templates. */
@RestController
@RequestMapping("/api/v1/plugin/component-templates")
@Tag(name = "Component Templates")
public class ComponentTemplateController {
    private final KuudraApp app;

    public ComponentTemplateController(KuudraApp app) {
        this.app = app;
    }

    @Operation(summary = "列出 Component 模板")
    @GetMapping
    List<KuudraApp.Component> componentTemplates() {
        return app.components();
    }

    @Operation(summary = "获取 Component 模板")
    @GetMapping("/{type}/{namespace}/{name}")
    KuudraApp.Component componentTemplate(
            @PathVariable("type") String type,
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponent(reference).orElseThrow(() -> notFound("Component template", reference));
    }

    @Operation(summary = "获取 Component 模板说明文档")
    @GetMapping("/{type}/{namespace}/{name}/documentation")
    KuudraApp.ComponentDocumentation documentation(
            @PathVariable("type") String type,
            @PathVariable("namespace") String namespace,
            @PathVariable("name") String name) {
        String reference = type + "/" + namespace + "/" + name;
        return app.pluginComponentDocumentation(reference)
                .orElseThrow(() -> notFound("Component template", reference));
    }
}
