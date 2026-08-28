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
  component: event-handler/actforever/awt-robot
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
