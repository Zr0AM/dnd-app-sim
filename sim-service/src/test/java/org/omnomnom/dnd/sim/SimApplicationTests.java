package org.omnomnom.dnd.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.in.web.security.AccessFilter;
import org.omnomnom.dnd.sim.adapter.out.report.D1Client;
import org.omnomnom.dnd.sim.adapter.out.report.FilesystemReportStore;
import org.omnomnom.dnd.sim.application.execution.SimLimits;
import org.omnomnom.dnd.sim.application.execution.SimulationExecutor;
import org.omnomnom.dnd.sim.application.job.JobService;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SimApplicationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    SimulationExecutor executor;

    @Test
    void contextLoadsAndHealthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void collaboratorsAreBeans() {
        assertThat(executor.call(() -> Thread.currentThread().getName())).startsWith("sim-worker-");
        assertThat(context.getBean("simulationPool")).isInstanceOf(ThreadPoolTaskExecutor.class);
        assertThat(context.containsBean("applicationTaskExecutor")).as("the simulation pool does not displace Boot's executor").isTrue();
        assertThat(context.getBean(ReportStore.class)).isInstanceOf(FilesystemReportStore.class);
        assertThat(context.getBeanNamesForType(D1Client.class)).isEmpty();
        for (Class<?> type : new Class<?>[] {Clock.class, JobService.Settings.class, SimLimits.class, AccessFilter.class}) {
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).hasSize(1);
        }
    }
}
