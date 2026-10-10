package com.modocument.infrastructure.persistence;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Wires the two {@code app.tenant} bindings that drive the V13 row-level
 * security policies:
 * <ul>
 *   <li>{@link TenantAwareJpaTransactionManager} replaces Boot's
 *       auto-configured {@code JpaTransactionManager} (declared as the
 *       {@code transactionManager} bean and {@code @Primary}), binding the
 *       tenant with SET LOCAL semantics at every transaction begin;</li>
 *   <li>{@link TenantAwareDataSource} wraps the primary {@code dataSource}
 *       bean, binding the tenant at every connection checkout — covering
 *       the non-transactional read paths (Spring Data derived finders,
 *       JdbcTemplate) a transaction manager never sees.</li>
 * </ul>
 * There is no other transaction-manager or datasource bean in the
 * application (DatabaseExportService/JdbcSourceConnector build throwaway
 * ones for EXTERNAL targets, which must not be tenant filtered).
 */
@Configuration
public class TenantPersistenceConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantAwareJpaTransactionManager manager =
                new TenantAwareJpaTransactionManager(entityManagerFactory);
        customizers.ifAvailable(customizer -> customizer.customize(manager));
        return manager;
    }

    /**
     * Static so it is registered before ordinary bean creation; wraps only
     * the application's primary datasource (Flyway's owner connection, when
     * separate credentials are configured, is built internally by its
     * auto-configuration and intentionally stays unwrapped — the owner
     * bypasses RLS anyway).
     */
    @Bean
    public static BeanPostProcessor tenantAwareDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && "dataSource".equals(beanName)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}
