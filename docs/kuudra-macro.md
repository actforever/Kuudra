# Kuudra 宏定义与执行

交互规约、捕获、宏前端和执行器统一位于独立同级 Reactor `kuudra-automation-plugins`，
可在不构建其他能力仓库的情况下独立测试。

宏能力由外部插件分层提供，内核不依赖具体键鼠库：

- `actforever/user-interaction-spec`：平台无关的键码、鼠标与坐标对象；
- `actforever/macro-spec`：不可变、语言无关的宏 IR、YAML 解码器与前端注册表；
- `actforever/macro-kotlin`：将受信任的本地 `.kt` 构建脚本编译为宏 IR；
- `actforever/awt-robot`：通过一个串行化执行器运行 YAML 或前端生成的 IR。

## Resource 与 handler

AWT Robot 是 v1alpha2 `Controller` Resource，静态 `options` 必须且只能配置 `steps` 或 `script` 之一：

```yaml
apiVersion: kuudra.io/v1alpha2
kind: Controller
metadata:
  namespace: macro
  name: robot
spec:
  template: actforever/awt-robot/awt-robot
  options:
    script: macros/hello.kt
    maxTotalSteps: 10000
    syntheticMarkerLifetimeMillis: 500
```

`script` 相对于插件 home `<home>/plugins/actforever/awt-robot`。绝对路径、目录穿越、符号链接逃逸、非普通文件和未知扩展名都会在 Resource 初始化阶段失败。

Ability 节点通过命名入口调用它：

```yaml
robot:
  resource: Controller/macro/robot
  handler: execute
```

## Kotlin 构建脚本

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

Kotlin 文件是受信任的本地构建代码，不是 Event 到来时执行的沙箱脚本。Resource 初始化时执行一次前端编译；停止后重新启动时，仅当文件大小或修改时间变化才重新编译。每个 Event 只执行缓存的 IR。

运行时引用使用 `event#`、`session#`、`ability#` 或 `global#`。`flow#` 仅是内核过渡兼容别名，新脚本不得使用。`ref("session#enabled")` 在编译阶段只生成 `ContextValueReference`；真正取值发生在宏执行到该节点时，因此能够观察最新上下文。

## 加载与执行边界

1. 插件管理器按依赖顺序加载 `macro-spec -> macro-kotlin` 与 `user-interaction-spec + macro-spec -> awt-robot`，保证依赖方看到同一个宏 IR 类型身份。
2. `macro-kotlin` 向 `MacroFrontendRegistry` 注册 `.kt` 前端，并使用依赖感知的插件 ClassLoader 构建编译 classpath。
3. App 按 Ability 节点 claim 创建 AWT Controller；仅声明 Resource alias 而未被节点引用不会初始化它。
4. Controller 初始化时解析并编译脚本，启动时初始化共享物理 Robot 设备。
5. Event 到达 `execute` handler 后只遍历 IR。执行器在步骤边界协作检查暂停和取消，并在取消、失败或结束时释放已按下的输入。

所有物理动作跨 Resource 实例串行化。JNativeHook 默认丢弃匹配的进程内合成输入，避免 Robot 输出再次进入捕获链形成反馈；诊断模式可以保留事件并标记 `synthetic=true`。

## 安全验证

官方工作区的 `examples/macro-kotlin-safe` 使用只发射 Event、不执行真实键鼠操作的脚本，适合验证：

- Kotlin 前端编译和缓存；
- 动态 Event/Session/Ability/Global 引用；
- Controller `execute` handler 与下游路由；
- 协作式暂停、取消和异常清理。

部署时将 Resource 文档放在 `<home>/manifests/`，Ability 放在 `<home>/abilities/`，KuudraProfile 放在 `<home>/profiles/`。Windows 无桌面测试环境启动 AWT 验证时需显式传入 `-Djava.awt.headless=false`；真实键鼠宏只应在可用的图形桌面会话中运行。
