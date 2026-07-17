package com.org.llm;

import com.org.llm.mcpgateway.guardrail.InjectionGuardProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InjectionGuardProperties.class)
class LlmMcpGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmMcpGatewayApplication.class, args);
    }

}
