/**
 * @author HXN
 * @date 2026-09-15
 * @description AI 白盒测试线程池与阈值配置
 */
package com.platform.whitebox.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 白盒测试引擎线程池配置
 *
 * <p>使用独立线程池，单线程 + 有界队列确保同一时间最多执行一个白盒任务
 * （构建被测项目与 LLM 调用均较为耗时，避免并发争抢）。</p>
 */
@Configuration
public class AiWhiteboxConfig {

    @Value("${ai-whitebox.max-concurrent-tasks:1}")
    private int maxConcurrentTasks;

    @Value("${ai-whitebox.compile-fix-rounds:3}")
    private int compileFixRounds;

    @Value("${ai-whitebox.exec-fix-rounds:3}")
    private int execFixRounds;

    @Value("${ai-whitebox.quality-rewrite-rounds:1}")
    private int qualityRewriteRounds;

    @Value("${ai-whitebox.mutation-threshold:60}")
    private int mutationThreshold;

    @Value("${ai-whitebox.branch-coverage-threshold:75}")
    private int branchCoverageThreshold;

    @Value("${ai-whitebox.max-methods-per-task:20}")
    private int maxMethodsPerTask;

    @Bean(name = "whiteboxExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor whiteboxExecutor() {
        AtomicInteger counter = new AtomicInteger(0);
        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "whitebox-task-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                maxConcurrentTasks,
                maxConcurrentTasks,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(5),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public int getCompileFixRounds() {
        return compileFixRounds;
    }

    public int getExecFixRounds() {
        return execFixRounds;
    }

    public int getQualityRewriteRounds() {
        return qualityRewriteRounds;
    }

    public int getMutationThreshold() {
        return mutationThreshold;
    }

    public int getBranchCoverageThreshold() {
        return branchCoverageThreshold;
    }

    public int getMaxMethodsPerTask() {
        return maxMethodsPerTask;
    }
}
