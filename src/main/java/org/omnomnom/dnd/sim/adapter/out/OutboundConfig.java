package org.omnomnom.dnd.sim.adapter.out;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SimProperties.class)
class OutboundConfig {}
