package org.omnomnom.dnd.sim.domain.opt;

/** A confidence interval around a point estimate. */
public record Interval(double point, double lo, double hi, double halfWidth) {}
