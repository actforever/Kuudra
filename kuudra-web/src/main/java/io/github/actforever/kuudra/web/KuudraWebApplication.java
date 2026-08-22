package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.app.KuudraApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KuudraWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(KuudraWebApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    KuudraApp kuudraApp() {
        return KuudraApp.createDefault();
    }
}
