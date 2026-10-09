package org.omnomnom.dnd.sim.testsupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Objectives;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;

/** Hand-made reports for the use-case, storage and API tests; {@code TestMapper} has the JSON mapper the service uses. */
public final class TestReports {

    private TestReports() {}

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
