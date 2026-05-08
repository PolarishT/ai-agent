package com.involutionhell.backend.rag.infrastructure.mq;

import com.involutionhell.backend.rag.shared.properties.RagProperties;
import jakarta.annotation.PostConstruct;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumerBuilder;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.core.RocketMQClientTemplate;
import org.apache.rocketmq.client.support.RocketMQMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * RAG RocketMQ 生产端配置。
 *
 * <p>该配置类只负责生产者侧能力，不承担消费者监听逻辑。
 * 只有在显式启用 RocketMQ 且提供必要连接参数时，才会创建发送索引任务所需的模板 Bean。
 */
@Configuration
@ConditionalOnProperty(prefix = "rag.rocketmq", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "rag.rocketmq", name = {"endpoints", "topic"})
public class RagRocketMqConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagRocketMqConfiguration.class);
    private final RagProperties ragProperties;

    public RagRocketMqConfiguration(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    @PostConstruct
    void logRocketMqConfigurationLoaded() {
        RagProperties.RocketMq rocketMq = ragProperties.rocketMq();
        log.info(
                "加载 RAG RocketMQ 配置。enabled={}, endpoints={}, topic={}, consumerGroup={}, sslEnabled={}",
                rocketMq.enabled(),
                rocketMq.endpoints(),
                rocketMq.topic(),
                rocketMq.consumerGroup(),
                rocketMq.sslEnabled()
        );
    }

    /**
     * 创建 RocketMQ 5.x gRPC 客户端模板。
     *
     * <p>该模板用于发送 RAG 离线索引消息，供 Outbox 分发器或直接发布链路复用。
     * 当应用里已经存在同类型模板时，这里不会重复装配。
     *
     * @param ragProperties            RAG 配置
     * @param rocketMQMessageConverter RocketMQ 消息转换器
     * @return 用于发送索引任务消息的 RocketMQ 客户端模板
     */
    @Bean(name = "rocketMQClientTemplate", destroyMethod = "destroy")
    @ConditionalOnMissingBean(RocketMQClientTemplate.class)
    public RocketMQClientTemplate rocketMQClientTemplate(
            RagProperties ragProperties,
            RocketMQMessageConverter rocketMQMessageConverter
    ) {
        RagProperties.RocketMq rocketMq = ragProperties.rocketMq();
        log.info(
                "初始化 RAG RocketMQ 生产者。endpoints={}, topic={}, sslEnabled={}, requestTimeoutSeconds={}",
                rocketMq.endpoints(),
                rocketMq.topic(),
                rocketMq.sslEnabled(),
                rocketMq.requestTimeoutSeconds()
        );

        ClientConfigurationBuilder clientConfigurationBuilder = ClientConfiguration.newBuilder()
                .setEndpoints(rocketMq.endpoints())
                .setRequestTimeout(Duration.ofSeconds(rocketMq.requestTimeoutSeconds()))
                .enableSsl(rocketMq.sslEnabled());

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ProducerBuilder producerBuilder = provider.newProducerBuilder()
                .setClientConfiguration(clientConfigurationBuilder.build())
                .setTopics(rocketMq.topic())
                .setMaxAttempts(rocketMq.maxAttemptTimes());

        SimpleConsumerBuilder simpleConsumerBuilder = provider.newSimpleConsumerBuilder()
                .setClientConfiguration(clientConfigurationBuilder.build())
                .setConsumerGroup(rocketMq.consumerGroup());

        RocketMQClientTemplate template = new RocketMQClientTemplate();
        template.setProducerBuilder(producerBuilder);
        template.setSimpleConsumerBuilder(simpleConsumerBuilder);
        template.setMessageConverter(rocketMQMessageConverter.getMessageConverter());
        return template;
    }


}
