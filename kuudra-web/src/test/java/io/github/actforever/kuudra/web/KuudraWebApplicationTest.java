package io.github.actforever.kuudra.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.actforever.kuudra.api.system.SystemEvent;
import io.github.actforever.kuudra.app.KuudraApp;
import io.github.actforever.kuudra.web.controller.ComponentController;
import io.github.actforever.kuudra.web.controller.FlowController;
import io.github.actforever.kuudra.web.controller.KuudraController;
import io.github.actforever.kuudra.web.controller.PluginController;
import io.github.actforever.kuudra.web.controller.SessionController;
import io.github.actforever.kuudra.web.controller.SystemEventController;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KuudraWebApplicationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private KuudraApp app;
    @Autowired
    private ApplicationContext context;

    @Test
    void registersOneHttpAdapterForEachApiDomain() {
        assertEquals(1, context.getBeansOfType(KuudraController.class).size());
        assertEquals(1, context.getBeansOfType(FlowController.class).size());
        assertEquals(1, context.getBeansOfType(ComponentController.class).size());
        assertEquals(1, context.getBeansOfType(SessionController.class).size());
        assertEquals(1, context.getBeansOfType(PluginController.class).size());
        assertEquals(1, context.getBeansOfType(SystemEventController.class).size());
    }

    @Test
    void publishesShutdownRequestBeforeSpringDestroysTheApp() throws Exception {
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        try (AutoCloseable ignored = app.systemEvents().subscribe(event -> events.add(event.type()))) {
            new KuudraWebApplication().kuudraShutdownListener(app).onApplicationEvent(null);
        }
        assertTrue(events.contains("web.shutdown.requested"));
    }

    @Test
    void silentlyUnsubscribesWhenAnSseClientDisconnects() {
        AtomicInteger closes = new AtomicInteger();
        SseEmitter disconnected = new SseEmitter() {
            @Override public void send(SseEventBuilder builder) throws IOException { throw new IOException("client disconnected"); }
        };
        SystemEventController.EventStreamSubscription stream =
                new SystemEventController.EventStreamSubscription(disconnected);
        stream.attach(closes::incrementAndGet);
        stream.send(SystemEvent.of("app.paused", Map.of()));
        stream.send(SystemEvent.of("app.resumed", Map.of()));
        assertTrue(stream.closed());
        assertEquals(1, closes.get());
    }

    @Test
    void exposesKuudraStatusOverRest() throws Exception {
        mvc.perform(get("/api/v1/kuudra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void exposesCurrentAppKernelStatusWithoutRuntimeEndpoint() throws Exception {
        mvc.perform(get("/api/v1/kuudra/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.status").value("RUNNING"))
                .andExpect(jsonPath("$.activeSessions").value(0));
        mvc.perform(get("/api/v1/plugin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/plugin/component-templates"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/plugin/kuudra-official/default"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plugin/component-templates/ingress/kuudra-official/default/default"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/plugin/component-templates/ingress/kuudra-official/default/default/documentation"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/runtime/components"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/kuudra/resource-documentation/kuudra-official/Flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("kuudra-official"))
                .andExpect(jsonPath("$.kind").value("Flow"))
                .andExpect(jsonPath("$.examples[0].metadata.namespace").value("dev"));
    }

    @Test
    void publishesSeparateOpenApiGroups() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['urls.primaryName']").value("00 - 全部接口"))
                .andExpect(jsonPath("$.urls[?(@.name == '00 - 全部接口')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == '01 - Kuudra 内核')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == '02 - Runtime 运行资源')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == '03 - Plugin 扩展资源')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == '04 - 系统事件')]").exists());


        mvc.perform(get("/v3/api-docs/kuudra"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/start']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/start'].post.tags[0]").value("Kuudra"))
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/start'].post.summary").value("启动 Kuudra 内核"))
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/checkpoint']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/resource-documentation']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/resource-documentation'].get.tags[0]").value("Kuudra"))
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/resource-documentation/{namespace}/{kind}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/flows']").doesNotExist());
        mvc.perform(get("/v3/api-docs/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/components']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/components'].get.tags[0]").value("Runtime"))
                .andExpect(jsonPath("$.paths['/api/v1/runtime/components'].get.summary").value("列出 Component"))
                .andExpect(jsonPath("$.paths['/api/v1/runtime/components/{kind}/{namespace}/{name}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/components/{kind}/{namespace}/{name}/desired-state/{desiredState}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/flows']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/flows'].get.tags[0]").value("Runtime"))
                .andExpect(jsonPath("$.paths['/api/v1/runtime/flows/{namespace}/{name}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/sessions/{sessionId}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/sessions/{sessionId}'].get.tags[0]").value("Runtime"))
                .andExpect(jsonPath("$.paths['/api/v1/plugin']").doesNotExist());
        mvc.perform(get("/v3/api-docs/plugin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plugin']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plugin'].get.tags[0]").value("Plugin"))
                .andExpect(jsonPath("$.paths['/api/v1/plugin/{namespace}/{pluginId}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plugin/component-templates']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plugin/component-templates'].get.tags[0]").value("Plugin"))
                .andExpect(jsonPath("$.paths['/api/v1/plugin/component-templates'].get.summary").value("列出 ComponentTemplate"))
                .andExpect(jsonPath("$.paths['/api/v1/plugin/component-templates/{type}/{namespace}/{pluginId}/{name}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plugin/component-templates/{type}/{namespace}/{pluginId}/{name}/documentation']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/components']").doesNotExist());
        mvc.perform(get("/v3/api-docs/system-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/system-events']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/system-events'].get.tags[0]").value("System Events"));
        mvc.perform(get("/v3/api-docs/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/start']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/kuudra/resource-documentation']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plugin']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/runtime/components']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plugin/component-templates']").exists());
    }
}
