package com.modocument.doccreation.rules;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock "today" is read from by the field rules (relative dates,
 * EXPIRY_DATE). UTC: banks have no time zone setting yet. Tests replace it
 * with a fixed clock.
 */
@Configuration(proxyBeanMethods = false)
public class RuleClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
