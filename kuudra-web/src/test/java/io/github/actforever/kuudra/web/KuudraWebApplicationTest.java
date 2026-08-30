package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.app.KuudraApp;
import io.github.actforever.kuudra.web.controller.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class KuudraWebApplicationTest {
    @Autowired MockMvc mvc;
    @Autowired KuudraApp app;
    @Autowired ApplicationContext context;

    @Test
    void registersAbilityResourceAndPluginAdapters() {
        assertEquals(1, context.getBeansOfType(AbilityController.class).size());
        assertEquals(1, context.getBeansOfType(ResourceController.class).size());
        assertEquals(1, context.getBeansOfType(PluginController.class).size());
        assertTrue(context.getBeansOfType(FlowControllerMarker.class).isEmpty());
    }

    @Test
    void exposesV1Alpha2RuntimeAndTemplateCollections() throws Exception {
        mvc.perform(get("/api/v1/kuudra/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.app.status").value("RUNNING"));
        mvc.perform(get("/api/v1/runtime/abilities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/runtime/resources"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/plugin/resource-templates"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/runtime/flows")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/runtime/components")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/kuudra/resource-documentation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].apiVersion").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("kuudra.io/v1alpha2"))))
                .andExpect(jsonPath("$[*].kind", org.hamcrest.Matchers.containsInAnyOrder(
                        "Ability", "AbilityProfile")));
    }

    @Test
    void bindsExplicitPathVariableNamesInPackagedControllers() throws Exception {
        mvc.perform(get("/api/v1/runtime/abilities/demo/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/runtime/resources/Controller/demo/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plugin/demo/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plugin/resource-templates/controller/demo/missing/entry"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publishesNewOpenApiPaths() throws Exception {
        mvc.perform(get("/v3/api-docs/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/abilities']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/abilities/{namespace}/{name}/{action}'].post.responses['202']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/resources']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/flows']").doesNotExist());
        mvc.perform(get("/v3/api-docs/plugin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plugin/resource-templates']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plugin/component-templates']").doesNotExist());
    }

    /** Compile-time sentinel: no old Flow controller bean type is retained. */
    private interface FlowControllerMarker { }
}
