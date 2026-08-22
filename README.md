# Kuudra

本分支 `refactor/kuudra-kernel` 是对旧 GTAV Ops/Orcana 代码的底层重构。当前 Maven 构建只包含 `kuudra-*` 模块；下方内容仅是旧项目历史笔记，不参与构建或当前架构设计。最新设计见 [Kuudra 架构文档](docs/kuudra-architecture.md)，最小可运行内核见 `kuudra-app`。

# 历史：GTAV Ops

对GTAV的一些技巧提供了更为宏实现，待办列表:

- 修复延迟攀第一次不稳定的问题，为延迟攀添加结束时自动躲入掩体的功能
- 使快速点火支持双击自动开门，单击仅点火的功能
- RPG连发
  - 拓展更多连发键位

独立前端本地化逻辑

后端配置逻辑

切枪宏tab确认



连发 优先级设置
// M自动零食

断网 卡单 崩溃游戏（自动删除pc settings）
多套配置
左轮连发

TAB补甲
近战偷速补甲

// 删除字体识别第三方库相关资源

改用自定义日志实现，并通过将自定义日志桥接到VFX日志实现日志面板 调试模式

配置列表 VlistConfigurationPane

主题

语言

拆分MainFX到Main

gui -> config -> featuretogglepane -> macroregistry

RTSS调用

快速切枪问题修复

普通偷速Tab清除键修复

宏重载逻辑修复：目前推测宏重载后无法正常运行部分逻辑，例如偷速，目前必须依靠重启宏进程来解决

AD摇问题修复

滚轮值输入

自动更新：提供更新进程

- 添加断网功能
- 添加基于进程挂起实现的卡单功能

- 添加断网功能
- 添加基于进程挂起实现的卡单功能

---

- 回血增强 ✔
- 安全轮盘有效期 ✔
- 延迟攀爬
- M菜单
  - 自动点火 ✔
  - 自动开门
  - 补弹药 
  
- 近战偷速
  - 自动补充护甲

- 字体包管理 
  - 基础字体包管理 ✔
  - 更好的字体包管理
  - 添加进度条 ✔

- AD摇 ✔
- 一键断网
- 一键卡单
- 卡末日前置
- 外置准星
- 计时功能
- 游戏外自动屏蔽快捷键
- 游戏手柄LT映射
- RPG连发


- 应用程序设置页面
  - 允许设置在指定应用程序外挂起宏 ✔



# 宏代码架构

从宏观上看：

- 对于事件源派发：GTAV-Ops遵循单线程架构，这意味着：你不可能通过快速触发事件源，来实现同一个宏在两个线程上并行执行

> 这种行为是`jnativehook`和`GTAV-Ops`的性质共同决定的，两者在用户多次触发事件时，都不会进行新线程派生，理论上新的触发必须要在上一次触发后续动作彻底结束之后才会得到执行
>
> 这同时也能作为一种防抖处理，避免某些宏真正的并行导致异常

- 对于宏行为的执行（本质上是Action执行）：
  - 对于一般执行宏（通常是Action）：它们作为事件源派生的后续执行动作，直接串行运行，不存在任何线程派生，因此理论上两个Action将无法并行执行，必须等待前一个Action彻底结束
  - 对于循环调度宏（本质上是ScheduledAction）：GTAV-Ops基于`java.util.concurrent.ScheduledExecutorService`申请独立的线程用于执行循环动作，避免阻塞

## InputSource

`InputSource`是整个宏的出发点，它定义了监听设备输入源的具体逻辑，这种监听主要是通过第三方库实现的，并可以通过引入更多的第三方库来拓展自身可监听的范围，例如实现对手柄等更多外设的支持

> ⚠注：目前GTAV-Ops仅基于`com.github.kwhat.jnativehook.keyboard.NativeKeyListener`对鼠标、键盘、滚轮进行事件监听，暂未拓展更多的输入源（以下简称`jnativehook`）
>
> 由于GTA-Ops的键盘、鼠标输入源监听依赖`com.github.kwhat.jnativehook`，因此对某些按键、鼠标侧键的支持情况会依赖该库对鼠标、键盘的监听支持是否完整

`cc.pineclone.server.macro.trigger.source.InputSource.java`是所有事件源的父类，它定义了包括：

- `install/uninstall`方法：调用这个方法来将事件源事件源从`jnativehook`提供的全局监听器`GlobalScreen`当中添加或移除
- `listener`属性：定义当前`InputSource`的监听器，当`jnativehook`（或其他第三方库）触发目标事件时，原始事件会被`InputSource`实现类封装成`InputSourceEvent`，一个完整的`InputSourceEvent`描述了该事件源是如何触发的，来自什么事件、按键等等，封装好的`InputSourceEvent`对象会被传递给监听器`listener`

> 任何希望监听`InputSourceEvent`事件的类必须实现`InputSourceListener`接口

目前GTA-Ops提供了基本的键盘、鼠标输入源监听类，它们均基于`jnativehook`编写：

- `KeyboardSource`：键盘事件源
- `MouseButtonSource`：鼠标按键事件源，包括前后侧键、左右键以及中键
- `MouseScrollSource`：鼠标滚轮事件源，包括上下滚轮

## ActivationPolicy

`ActivationPolicy`是另一个重要的机制，它定义了对`InputSourceEvent`事件的“语义归一化”逻辑

> 语义归一化（Semantic Normalization）指的是将语义上等价、但来源不同的状态、输入、上下文映射为一组有限、同一且规范的语义，以便后续逻辑处理

在GTAV-Ops当中，`ActivationPolicy`提供了`decide(InputSourceEvent event)`方法，该方法接收来自某个`InputSource`被触发后产生的封装事件，并根据具体实现类的逻辑，返回决定是否触发的语义命令：

- `1`：令触发器（见下一节）执行“启用动作”
- `0`：令触发器执行“停止动作”
- `-1`：令触发器忽略此次动作

基于这些语义命令，GTAV-Ops实现了三种对应的触发行为模式：

- `TogglePolicy`：切换触发模式，判断`InputSourceEvent`的操作类型，例如在`KEY_PRESSED` `MOUSE_PRESS`两种操作类型时切换toggle状态，实现“按下第一次时返回1，第二次时返回0”，从而实现切换效果，即第一次按下按键时启动，第二次按下按键时停止
- `HoldPolicy`：按住触发模式，判断`InputSourceEvent`的操作类型，例如在`*_PRESSED`时返回1，`*_RELEASED`返回0，那么就能实现一种按压触发的效果，即按下时启动，松开时停止
- `ClickPolicy`：点按触发模式，判断`InputSourceEvent`操作类型，在每一次`*_PRESSED`或`MOUSE_WHEEL_MOVED`事件时都返回1，其余事件全部忽略，即每一次按下都会激活的效果

## Trigger

`Trigger`本身实现了`MacroLifecycleAware`接口，这使得任何`Trigger`都能感知到`Macro`（见下下下节）的生命周期执行情况，例如宏的下载、卸载、挂起以及唤醒，Trigger提供了如下基础属性：

- `List<TriggerListener> listeners`字段，该字段维护了所有当前Trigger的监听器，通常是一个Macro（宏）
- `activate(TriggerEvent event)`：由Trigger触发的事件，所有的`TriggerListener`都会被通知，表示开始执行
- `deactivate(TriggerEvent event)`：同上，表示停止执行

> ⚠注：对于Trigger，它不再采用ActivationPolicy那样的状态位判断，而是基于0/1状态位判断后，直接使用`activate/deactivate`方法表示启用/停止具体的逻辑

### SimpleTrigger

`SimpleTrigger`是最纯粹的`Trigger`实现类，它使用了桥接设计模式设计，将`InputSource`和`ActivationPolicy`两个抽象类桥接到同一层级，创建一个SimpleTrigger需要同时传入两者；其中前者用于描述监听哪些事件，后者用于描述监听到事件之后如何触发（如点按、长按、切换）

`SimpleTrigger`除了直接继承Trigger，还实现了InputSourceListener接口，这意味着它会直接得到`InputSource`触发的事件（基于`SimpleTrigger`构造器注入`InputSource`实例），当接收`InputSource`触发事件之后，`SimpleTrigger`会基于`ActivationPolicy`进行归一化，基于归一化的结果：

- Policy返回`1`：调用`activate`方法，该方法会遍历所有的`listener`并调用其`onTriggerActivate`方法
- Policy返回`0`：调用`deactivate`方法，该方法会遍历所有的listener并调用其`onTriggerDeactivate`方法

### CompositeTrigger

`CompositeTrigger`是一个特殊的`Trigger`，它不负责实现`InputSource` `ActivationPolicy`之间的桥接功能，而是专注于组合两个或以上的`Trigger`来成为一个组合`Trigger`

> 因此`CompositeTrigger`自身也实现了`TriggerListener`接口，因为它需要监听来自其组合旗下的所有Trigger触发的`activate/deactivate`事件

`CompositerTrigger`在创建阶段要求传入一个或多个Trigger，`CompositeTrigger`会将自己作为监听器（TriggerListener）加入这些Trigger的`listeners`列表当中，从而监听它们的`activate/deactivate`，然后：

- 仅当所有子`Trigger`的`activate`被执行时，CompositeTrigger的`activate`方法才会被执行
- 仅当所有子`Trigger`的`deactivate`被执行时，CompositeTrigger的`deactivate`方法才会被执行











