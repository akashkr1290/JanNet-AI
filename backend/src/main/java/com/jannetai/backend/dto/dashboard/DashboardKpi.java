package com.jannetai.backend.dto.dashboard;

/**
 * A KPI tile of the Government / Admin dashboard whose complaints can be
 * listed (GET /api/v1/dashboard/complaints?kpi=...). Same definitions as the
 * tile counts (AnalyticsAggregationService#computeKpiTiles):
 * TOTAL = every complaint, OPEN = VERIFIED / ASSIGNED / IN_PROGRESS,
 * RESOLVED = RESOLVED / CLOSED, ESCALATED = SLA breached (is_escalated).
 */
public enum DashboardKpi {
    TOTAL, OPEN, RESOLVED, ESCALATED
}
