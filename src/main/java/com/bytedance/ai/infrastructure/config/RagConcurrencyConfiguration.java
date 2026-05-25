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
 * 事件线程。本类是 RAG 阻塞任务的集中入口：
 * <ul>
 *   <li>{@link #ragVirtualThreadExecutor(int)} —— 显式 {@code execute/submit} 共用的虚拟线程执行器。</li>
 *   <li>{@link #ragBlockingScheduler(ExecutorService)} —— Reactor 侧的 Scheduler 包装，
 *       承载 JDBC / Milvus / Spring AI 等阻塞调用。</li>
 * </ul>
 *
 * <p>虚拟线程本身很便宜，但外部资源并不便宜：LLM、Milvus、JDBC 和 RocketMQ 都需要
 * 应用侧背压。因此这里用 semaphore 给 per-task virtual thread executor 加统一上限。
 */
@Configuration
@EnableAsync
public class RagConcurrencyConfiguration {

    public static final String RAG_VIRTUAL_THREAD_EXECUTOR = "ragVirtualThreadExecutor";
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
