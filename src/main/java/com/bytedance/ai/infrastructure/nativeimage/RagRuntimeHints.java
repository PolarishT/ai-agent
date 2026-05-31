package com.bytedance.ai.infrastructure.nativeimage;

import com.bytedance.ai.common.api.ApiResponse;
import com.bytedance.ai.document.api.RagDocumentCreateRequest;
import com.bytedance.ai.document.api.RagDocumentUpdateRequest;
import com.bytedance.ai.document.api.RagDocumentView;
import com.bytedance.ai.indexing.api.RagIndexJobView;
import com.bytedance.ai.indexing.api.RagIndexOutboxView;
import com.bytedance.ai.indexing.api.RagIndexTimelineDocumentView;
import com.bytedance.ai.indexing.api.RagIndexTimelineView;
import com.bytedance.ai.indexing.api.RagIndexTransitionView;
import com.bytedance.ai.indexing.messaging.RagIndexMessage;
import com.bytedance.ai.shared.properties.RagProperties;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * 为 native image 显式注册当前应用里需要运行时绑定的类型。
 *
 * <p>这里主要覆盖三类对象：
 * 1. Controller 层请求/响应 DTO；
 * 2. RocketMQ 通过统一 JSON codec 读写的消息体；
 * 3. {@link RagProperties} 及其嵌套配置记录。
 */
public class RagRuntimeHints implements RuntimeHintsRegistrar {

    private static final BindingReflectionHintsRegistrar BINDING_HINTS =
            new BindingReflectionHintsRegistrar();

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        BINDING_HINTS.registerReflectionHints(
                hints.reflection(),
                ApiResponse.class,
                RagDocumentCreateRequest.class,
                RagDocumentUpdateRequest.class,
                RagDocumentView.class,
                RagIndexJobView.class,
                RagIndexTimelineDocumentView.class,
                RagIndexTimelineView.class,
                RagIndexOutboxView.class,
                RagIndexTransitionView.class,
                RagIndexMessage.class,
                RagProperties.class
        );
    }
}
