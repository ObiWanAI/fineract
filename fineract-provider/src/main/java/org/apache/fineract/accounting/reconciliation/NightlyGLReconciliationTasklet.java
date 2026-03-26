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
package org.apache.fineract.accounting.reconciliation;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.reconciliation.data.ReconciliationReport;
import org.apache.fineract.accounting.reconciliation.service.LoanGLReconciliationService;
import org.apache.fineract.accounting.reconciliation.service.ReconciliationReportWriter;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * Spring Batch Tasklet: Nightly GL Reconciliation.
 *
 * <p>Registered as a job step via {@link NightlyGLReconciliationJobConfig}.
 * Runs after Close-of-Business to detect accounting discrepancies
 * between m_loan* tables and acc_gl_journal_entry.
 *
 * <p><b>Non-destructive</b>: No database writes. Outputs only to files.
 *
 * <p>Cron default: {@code 0 30 2 * * ?} (02:30 AM server time, after COB).
 * Override via {@code fiter.reconciliation.cron} in application.properties.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NightlyGLReconciliationTasklet implements Tasklet {

    private final LoanGLReconciliationService reconciliationService;
    private final ReconciliationReportWriter reportWriter;

    @Override
    public RepeatStatus execute(final StepContribution contribution,
                                final ChunkContext chunkContext) throws Exception {

        // Reconcile against yesterday's business date (COB already ran)
        final LocalDate businessDate = DateUtils.getBusinessLocalDate().minusDays(1);

        log.info("[Reconciliation] ===== NIGHTLY GL RECONCILIATION START — businessDate={} =====", businessDate);

        try {
            final ReconciliationReport report = reconciliationService.runReconciliation(businessDate);
            reportWriter.write(report);

            logSummary(report);

            if (report.hasCriticalDiscrepancies()) {
                log.error("[Reconciliation] ⚠️  CRITICAL discrepancies detected! " +
                          "Total={}, Delta={} — Review the CSV report immediately.",
                        report.getTotalDiscrepancies(), report.totalDelta());
                // NOTE: Add alerting here (email / Slack webhook) if desired.
                // Do NOT auto-correct. Manual review required.
            } else if (report.getTotalDiscrepancies() > 0) {
                log.warn("[Reconciliation] {} non-critical discrepancy(ies) found. Delta={}",
                        report.getTotalDiscrepancies(), report.totalDelta());
            } else {
                log.info("[Reconciliation] ✅ No discrepancies found. GL is balanced.");
            }

        } catch (Exception e) {
            log.error("[Reconciliation] Job failed with exception: {}", e.getMessage(), e);
            throw e; // Let Spring Batch record the step as FAILED
        }

        log.info("[Reconciliation] ===== NIGHTLY GL RECONCILIATION END =====");
        return RepeatStatus.FINISHED;
    }

    private void logSummary(final ReconciliationReport report) {
        log.info("[Reconciliation] Summary — Loans scanned: {} | With issues: {} | Total discrepancies: {} | Total delta: {}",
                report.getTotalLoansScanned(),
                report.getLoansWithDiscrepancies(),
                report.getTotalDiscrepancies(),
                report.totalDelta());

        report.discrepanciesByType().forEach((type, count) ->
                log.info("[Reconciliation]   {}: {}", type, count));
    }
}
