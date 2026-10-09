package org.omnomnom.dnd.sim.adapter.in.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.omnomnom.dnd.sim.application.content.ContentCatalogs;

/** A hero level the content is authored for (3, 5, 11 or 17). Null is left to {@code @NotNull}. */
@Documented
@Constraint(validatedBy = ValidLevel.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidLevel {

    String message() default "must be one of 3, 5, 11, 17";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    public final class Validator implements ConstraintValidator<ValidLevel, Integer> {
        @Override
        public boolean isValid(Integer value, ConstraintValidatorContext context) {
            return value == null || ContentCatalogs.CHECKPOINT_LEVELS.contains(value);
        }
    }
}
