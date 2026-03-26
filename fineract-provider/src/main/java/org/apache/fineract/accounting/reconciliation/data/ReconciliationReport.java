/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.fineract.accounting.reconciliation.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;

/**
 * Aggregated reconciliation report produced by the nightly job.
 * Read-only — never triggers any write to the database.
 */
@Data
@Builder
public class ReconciliationReport {

    private LocalDate businessDate;
    private LocalDateTime generatedAt;
    private int totalLoansScanned;
    private int loansWithDiscrepancies;
    private int totalDiscrepancies;
    private List<LoanGLDiscrepancy> discrepancies;

    /**
     * Convenience: group discrepancies by type for summary output.
     */
    public Map<LoanGLDiscrepancy.DiscrepancyType, Long> discrepanciesByType() {
        if (discrepancies == null) return Map.of();
        return discrepancies.stream()
                .collect(Collectors.groupingBy(LoanGLDiscrepancy::getDiscrepancyType, Collectors.counting()));
    }

    /**
     * Total absolute delta across all discrepancies.
     */
    public BigDecimal totalDelta() {
        if (discrepancies == null) return BigDecimal.ZERO;
        return discrepancies.stream()
                .map(LoanGLDiscrepancy::getDelta)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .abs();
    }

    /**
     * Returns true if there are any critical discrepancies (unbalanced or duplicate).
     */
    public boolean hasCriticalDiscrepancies() {
        if (discrepancies == null) return false;
        return discrepancies.stream().anyMatch(d ->
                d.getDiscrepancyType() == LoanGLDiscrepancy.DiscrepancyType.UNBALANCED_JOURNAL
                || d.getDiscrepancyType() == LoanGLDiscrepancy.DiscrepancyType.DUPLICATE_ACCRUAL);
    }
}
