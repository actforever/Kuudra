package io.github.actforever.kuudra.defaultplugin;

import io.github.actforever.kuudra.plugin.ComponentInstancePolicy;
import io.github.actforever.kuudra.plugin.ComponentLimitScope;
import io.github.actforever.kuudra.plugin.PluginArchiveLoader;
import io.github.actforever.kuudra.plugin.PluginComponentDefinition;
import io.github.actforever.kuudra.plugin.PluginComponentDocumentation;
import io.github.actforever.kuudra.plugin.PluginComponentKind;
import io.github.actforever.kuudra.plugin.PluginConfigurationDocumentation;
import io.github.actforever.kuudra.plugin.PluginMetadata;

import java.util.List;

/** Explicit code-level registration bundle for the official built-in plugin. */
public final class DefaultPluginBundle {
    public static final String PLUGIN_ID = "default";
    public static final String NAMESPACE = "kuudra-official";
    public static final String VERSION = "0.1.0";
    private static final ComponentInstancePolicy SHARED = new ComponentInstancePolicy(
            Integer.MAX_VALUE, ComponentLimitScope.APP, "", true, true);

    private DefaultPluginBundle() { }

    public static PluginArchiveLoader.LoadedPlugin loadedPlugin() {
        return new PluginArchiveLoader.LoadedPlugin(metadata(), new DefaultPlugin(), List.of(
                new PluginComponentDefinition(PLUGIN_ID, NAMESPACE, PluginComponentKind.INGRESS, "default",
                        DefaultIngress.class, SHARED, new PluginComponentDocumentation(
                        "无条件准入 Event，并按 groupKey 或事件类型建立会话组。",
                        "groupKey: '${event#namespace.key}'", false, List.of(), List.of("ACTIVE", "INACTIVE"),
                        ingressConfiguration(), List.of())),
                new PluginComponentDefinition(PLUGIN_ID, NAMESPACE, PluginComponentKind.EGRESS, "default",
                        DefaultEgress.class, SHARED, new PluginComponentDocumentation(
                        "移除 Session 执行域并原样导出业务事件。", "options: {}", false,
                        List.of(), List.of("ACTIVE", "INACTIVE"), List.of(), List.of())),
                new PluginComponentDefinition(PLUGIN_ID, NAMESPACE, PluginComponentKind.EVENT_HANDLER, "system-control",
                        SystemControlEventHandler.class, SHARED, new PluginComponentDocumentation(
                        "将事件语义转换为内核或当前会话的暂停、恢复、停止请求。",
                        "action: PAUSE_KERNEL", true, List.of("initialize", "destroy"),
                        List.of("RUNNING", "STOPPED"), List.of(new PluginConfigurationDocumentation(
                        "action", "string", true, "", "要提交的内核或会话控制动作。", "PAUSE_KERNEL",
                        List.of("PAUSE_KERNEL", "RESUME_KERNEL", "STOP_KERNEL", "PAUSE_SESSION", "RESUME_SESSION", "CANCEL_SESSION"))),
                        List.of()))));
    }

    private static List<PluginConfigurationDocumentation> ingressConfiguration() {
        return List.of(
                property("groupKey", "string", "", "会话分组键；未配置时使用当前事件类型。", "${event#deviceId}", List.of()),
                property("policy", "string", "", "会话调度策略；未配置时继承根配置。", "SERIAL",
                        List.of("PARALLEL", "SERIAL", "IGNORE", "CANCEL_AND_REPLACE_PENDING", "CANCEL_AND_KEEP_PENDING", "TOGGLE")),
                property("groupScope", "string", "", "会话组隔离范围；未配置时继承根配置。", "FLOW_BINDING",
                        List.of("FLOW_BINDING", "INGRESS")),
                property("maxParallelSessions", "integer", "", "每个会话组允许的最大并行会话数。", "64", List.of()),
                property("queueCapacity", "integer", "", "每个会话组允许积压的事件数量。", "256", List.of()));
    }

    private static PluginConfigurationDocumentation property(String path, String type, String defaultValue,
                                                               String description, String example, List<String> allowedValues) {
        return new PluginConfigurationDocumentation(path, type, false, defaultValue, description, example, allowedValues);
    }

    public static PluginMetadata metadata() {
        return new PluginMetadata(PLUGIN_ID, NAMESPACE, VERSION, DefaultPlugin.class.getName(), List.of());
    }
}
