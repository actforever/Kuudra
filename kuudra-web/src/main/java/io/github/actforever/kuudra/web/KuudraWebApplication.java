package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.app.KuudraApp;
import io.github.actforever.kuudra.config.KuudraConfigResource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.context.annotation.Bean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class KuudraWebApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(KuudraWebApplication.class);
        addAdjacentApplicationYaml(application);
        application.run(args);
    }

    /** Lets a deployed executable JAR be overridden by an application.yaml in the same directory. */
    static void addAdjacentApplicationYaml(SpringApplication application) {
        try {
            java.io.File source = new ApplicationHome(KuudraWebApplication.class).getSource();
            if (source == null) return;
            Path codeSource = source.toPath();
            if (!Files.isRegularFile(codeSource)) return;
            Path adjacent = codeSource.getParent().resolve("application.yaml");
            if (Files.isRegularFile(adjacent)) {
                application.addInitializers(context -> addYamlPropertySource(context, adjacent));
            }
        } catch (RuntimeException ignored) {
            // A non-file CodeSource simply uses Spring Boot's normal configuration locations.
        }
    }

    private static void addYamlPropertySource(ConfigurableApplicationContext context, Path file) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("kuudra-adjacent-application", new FileSystemResource(file));
            for (int index = sources.size() - 1; index >= 0; index--) context.getEnvironment().getPropertySources().addFirst(sources.get(index));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load adjacent application.yaml: " + file, error);
        }
    }

    @Bean(destroyMethod = "close")
    KuudraApp kuudraApp(Environment environment) throws IOException {
        Map<String, Object> values = Binder.get(environment)
                .bind("kuudra", Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        Path baseDirectory = Path.of(environment.getProperty("kuudra.base-directory", "."));
        return KuudraApp.createConfigured(new KuudraConfigResource(values, baseDirectory, "Spring application configuration"));
    }
}
