package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.app.KuudraApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;
import java.io.IOException;

@SpringBootApplication
public class KuudraWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(KuudraWebApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    KuudraApp kuudraApp(@Value("${kuudra.config.path:}") String configPath) throws IOException {
        return configPath == null || configPath.isBlank() ? KuudraApp.createDefaultOrClasspathConfigured() : KuudraApp.createConfigured(Path.of(configPath));
    }
}
