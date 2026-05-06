package com.lightnote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.provider")
@Data
public class AiProviderProperties {
    private String type = "native";


    public boolean isNative()      { return "native".equalsIgnoreCase(type); }
    public boolean isSpringAi()    { return "spring-ai".equalsIgnoreCase(type); }
    public boolean isLangChain4j() { return "langchain4j".equalsIgnoreCase(type); }
}
