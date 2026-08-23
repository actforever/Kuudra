package io.github.actforever.kuudra.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfiguration {
    @Bean
    OpenAPI kuudraOpenApi() {
        return new OpenAPI().info(new Info().title("Kuudra App API").version("v1")
                .description("Kuudra 内核运行时观测与生命周期控制接口。"))
                .addTagsItem(new Tag().name("App 生命周期").description("查询、启动、停止和重启 App 内核。"))
                .addTagsItem(new Tag().name("Flow 管理").description("查询和控制 Flow 路由与会话闸门。"))
                .addTagsItem(new Tag().name("EventSource 资源").description("查询和控制 Flow 绑定的事件源资源。"))
                .addTagsItem(new Tag().name("Session 管理").description("查询会话并请求协作式取消。"))
                .addTagsItem(new Tag().name("系统事件").description("订阅 App SystemEvent SSE 流。"));
    }

    @Bean
    GroupedOpenApi appLifecycleApi() {
        return GroupedOpenApi.builder().group("app-lifecycle")
                .pathsToMatch("/api/v1/app", "/api/v1/app/status", "/api/v1/app/start",
                        "/api/v1/app/stop", "/api/v1/app/restart")
                .build();
    }

    @Bean
    GroupedOpenApi flowApi() {
        return GroupedOpenApi.builder().group("flows")
                .pathsToMatch("/api/v1/app/flows", "/api/v1/app/flows/*",
                        "/api/v1/app/flows/*/start", "/api/v1/app/flows/*/pause",
                        "/api/v1/app/flows/*/resume", "/api/v1/app/flows/*/stop")
                .build();
    }

    @Bean
    GroupedOpenApi eventSourceApi() {
        return GroupedOpenApi.builder().group("event-sources")
                .pathsToMatch("/api/v1/app/resources/event-sources",
                        "/api/v1/app/flows/*/resources/event-sources",
                        "/api/v1/app/flows/*/resources/event-sources/**")
                .build();
    }

    @Bean
    GroupedOpenApi sessionApi() {
        return GroupedOpenApi.builder().group("sessions")
                .pathsToMatch("/api/v1/app/sessions/**")
                .build();
    }

    @Bean
    GroupedOpenApi systemEventApi() {
        return GroupedOpenApi.builder().group("system-events")
                .pathsToMatch("/api/v1/app/events")
                .build();
    }
}
