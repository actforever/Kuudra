package io.github.actforever.kuudra.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KuudraWebApplicationTest {
    @Autowired
    private MockMvc mvc;

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
    }

    @Test
    void publishesSeparateOpenApiGroups() throws Exception {
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[?(@.name == 'app-lifecycle')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'flows')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'event-sources')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'sessions')]").exists())
                .andExpect(jsonPath("$.urls[?(@.name == 'system-events')]").exists());

        mvc.perform(get("/v3/api-docs/app-lifecycle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/start']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app/flows']").doesNotExist());
        mvc.perform(get("/v3/api-docs/event-sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/resources/event-sources']").exists());
        mvc.perform(get("/v3/api-docs/flows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/flows']").exists());
        mvc.perform(get("/v3/api-docs/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/sessions/{sessionId}']").exists());
        mvc.perform(get("/v3/api-docs/system-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/app/events']").exists());
    }
}
