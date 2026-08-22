package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.runtime.KuudraRuntime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KuudraWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(KuudraWebApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    KuudraRuntime kuudraRuntime() {
        return new KuudraRuntime(1_024, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    }
}
