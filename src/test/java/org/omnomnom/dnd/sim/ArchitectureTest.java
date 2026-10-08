package org.omnomnom.dnd.sim;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
