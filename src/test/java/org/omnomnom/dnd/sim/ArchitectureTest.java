package org.omnomnom.dnd.sim;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@AnalyzeClasses(packages = "org.omnomnom.dnd.sim", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "org.omnomnom.dnd.sim";

    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "jakarta..", "javax.persistence..", "com.fasterxml..", "tools.jackson..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".application..", ROOT + ".adapter..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage(ROOT + ".application..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule inboundAdaptersDoNotDependOnOutboundAdapters = noClasses()
            .that().resideInAPackage(ROOT + ".adapter.in..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter.out..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule outboundAdaptersDoNotDependOnInboundAdapters = noClasses()
            .that().resideInAPackage(ROOT + ".adapter.out..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter.in..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationIsFrameworkFree = noClasses()
            .that().resideInAPackage(ROOT + ".application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "tools.jackson..", "com.fasterxml..")
            .allowEmptyShould(true);

    // ---- inside the domain: the engine at the bottom, the optimizer at the top ----------------------

    @ArchTest
    static final ArchRule combatDoesNotReachUp = noClasses()
            .that().resideInAPackage(ROOT + ".domain.combat..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".domain.content..", ROOT + ".domain.scenario..", ROOT + ".domain.opt..", ROOT + ".domain.ai..");

    @ArchTest
    static final ArchRule contentDoesNotReachUp = noClasses()
            .that().resideInAPackage(ROOT + ".domain.content..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".domain.scenario..", ROOT + ".domain.opt..");

    @ArchTest
    static final ArchRule scenariosDoNotReachUp = noClasses()
            .that().resideInAPackage(ROOT + ".domain.scenario..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.opt..");

    @ArchTest
    static final ArchRule foundationsStandAlone = noClasses()
            .that().resideInAnyPackage(ROOT + ".domain.core..", ROOT + ".domain.rng..", ROOT + ".domain.dice..", ROOT + ".domain.grid..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".domain.combat..", ROOT + ".domain.content..", ROOT + ".domain.scenario..", ROOT + ".domain.opt..",
                    ROOT + ".domain.ai..");

    // ---- no package cycles at any level ------------------------------------------------------------

    @ArchTest
    static final ArchRule domainPackagesAreAcyclic = slices().matching(ROOT + ".domain.(**)").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule applicationPackagesAreAcyclic = slices().matching(ROOT + ".application.(**)").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule webPackagesAreAcyclic = slices().matching(ROOT + ".adapter.in.web.(**)").should().beFreeOfCycles();

    // ---- Spring wiring lives in config -------------------------------------------------------------

    /** Every bean is declared in the composition root, so the classes it wires stay plain and constructible by hand. */
    @ArchTest
    static final ArchRule beansAreDeclaredInConfig = methods()
            .that().areAnnotatedWith(Bean.class)
            .should().beDeclaredInClassesThat().resideInAPackage(ROOT + ".config..");

    @ArchTest
    static final ArchRule configurationClassesLiveInConfig = classes()
            .that().areMetaAnnotatedWith(Configuration.class)
            .should().resideInAPackage(ROOT + ".config..")
            .orShould().haveSimpleName("SimApplication");

    /** Component scanning picks up only the web edge (controllers, advice); everything else is a {@code @Bean}. */
    @ArchTest
    static final ArchRule scannedComponentsAreWebOrConfig = classes()
            .that().areMetaAnnotatedWith(Component.class)
            .should().resideInAnyPackage(ROOT + ".adapter.in.web..", ROOT + ".config..")
            .orShould().haveSimpleName("SimApplication");

    @ArchTest
    static final ArchRule onlyConfigReadsSettings = noClasses()
            .that().resideOutsideOfPackages(ROOT + ".config..")
            .and().doNotHaveSimpleName("SimApplication")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".config..");
}
