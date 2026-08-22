package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.app.KuudraApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.io.IOException;

@SpringBootApplication
public class KuudraWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(KuudraWebApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    KuudraApp kuudraApp() throws IOException {
        return KuudraApp.createFromDefaultLocations();
    }
}
