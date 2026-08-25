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
                        "作为 RAW 到 SESSION 的边界无条件准入 Event；它只计算会话分组与调度参数，Session 的创建和调度由 Runtime 负责。",
                        "groupKey: '${event#deviceId}'\npolicy: SERIAL\ngroupScope: FLOW_BINDING", false,
                        List.of("admit: 计算分组键并返回准入决定"), List.of("ACTIVE", "INACTIVE"),
                        ingressConfiguration(), List.of())),
                new PluginComponentDefinition(PLUGIN_ID, NAMESPACE, PluginComponentKind.EGRESS, "default",
                        DefaultEgress.class, SHARED, new PluginComponentDocumentation(
                        "作为 SESSION 到 RAW 的边界移除会话执行域，并保留事件数据和因果 lineage，使事件可以进入其他 Flow。",
                        "options: {}", false, List.of("export: 原样导出当前业务事件"),
                        List.of("ACTIVE", "INACTIVE"), List.of(), List.of())),
                new PluginComponentDefinition(PLUGIN_ID, NAMESPACE, PluginComponentKind.EVENT_HANDLER, "system-control",
                        SystemControlEventHandler.class, SHARED, new PluginComponentDocumentation(
                        "将路由到当前节点的 Event 转换为内核或当前 Session 的生命周期控制请求。会话级动作使用当前 Event 携带的 Session。",
                        "action: PAUSE_KERNEL", true,
                        List.of("initialize: 获取 Runtime 控制端口", "handle: 异步提交控制请求", "destroy: 释放组件上下文"),
                        List.of("RUNNING", "STOPPED"), List.of(new PluginConfigurationDocumentation(
                        "action", io.github.actforever.kuudra.plugin.KernelControlAction.class.getName(), true, "", "要提交的内核或会话控制动作。", List.of("PAUSE_KERNEL", "CANCEL_SESSION"),
                        List.of("PAUSE_KERNEL", "RESUME_KERNEL", "STOP_KERNEL", "PAUSE_SESSION", "RESUME_SESSION", "CANCEL_SESSION"))),
                        List.of()))));
    }

    private static List<PluginConfigurationDocumentation> ingressConfiguration() {
        return List.of(
                property("groupKey", String.class, "", "会话分组键；未配置时使用当前事件类型。", List.of("device-1", "keyboard"), List.of()),
                property("policy", io.github.actforever.kuudra.api.SessionSchedulingPolicy.class, "", "会话调度策略；未配置时继承根配置。", List.of("SERIAL", "PARALLEL"),
                        List.of("PARALLEL", "SERIAL", "IGNORE", "CANCEL_AND_REPLACE_PENDING", "CANCEL_AND_KEEP_PENDING", "TOGGLE")),
                property("groupScope", io.github.actforever.kuudra.api.SessionGroupScope.class, "", "会话组隔离范围；未配置时继承根配置。", List.of("FLOW_BINDING", "INGRESS"),
                        List.of("FLOW_BINDING", "INGRESS")),
                property("maxParallelSessions", Integer.class, "", "每个会话组允许的最大并行会话数。", List.of(16, 64), List.of()),
                property("queueCapacity", Integer.class, "", "每个会话组允许积压的事件数量。", List.of(128, 256), List.of()));
    }

    private static PluginConfigurationDocumentation property(String path, Class<?> type, String defaultValue,
                                                               String description, List<Object> examples, List<String> allowedValues) {
        return new PluginConfigurationDocumentation(path, type.getName(), false, defaultValue, description, examples, allowedValues);
    }

    public static PluginMetadata metadata() {
        return new PluginMetadata(PLUGIN_ID, NAMESPACE, VERSION, DefaultPlugin.class.getName(), List.of());
    }
}
