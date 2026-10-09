package org.omnomnom.dnd.sim.adapter.in.web.content;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.omnomnom.dnd.sim.adapter.in.web.validation.ValidLevel;
import org.omnomnom.dnd.sim.application.content.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only reference data (the CLI {@code browse} mode and the lists a client needs to build requests). */
@RestController
@RequestMapping("/api/v1/content")
class ContentController {

    private final ContentService content;

    ContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/classes")
    List<ContentService.ClassView> classes(@RequestParam @ValidLevel int level) {
        return content.classes(level);
    }

    @GetMapping("/roles")
    List<ContentService.RoleView> roles() {
        return content.roles();
    }

    @GetMapping("/scenarios")
    List<ContentService.ScenarioView> scenarios(@RequestParam @ValidLevel int level) {
        return content.scenarios(level);
    }

    @GetMapping("/monsters")
    ContentService.MonsterPage monsters(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DecimalMin("0") Double minCr,
            @RequestParam(required = false) @DecimalMin("0") Double maxCr,
            @RequestParam(defaultValue = "100") @Min(1) @Max(400) int limit,
            @RequestParam(required = false) String cursor) {
        return content.monsters(q, minCr, maxCr, limit, cursor);
    }

    @GetMapping("/weapons")
    List<ContentService.WeaponView> weapons(@RequestParam @ValidLevel int level) {
        return content.weapons(level);
    }

    @GetMapping("/armors")
    List<ContentService.ArmorView> armors(@RequestParam @ValidLevel int level) {
        return content.armors(level);
    }

    @GetMapping("/maps")
    List<ContentService.MapView> maps() {
        return content.maps();
    }

    @GetMapping("/party-templates")
    List<ContentService.PartyTemplateView> partyTemplates() {
        return content.partyTemplates();
    }
}
