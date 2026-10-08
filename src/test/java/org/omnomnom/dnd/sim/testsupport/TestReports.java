package org.omnomnom.dnd.sim.testsupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.opt.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.Genome;
import org.omnomnom.dnd.sim.domain.opt.Objectives;
import org.omnomnom.dnd.sim.domain.opt.Reports;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.omnomnom.dnd.sim.adapter.json.SimJacksonModule;

/** Hand-made reports and the JSON mapper the service uses, for the storage and API tests. */
public final class TestReports {

    private TestReports() {}

    public static ObjectMapper mapper() {
        return JsonMapper.builder().addModule(new SimJacksonModule()).build();
    }

    public static Reports.Entry entry(String key, String description, double score, Double campaign) {
        Map<String, Double> objectives = new LinkedHashMap<>();
        for (String name : Objectives.NAMES) {
            objectives.put(name, 0.5);
        }
        Genome g = new Genome(BuildClass.FIGHTER, List.of(0, 1, 2, 3, 4, 5), "Longsword", "Chain Mail", true, false, null);
        return new Reports.Entry(key, 0, g, description, new Reports.Metrics(0.5, 12.5, 0.25, 4, 16), objectives, score, campaign);
    }

    public static Reports.Report report(String runKey, double topScore) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("level", 3);
        config.put("role", "equal");
        config.put("classes", List.of("fighter"));
        config.put("campaign", false);
        config.put("seed", 7);
        Map<String, double[]> bounds = new LinkedHashMap<>();
        for (String name : Objectives.NAMES) {
            bounds.put(name, new double[] {0, 1});
        }
        Reports.Entry top = entry("fighter|012345|Longsword|Chain Mail|S|1H|-", "L3 fighter — Longsword", topScore, null);
        return new Reports.Report(Reports.VERSION, runKey, config, Objectives.NAMES, bounds, Reports.equalWeights(), List.of(top), List.of(top));
    }
}
