package com.bytedance.ai.infrastructure.config;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * RAG 链路与跨模块异步任务的并发执行配置。
 *
 * <p>项目并发政策要求同进程阻塞任务优先落到虚拟线程上，避免占用 Web / Reactor
 * 事件线程。本类提供两类用途不同的虚拟线程执行器：
 * <ul>
 *   <li>{@link #ragVirtualThreadExecutor(int)} —— <strong>叶子级阻塞 I/O</strong> 专用，
 *       直接打 LLM / Milvus / JDBC / RocketMQ 等外部资源。用 semaphore 加统一上限做应用侧背压。</li>
 *   <li>{@link #ragOrchestrationExecutor()} —— <strong>编排级 fan-out</strong> 专用，
 *       只负责并行调度子任务再 {@code join} 聚合，自身不直接打外部资源。无界、不占背压额度。</li>
 *   <li>{@link #ragBlockingScheduler(ExecutorService)} —— Reactor 侧的 Scheduler 包装，
 *       复用 {@link #ragVirtualThreadExecutor(int)} 承载阻塞调用。</li>
 * </ul>
 *
 * <p>虚拟线程本身很便宜，但外部资源并不便宜：LLM、Milvus、JDBC 和 RocketMQ 都需要
 * 应用侧背压，因此叶子 I/O 池用 semaphore 加上限。
 *
 * <p><strong>为什么编排和叶子 I/O 必须用两个池：</strong>{@link BoundedExecutorService#execute}
 * 在<em>提交方线程</em>上同步 {@code acquire} permit。如果一个已持有 permit 的任务，又同步往
 * 同一个 bounded 池提交子任务并 {@code join}（典型如 keyword 召回 → fan-out 多路 evidence），
 * 高并发下父任务会占满 permit、子任务拿不到 permit，父任务又在等子任务，形成嵌套饥饿乃至死锁。
 * 让编排任务跑在<em>无界</em>的 {@link #ragOrchestrationExecutor()} 上、只有真正打外部资源的叶子
 * 占 permit，即可彻底断开这条环：编排任务永远能起来、能 join、能完成并释放它调度的叶子 permit。
 * <strong>切勿把编排 fan-out 改回 bounded 池。</strong>
 */
@Configuration
@EnableAsync
public class RagConcurrencyConfiguration {

    public static final String RAG_VIRTUAL_THREAD_EXECUTOR = "ragVirtualThreadExecutor";
    public static final String RAG_ORCHESTRATION_EXECUTOR = "ragOrchestrationExecutor";
    public static final String RAG_BLOCKING_SCHEDULER = "ragBlockingScheduler";

    @Bean(name = RAG_VIRTUAL_THREAD_EXECUTOR, destroyMethod = "close")
    public ExecutorService ragVirtualThreadExecutor(
            @Value("${rag.concurrency.max-blocking-tasks:64}") int maxBlockingTasks
    ) {
        ThreadFactory threadFactory = Thread.ofVirtual().name("rag-vt-", 0).factory();
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(threadFactory);
        return new BoundedExecutorService(delegate, maxBlockingTasks);
    }

    /**
     * 编排级 fan-out 专用执行器：<strong>无界</strong>、不加 semaphore。
     *
     * <p>只承载「并行调度子任务再 join 聚合」这类自身不打外部资源的编排任务（如商品 keyword 召回
     * fan-out 多路 evidence）。它们必须能无条件起来并完成，才能及时释放自己调度的、占着
     * {@link #ragVirtualThreadExecutor(int)} permit 的叶子任务——这正是断开嵌套 permit 死锁环的关键，
     * 故这里<strong>刻意不设上限</strong>。背压只应落在真正稀缺的外部资源（叶子 I/O 池）上。
     */
    @Bean(name = RAG_ORCHESTRATION_EXECUTOR, destroyMethod = "close")
    public ExecutorService ragOrchestrationExecutor() {
        ThreadFactory threadFactory = Thread.ofVirtual().name("rag-orch-", 0).factory();
        return Executors.newThreadPerTaskExecutor(threadFactory);
    }

    /**
     * Reactor 侧复用同一组虚拟线程执行 JDBC、Milvus 和 Spring AI 等阻塞调用。
     */
    @Bean(name = RAG_BLOCKING_SCHEDULER)
    public Scheduler ragBlockingScheduler(@Qualifier(RAG_VIRTUAL_THREAD_EXECUTOR) ExecutorService executorService) {
        return Schedulers.fromExecutor(executorService);
    }

    private static final class BoundedExecutorService extends AbstractExecutorService {

        private final ExecutorService delegate;
        private final Semaphore permits;

        private BoundedExecutorService(ExecutorService delegate, int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException("rag.concurrency.max-blocking-tasks must be at least 1");
            }
            this.delegate = delegate;
            this.permits = new Semaphore(maxConcurrency);
        }

        @Override
        public void execute(@NonNull Runnable command) {
            acquirePermit();
            try {
                this.delegate.execute(() -> {
                    try {
                        command.run();
                    } finally {
                        this.permits.release();
                    }
                });
            } catch (RuntimeException exception) {
                this.permits.release();
                throw exception;
            }
        }

        @Override
        public void shutdown() {
            this.delegate.shutdown();
        }

        @Override
        public @NonNull List<Runnable> shutdownNow() {
            return this.delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return this.delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return this.delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
            return this.delegate.awaitTermination(timeout, unit);
        }

        private void acquirePermit() {
            try {
                this.permits.acquire();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for RAG blocking executor capacity", exception);
            }
        }
    }
}
