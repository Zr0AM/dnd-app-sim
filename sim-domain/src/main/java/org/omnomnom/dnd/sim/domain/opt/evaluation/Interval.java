package org.omnomnom.dnd.sim.domain.opt.evaluation;

/** A confidence interval around a point estimate. */
public record Interval(double point, double lo, double hi, double halfWidth) {}
