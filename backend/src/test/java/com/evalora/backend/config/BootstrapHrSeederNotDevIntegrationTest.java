package com.evalora.backend.config;

import com.evalora.backend.entity.enums.Role;
import com.evalora.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without the {@code dev} profile the seeder must not exist at all, even when
 * credentials are configured.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalora_bootstrap_hr_no_dev;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "evalora.bootstrap.hr.email=bootstrap.hr@evalora.local",
        "evalora.bootstrap.hr.password=Bootstrap@2026"
})
class BootstrapHrSeederNotDevIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private UserRepository userRepository;

    @Test
    void seederIsNotRegisteredOutsideTheDevProfile() {
        assertThat(applicationContext.getBeansOfType(BootstrapHrSeeder.class)).isEmpty();
    }

    @Test
    void noAccountIsCreatedOutsideTheDevProfile() {
        assertThat(userRepository.findByEmailIgnoreCase("bootstrap.hr@evalora.local")).isEmpty();
        assertThat(userRepository.existsByRole(Role.HR)).isFalse();
    }
}
