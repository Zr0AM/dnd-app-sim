package org.omnomnom.dnd.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.SimProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class LocalProfileTests {

    @Autowired
    SimProperties props;

    @Test
    void localProfileNeedsNoCloudflareCredentials() {
        assertThat(props.reportStore().type()).isEqualTo(SimProperties.StoreType.FILESYSTEM);
        assertThat(props.d1().enabled()).isFalse();
        assertThat(props.d1().isConfigured()).isFalse();
        assertThat(props.executor().threads()).isEqualTo(2);
    }
}
