package org.omnomnom.dnd.sim.testsupport;

import org.omnomnom.dnd.sim.adapter.json.SimJacksonModule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The JSON mapper the service uses, for the storage tests and the wiring tests that run without Spring Boot. */
public final class TestMapper {

    private TestMapper() {}

    public static ObjectMapper mapper() {
        return JsonMapper.builder().addModule(new SimJacksonModule()).build();
    }
}
