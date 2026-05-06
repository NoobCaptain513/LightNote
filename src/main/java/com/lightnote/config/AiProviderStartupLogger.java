package com.lightnote.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AiProviderStartupLogger implements CommandLineRunner {
    private final AiProviderProperties aiProviderProperties;

    @Override
    public void run(String... args) {
        log.info("AI provider active: {}", aiProviderProperties.getType());
    }
}
