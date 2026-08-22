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
import java.util.LinkedHashMap;
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
        Map<String, Object> values = appConfiguration(environment);
        String configuredBaseDirectory = environment.getProperty("kuudra.base-directory");
        Path baseDirectory = configuredBaseDirectory == null || configuredBaseDirectory.isBlank()
                ? executableDirectoryOrWorkingDirectory() : Path.of(configuredBaseDirectory);
        return KuudraApp.createConfigured(new KuudraConfigResource(values, baseDirectory, "Spring application configuration"));
    }

    private static Map<String, Object> appConfiguration(Environment environment) {
        Binder binder = Binder.get(environment);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("queueCapacity", environment.getProperty("kuudra.runtime.queue-capacity", Integer.class, 1_024));
        runtime.put("workerThreads", environment.getProperty("kuudra.runtime.worker-threads", Integer.class, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)));
        Map<String, Object> plugins = new LinkedHashMap<>();
        plugins.put("directories", binder.bind("kuudra.plugins.directories", Bindable.listOf(String.class)).orElse(List.of()));
        plugins.put("homeDirectory", environment.getProperty("kuudra.plugins.home-directory", ".kuudra/plugins"));
        plugins.put("load", binder.bind("kuudra.plugins.load", Bindable.listOf(String.class)).orElse(List.of()));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runtime", runtime);
        values.put("plugins", plugins);
        values.put("flowsDirectory", environment.getProperty("kuudra.flows-directory", "flows"));
        values.put("globalContext", binder.bind("kuudra.global-context", Bindable.mapOf(String.class, Object.class)).orElse(Map.of()));
        return values;
    }

    private static Path executableDirectoryOrWorkingDirectory() {
        java.io.File source = new ApplicationHome(KuudraWebApplication.class).getSource();
        if (source != null && source.isFile() && source.getParentFile() != null) return source.getParentFile().toPath();
        return Path.of(".");
    }
}
