package com.lc33.tokenvault.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * boot 存储（§6.1、红线 26）。
 *
 * 接口存在的理由是**能在 JVM 单测里换实现**：原子写与损坏检测是这一层最需要被测到的部分，
 * 而它们恰好也是最难在真机上复现的（要在 fsync 与 rename 之间断电）。
 *
 * 阶段3：接口迁入 commonMain，平台实现（[FileBootStore] / iOS 版）留在各端。
 */
interface BootStore {
    fun read(): BootState

    /** 原子写。失败抛异常，**不允许静默丢弃**——写不进去意味着下次启动会回到旧状态。 */
    fun write(record: BootRecord)

    /**
     * 读改写。
     *
     * 提供这个组合操作而不是让调用方自己读写，是因为 boot 的每一次改动都很小
     * （累加一次失败计数、翻一个开关），而每次写都是一次撕裂风险——把它收在一处，
     * "没变就不写"这条优化才有地方放。
     *
     * 全新安装时会先造一份带新 [BootRecord.deviceId] 的空记录再交给 [transform]。
     * 若 [transform] 什么都没改，**不落盘**——一份只有 deviceId、没有任何包裹的 boot 文件
     * 对调用方来说与 [BootState.Missing] 等价，写它只是白担一次撕裂风险。
     * 代价是 `deviceId` 在第一次真正写入之前不稳定，而在那之前它也没有被任何人引用。
     */
    fun update(transform: (BootRecord) -> BootRecord): BootRecord

    /** 清空重来（`BootCorrupt` 页那个二次确认之后的出口）。 */
    fun clear()

    /**
     * 落盘次数。每次成功的 [write] 与 [clear] 之后 +1。
     *
     * 存在的理由是红线 10 的精神：观察者从数据源本身知道"该重读了"，而不是靠每个写入方
     * 顺手通知一声。漏通知的那一处永远是最后加进来的写入点，而它的表现是
     * "在设置里改了，界面没动"——这种 bug 编译得过、测试也未必抓得到。
     *
     * 只带一个计数而不带记录本身：boot 里绝大多数改动（累加失败计数、包裹换一份）
     * 对观察者毫无意义，把整条记录推出去只会让每个观察者都得自己判断"这次跟我有关吗"。
     */
    val revision: StateFlow<Long>
}
