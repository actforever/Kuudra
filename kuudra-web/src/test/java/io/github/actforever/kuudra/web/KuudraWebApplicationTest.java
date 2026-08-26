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
import io.github.actforever.kuudra.web.controller.AppLifecycleController;
import io.github.actforever.kuudra.web.controller.ComponentResourceController;
import io.github.actforever.kuudra.web.controller.EventSourceController;
import io.github.actforever.kuudra.web.controller.FlowController;
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
        assertEquals(1, context.getBeansOfType(AppLifecycleController.class).size());
        assertEquals(1, context.getBeansOfType(FlowController.class).size());
        assertEquals(1, context.getBeansOfType(EventSourceController.class).size());
        assertEquals(1, context.getBeansOfType(ComponentResourceController.class).size());
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
    void exposesAppStatusOverRest() throws Exception {
        mvc.perform(get("/api/v1/app"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void exposesCurrentAppKernelStatusWithoutRuntimeEndpoint() throws Exception {
        mvc.perform(get("/api/v1/app/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.status").value("RUNNING"))
                .andExpect(jsonPath("$.activeSessions").value(0));
        mvc.perform(get("/api/v1/app/plugins"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/app/components"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/app/plugins/kuudra-official/default"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/app/components/ingress/kuudra-official/default"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/app/components/ingress/kuudra-official/default/documentation"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/app/plugins/kuudra-official/default/components/event-handler/system-control/documentation"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/app/resources/components"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/app/resource-documentation/kuudra-official/Flow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("kuudra-official"))
                .andExpect(jsonPath("$.kind").value("Flow"))
                .andExpect(jsonPath("$.examples[0].metadata.namespace").value("dev"));
    }

    @Test
    void publishesSeparateOpenApiGroups() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['urls.primaryName']").value("all"))
                .andExpect(jsonPath("$.urls[?(@.name == 'all')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'app-lifecycle')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'flows')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'event-sources')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'component-resources')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'sessions')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'system-events')]").exists());


        mvc.perform(get("/v3/api-docs/app-lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/start']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/checkpoint']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/flows']").doesNotExist());
        mvc.perform(get("/v3/api-docs/event-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/resources/event-sources']").exists());
        mvc.perform(get("/v3/api-docs/component-resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/resources/components']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/resources/components/{type}/{namespace}/{name}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/resources/{kind}/{namespace}/{name}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/resources/{kind}/{namespace}/{name}/desired-state/{desiredState}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/namespaces/{namespace}/resources']").exists());
        mvc.perform(get("/v3/api-docs/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/flows']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/resource-documentation/{namespace}/{kind}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/namespaces/{namespace}/flows/{name}']").exists());
        mvc.perform(get("/v3/api-docs/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/sessions/{sessionId}']").exists());
        mvc.perform(get("/v3/api-docs/system-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/events']").exists());
        mvc.perform(get("/v3/api-docs/plugins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/plugins']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/plugins/{namespace}/{pluginId}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/plugins/{namespace}/{pluginId}/components']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/plugins/{namespace}/{pluginId}/components/{type}/{name}/documentation']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/components/{type}/{namespace}/{name}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/components/{type}/{namespace}/{name}/documentation']").exists());
        mvc.perform(get("/v3/api-docs/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/start']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/plugins']").exists());
    }
}
