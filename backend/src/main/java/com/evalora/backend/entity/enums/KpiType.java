package com.evalora.backend.entity.enums;

/**
 * Who owns the rating of a KPI.
 *
 * <p>{@link #MANAGER} KPIs are rated by the reviewing manager and form the
 * 75% manager evaluation; HR also contributes the 25% HR component for them,
 * exactly as before. {@link #HR} KPIs are rated by HR only and are never
 * editable by a manager.</p>
 */
public enum KpiType {
    MANAGER,
    HR
}
