package io.github.actforever.kuudra.app.http;

import io.github.actforever.kuudra.app.KuudraApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class KuudraAppHttpApplication {
    public static void main(String[] args) { SpringApplication.run(KuudraAppHttpApplication.class, args); }
    @Bean(destroyMethod = "close") KuudraApp kuudraApp() { return KuudraApp.createDefault(); }
    @Bean DaemonTerminator daemonTerminator(ConfigurableApplicationContext context) {
        return () -> new Thread(context::close, "kuudra-app-terminator").start();
    }
    @FunctionalInterface interface DaemonTerminator { void terminate(); }
}
