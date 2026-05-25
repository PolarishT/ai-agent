package com.bytedance.ai.infrastructure.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    private final AsyncTaskExecutor applicationTaskExecutor;
    private final long defaultTimeoutMillis;

    public WebMvcAsyncConfig(
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor applicationTaskExecutor,
            @Value("${app.web.async.default-timeout-millis:60000}") long defaultTimeoutMillis
    ) {
        this.applicationTaskExecutor = applicationTaskExecutor;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(this.applicationTaskExecutor);
        configurer.setDefaultTimeout(this.defaultTimeoutMillis);
    }
}
