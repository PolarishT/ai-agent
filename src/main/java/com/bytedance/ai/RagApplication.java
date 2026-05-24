package com.bytedance.ai;

import com.bytedance.ai.infrastructure.nativeimage.RagRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.modulith.Modulith;

@SpringBootApplication
@Modulith(
        systemName = "Involution Hell RAG Backend",
        sharedModules = "shared"
)
@ImportRuntimeHints(RagRuntimeHints.class)
public class RagApplication {

    static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }

}
