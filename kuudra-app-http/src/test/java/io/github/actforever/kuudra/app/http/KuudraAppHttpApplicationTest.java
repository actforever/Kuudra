package io.github.actforever.kuudra.app.http;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KuudraAppHttpApplicationTest {
    @Autowired MockMvc mvc;
    @Test
    void managesAppLifecycleWithoutUsingRuntimeInTheApi() throws Exception {
        mvc.perform(get("/api/v1/app")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
        mvc.perform(post("/api/v1/app/stop")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOPPED"));
        mvc.perform(post("/api/v1/app/start")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
    }
}
