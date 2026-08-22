package io.github.actforever.kuudra.web;

import io.github.actforever.kuudra.app.KuudraApp;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.context.annotation.Bean;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
    KuudraApp kuudraApp() throws IOException {
        return KuudraApp.createFromDefaultLocations(executableDirectoryOrWorkingDirectory());
    }

    private static Path executableDirectoryOrWorkingDirectory() {
        java.io.File source = new ApplicationHome(KuudraWebApplication.class).getSource();
        if (source != null && source.isFile() && source.getParentFile() != null) return source.getParentFile().toPath();
        return Path.of(".");
    }
}
