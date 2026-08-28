# Kuudra 宏定义与执行

宏能力由外部插件分层提供，内核不依赖具体键鼠库：

- `actforever/user-interaction-spec` 定义平台无关的键码、鼠标和坐标对象；
- `actforever/macro-spec` 定义不可变、语言无关的宏 IR、YAML 编解码器和语言前端注册表；
- `actforever/macro-kotlin` 将受信任的本地 `.kt` 构建脚本编译成宏 IR；
- `actforever/awt-robot` 使用同一个执行器运行 YAML 或脚本生成的 IR。

`awt-robot` 资源必须且只能配置 `spec.options.steps` 或 `spec.options.script` 之一。`script` 是相对于插件家目录 `<home>/plugins/actforever/awt-robot` 的路径；绝对路径、目录穿越、符号链接逃逸和未知扩展名都会在资源初始化阶段失败。

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata:
  namespace: macro
  name: robot
spec:
  component: actforever/awt-robot/awt-robot
  desiredState: running
  options:
    script: macros/hello.kt
    maxTotalSteps: 10000
    syntheticMarkerLifetimeMillis: 500
```

对应的 `<plugin-home>/macros/hello.kt`：

```kotlin
macro {
    press(A)
    sleep(100)
    release(A)

    whenCondition(ref("session#enabled").eq(true), {
        whileCondition(ref("session#cancelled").falsy(), 1000) {
            click(BUTTON_1)
            sleep(50)
        }
    }).otherwise {
        emit("macro.skipped", "disabled")
    }
}
```

Kotlin 文件是受信任的本地构建代码，不是 Event 到来时执行的沙箱脚本。组件初始化时执行一次前端编译；组件停止后重新启动时，只有文件大小或修改时间变化才重新编译。每个 Event 只执行已经生成的 IR，因此不会重复启动 Kotlin 编译器。

运行时条件必须使用 `ref("event#...")`、`ref("session#...")`、`ref("flow#...")` 或 `ref("global#...")` 构建；实际取值发生在执行器到达该步骤时，能够看到最新上下文。YAML 与 Kotlin 最终共享相同的协作式检查点、Session 取消、循环和总步数限制、暂停释放/恢复输入、异常清理及合成输入回捕抑制语义。

`.groovy` 和 `.kmd` 仅为未来独立前端预留。没有安装并注册对应前端插件时，引用这些扩展名会明确失败。

## 编译、类加载与运行时上下文

内核本身不直接理解 Kotlin。真实调用链如下：

1. 插件管理器按依赖顺序加载 `macro-spec -> macro-kotlin` 和 `user-interaction-spec + macro-spec -> awt-robot`；三个依赖方看到的是同一个 `MacroProgramDefinition` 类型身份。
2. `macro-kotlin` 启动时向 `MacroFrontendRegistry` 注册 `.kt` 前端。它使用内嵌 Kotlin 2.4.10 的 `BasicJvmScriptingHost`，并从 Kuudra 的依赖感知插件 ClassLoader 显式构建编译 classpath。求值阶段以该插件 ClassLoader 作为父加载器，避免脚本产生一份无法转换的重复 IR 类型。
3. App 创建 AWT Handler 资源时，`PluginComponentContext.plugin().home()` 给出 `<home>/plugins/actforever/awt-robot`。Handler 校验 `script` 不能逃逸该目录，再按扩展名找到前端。
4. Kotlin 文件只执行构建 API 并返回 `MacroProgramDefinition`。该对象随组件缓存；文件大小和修改时间未变化时，组件再次启动不会重新编译。
5. Event 到达时 AWT Handler 不再运行 Kotlin，只遍历 IR。每个步骤前后都会执行 `ExecutionControl` 检查点，并在结束或异常时释放输入状态。

`.kt` 在编译阶段**不会取得某个 Event 或 Session 对象**。`ref("session#enabled")` 只是提前编译成 `ContextValueReference` 并保存到 `MacroCondition`。运行到条件节点时才调用 `MacroCondition.matches(currentEvent, actionContext)`：

```text
Kotlin ref("session#enabled")
  -> 编译期 ContextValueReference
  -> MacroProgramDefinition 缓存
  -> Event 到达
  -> MacroCondition.matches(KuudraEvent, ActionContext)
  -> 按 event/session/flow/global 规则动态取值
```

因此脚本语法负责描述流程，`KuudraEvent`、Session、Flow 和 Global 的最新值仍由 Runtime 在实际执行时提供；脚本不能在初始化时捕获某次运行的上下文。

## 完整安全示例

部署 `hello-world`、`default`、`logging`、`user-interaction-spec`、`macro-spec`、`macro-kotlin`、`awt-robot` 七个插件 JAR。将以下多文档文件保存为 `.kuudra/manifests/macro-kotlin.yaml`：

```yaml
apiVersion: kuudra.io/v1alpha1
kind: EventSource
metadata: { namespace: macro-kotlin-demo, name: trigger }
spec:
  component: kuudra-official/hello-world/hello-world
  desiredState: running
  options: { intervalMillis: 500 }
---
apiVersion: kuudra.io/v1alpha1
kind: Ingress
metadata: { namespace: macro-kotlin-demo, name: ingress }
spec:
  component: kuudra-official/default/plain-ingress
  desiredState: active
  options:
    groupKey: "${event#hello-world.message}"
---
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata: { namespace: macro-kotlin-demo, name: robot }
spec:
  component: actforever/awt-robot/awt-robot
  desiredState: running
  options:
    script: macros/safe-emit.kt
    maxTotalSteps: 100
    syntheticMarkerLifetimeMillis: 500
---
apiVersion: kuudra.io/v1alpha1
kind: EventHandler
metadata: { namespace: macro-kotlin-demo, name: logger }
spec:
  component: kuudra-official/logging/event-logger
  desiredState: running
  options:
    level: INFO
    message: "Kotlin macro emitted ${event#macro.result}"
    includeData: true
---
apiVersion: kuudra.io/v1alpha1
kind: Flow
metadata: { namespace: macro-kotlin-demo, name: safe-kotlin-macro }
spec:
  imports:
    source: { kind: EventSource, name: trigger }
    ingress: { kind: Ingress, name: ingress }
    robot: { kind: EventHandler, name: robot }
    logger: { kind: EventHandler, name: logger }
  edges:
    - { from: source, to: ingress }
    - { from: ingress, to: robot }
    - { from: robot, to: logger }
```

将脚本保存为 `.kuudra/plugins/actforever/awt-robot/macros/safe-emit.kt`：

```kotlin
macro {
    whenCondition(ref("event#hello-world.message").eq("hello-world"), {
        emit("macro.kotlin.completed", mapOf("macro" to mapOf("result" to "compiled-and-executed")))
    }).otherwise {
        emit("macro.kotlin.skipped", mapOf("macro" to mapOf("result" to "unexpected-input")))
    }
}
```

该脚本不执行真实键鼠操作，可安全验证外部编译、动态 Event 取值、Session 内 Handler 输出以及下游路由。Windows 无桌面检测环境执行黑箱测试时需显式传入 `-Djava.awt.headless=false`；真实键鼠宏仍应只在可用的图形桌面会话中运行。
