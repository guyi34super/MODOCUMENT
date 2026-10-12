package com.modocument.testsupport;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The ONE production-shaped H2 test context: dev tooling off
 * ({@code permit-dev-paths=false}), so the startup guards demand a real JWT
 * secret, a dedicated MFA key and a bootstrap admin (test-only values
 * below), and the strict API CSP / authenticated API docs apply.
 *
 * <p>Every class that needs production mode uses this annotation instead of
 * its own {@code @SpringBootTest(properties = …)} copy, so Spring's context
 * cache builds the context once (it used to be one context per class with
 * the same meaning and different secrets). Own in-memory database: this
 * context's {@code ddl-auto=create} must not wipe the schema the shared
 * test contexts are using.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(properties = {
        "modocument.security.permit-dev-paths=false",
        // Low-entropy test-only values (the HeaderContractTest set, which the
        // gitleaks gate accepts without a pin); no deployment uses them.
        "modocument.security.jwt.secret=header-contract-test-signing-key-0123456789abcdef",
        "modocument.security.mfa.key=header-contract-test-mfa-encryption-key-0123456789",
        "MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL=" + ProdShapedSpringBootTest.BOOTSTRAP_ADMIN_EMAIL,
        "MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD=Header-Contract-Passphrase-2026!",
        "spring.datasource.url=jdbc:h2:mem:modocument-prod-shaped;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public @interface ProdShapedSpringBootTest {

    /** The first admin the bootstrap runner creates in this context. */
    String BOOTSTRAP_ADMIN_EMAIL = "prod-shaped-admin@example.com";
}
