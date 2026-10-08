package org.omnomnom.dnd.sim.config;

import org.omnomnom.dnd.sim.adapter.in.web.SimJacksonModule;
import org.omnomnom.dnd.sim.adapter.out.SimProperties;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.application.ContentCatalogs;
import org.omnomnom.dnd.sim.application.ContentService;
import org.omnomnom.dnd.sim.application.EncounterService;
import org.omnomnom.dnd.sim.application.EvaluationService;
import org.omnomnom.dnd.sim.application.SimulationExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The composition root: builds the immutable catalogs once at startup (the seed database is opened, read and closed),
 * the bounded simulation executor, and the use cases. Application classes carry no framework annotations; this is the
 * only place they meet Spring.
 */
@Configuration
class SimConfig {

    @Bean
    ContentCatalogs contentCatalogs() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            return ContentCatalogs.load(source);
        }
    }

    @Bean(destroyMethod = "close")
    SimulationExecutor simulationExecutor(SimProperties props) {
        return new SimulationExecutor(props.executor().threads(), props.executor().queueCapacity());
    }

    @Bean
    EncounterService encounterService(ContentCatalogs catalogs, SimulationExecutor executor) {
        return new EncounterService(catalogs, executor);
    }

    @Bean
    EvaluationService evaluationService(ContentCatalogs catalogs, SimulationExecutor executor) {
        return new EvaluationService(catalogs, executor);
    }

    @Bean
    ContentService contentService(ContentCatalogs catalogs) {
        return new ContentService(catalogs);
    }

    @Bean
    SimJacksonModule simJacksonModule() {
        return new SimJacksonModule();
    }
}
