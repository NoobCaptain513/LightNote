package com.lightnote.config;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PgVectorFlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway pgVectorFlyway(PgVectorProperties properties) {
        return Flyway.configure()
                .dataSource(properties.getUrl(), properties.getUsername(), properties.getPassword())
                .locations("classpath:db/migration/pgvector")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }
}
