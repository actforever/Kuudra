package io.github.actforever.kuudra.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfiguration {
    @Bean
    OpenAPI kuudraOpenApi() {
        return new OpenAPI().info(new Info().title("Kuudra App API").version("v1")
                .description("Kuudra 内核运行时观测与生命周期控制接口。"));
    }
}
