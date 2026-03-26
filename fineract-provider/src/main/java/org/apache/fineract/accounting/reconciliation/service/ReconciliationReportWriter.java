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
package org.apache.fineract.accounting.reconciliation.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.reconciliation.data.LoanGLDiscrepancy;
import org.apache.fineract.accounting.reconciliation.data.ReconciliationReport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Writes the reconciliation report to disk (CSV + summary TXT).
 * Never modifies any database records.
 */
@Component
@Slf4j
public class ReconciliationReportWriter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${fiter.reconciliation.output-dir:/tmp/fineract-reconciliation}")
    private String outputDir;

    /**
     * Write the report as:
     * - {outputDir}/{date}_reconciliation_summary.txt  — human-readable summary
     * - {outputDir}/{date}_reconciliation_details.csv  — full CSV for spreadsheets
     */
    public void write(final ReconciliationReport report) {
        try {
            final Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);

            final String dateStr = report.getBusinessDate().format(DATE_FMT);
            writeSummary(report, dir.resolve(dateStr + "_reconciliation_summary.txt"));
            writeCsv(report, dir.resolve(dateStr + "_reconciliation_details.csv"));

        } catch (IOException e) {
            log.error("[Reconciliation] Failed to write report: {}", e.getMessage(), e);
        }
    }

    private void writeSummary(final ReconciliationReport report, final Path path) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            pw.println("=".repeat(70));
            pw.println("  FINERACT GL RECONCILIATION REPORT");
            pw.println("  Fiter — Nightly Accounting Integrity Check");
            pw.println("=".repeat(70));
            pw.printf("  Business Date  : %s%n", report.getBusinessDate());
            pw.printf("  Generated At   : %s%n", report.getGeneratedAt().format(DATETIME_FMT));
            pw.println("-".repeat(70));
            pw.printf("  Loans Scanned  : %,d%n", report.getTotalLoansScanned());
            pw.printf("  With Issues    : %,d%n", report.getLoansWithDiscrepancies());
            pw.printf("  Discrepancies  : %,d%n", report.getTotalDiscrepancies());
            pw.printf("  Total Delta    : %,.2f%n", report.totalDelta());
            pw.printf("  Critical       : %s%n", report.hasCriticalDiscrepancies() ? "⚠️  YES" : "✅ NO");
            pw.println("-".repeat(70));
            pw.println("  BY TYPE:");
            for (Map.Entry<LoanGLDiscrepancy.DiscrepancyType, Long> e : report.discrepanciesByType().entrySet()) {
                pw.printf("    %-30s : %,d%n", e.getKey(), e.getValue());
            }
            pw.println("=".repeat(70));

            if (!report.getDiscrepancies().isEmpty()) {
                pw.println();
                pw.println("  TOP 20 DISCREPANCIES (by delta):");
                pw.println("-".repeat(70));

                report.getDiscrepancies().stream()
                        .sorted((a, b) -> b.getDelta().abs().compareTo(a.getDelta().abs()))
                        .limit(20)
                        .forEach(d -> {
                            pw.printf("  LoanID: %-8d  Account: %-12s  Type: %-25s  Delta: %,.2f%n",
                                    d.getLoanId(), d.getAccountNumber(), d.getDiscrepancyType(),
                                    d.getDelta() != null ? d.getDelta() : BigDecimal.ZERO);
                            pw.printf("    Client: %s | Product: %s%n", d.getClientName(), d.getProductName());
                            pw.printf("    Notes : %s%n", d.getNotes());
                            pw.println();
                        });
            }
        }
        log.info("[Reconciliation] Summary written to: {}", path);
    }

    private void writeCsv(final ReconciliationReport report, final Path path) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {

            pw.println("loan_id,account_number,client_name,product_name,loan_status," +
                       "discrepancy_type,expected_amount,posted_amount,delta," +
                       "gl_account_code,gl_account_name,detected_on,notes");

            final List<LoanGLDiscrepancy> discrepancies = report.getDiscrepancies();
            if (discrepancies != null) {
                for (LoanGLDiscrepancy d : discrepancies) {
                    pw.printf("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,\"%s\"%n",
                            d.getLoanId(),
                            csvEscape(d.getAccountNumber()),
                            csvEscape(d.getClientName()),
                            csvEscape(d.getProductName()),
                            csvEscape(d.getLoanStatus()),
                            d.getDiscrepancyType(),
                            d.getExpectedAmount() != null ? d.getExpectedAmount().toPlainString() : "",
                            d.getPostedAmount() != null ? d.getPostedAmount().toPlainString() : "",
                            d.getDelta() != null ? d.getDelta().toPlainString() : "",
                            csvEscape(d.getGlAccountCode()),
                            csvEscape(d.getGlAccountName()),
                            d.getDetectedOn(),
                            csvEscape(d.getNotes()));
                }
            }
        }
        log.info("[Reconciliation] CSV details written to: {}", path);
    }

    private String csvEscape(final String value) {
        if (value == null) return "";
        return value.replace("\"", "\"\"");
    }
}
