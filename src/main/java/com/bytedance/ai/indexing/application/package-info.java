/**
 * indexing 模块应用层。
 *
 * <p>本包负责串联文档 SPI、索引工作流、outbox 投递、补偿恢复、时间线查询和控制器面向的用例编排。
 * 这一层可以调用 workflow、persistence 与 service 层能力，但不向其它模块暴露表结构、Repository
 * 或具体消息实现；跨模块入口应收敛到 {@code api} / {@code spi} 包中。
 */
package com.bytedance.ai.indexing.application;
