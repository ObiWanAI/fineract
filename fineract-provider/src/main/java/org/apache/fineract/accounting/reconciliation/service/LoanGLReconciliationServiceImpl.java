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

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.accounting.reconciliation.data.LoanGLDiscrepancy;
import org.apache.fineract.accounting.reconciliation.data.LoanGLDiscrepancy.DiscrepancyType;
import org.apache.fineract.accounting.reconciliation.data.ReconciliationReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link LoanGLReconciliationService}.
 *
 * <p><b>NON-DESTRUCTIVE</b>: All queries are read-only SELECT statements.
 * The @Transactional annotation uses readOnly=true to enforce this at DB level.
 * Uses PROPAGATION_SUPPORTS so it never forces a new write transaction.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoanGLReconciliationServiceImpl implements LoanGLReconciliationService {

    private final JdbcTemplate jdbcTemplate;

    // ─── Constants ──────────────────────────────────────────────────────────

    /** Threshold below which a delta is considered a rounding difference (not a real discrepancy) */
    private static final BigDecimal DELTA_THRESHOLD = new BigDecimal("0.01");

    // ─── Loan status codes in m_loan ────────────────────────────────────────
    private static final int STATUS_ACTIVE = 300;
    private static final int STATUS_OVERPAID = 400;

    // ─── GL entry types in acc_gl_journal_entry ─────────────────────────────
    private static final int ENTRY_TYPE_DEBIT = 1;
    private static final int ENTRY_TYPE_CREDIT = 2;

    // ─── Loan transaction types in m_loan_transaction ───────────────────────
    private static final int TXN_ACCRUAL = 10;
    private static final int TXN_ACCRUAL_ADJUSTMENT = 25;

    // ═══════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public ReconciliationReport runReconciliation(final LocalDate businessDate) {
        log.info("[Reconciliation] Starting nightly GL reconciliation for business date: {}", businessDate);

        final List<LoanGLDiscrepancy> allDiscrepancies = new ArrayList<>();
        final int totalLoans = countActiveLoans();
        log.info("[Reconciliation] Total active/overpaid loans to scan: {}", totalLoans);

        // Run each check and accumulate
        allDiscrepancies.addAll(findAccrualMismatches(businessDate));
        allDiscrepancies.addAll(findUnbalancedJournals(businessDate));
        allDiscrepancies.addAll(findDuplicateAccruals(businessDate));
        allDiscrepancies.addAll(findMissingGLEntries(businessDate));
        allDiscrepancies.addAll(findOrphanGLEntries(businessDate));
        allDiscrepancies.addAll(findPrincipalMismatches(businessDate));

        final long distinctLoans = allDiscrepancies.stream()
                .map(LoanGLDiscrepancy::getLoanId)
                .distinct()
                .count();

        log.info("[Reconciliation] Completed. Total discrepancies: {} across {} loans",
                allDiscrepancies.size(), distinctLoans);

        return ReconciliationReport.builder()
                .businessDate(businessDate)
                .generatedAt(LocalDateTime.now())
                .totalLoansScanned(totalLoans)
                .loansWithDiscrepancies((int) distinctLoans)
                .totalDiscrepancies(allDiscrepancies.size())
                .discrepancies(allDiscrepancies)
                .build();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public ReconciliationReport runReconciliationForLoan(final Long loanId, final LocalDate businessDate) {
        log.info("[Reconciliation] Running single-loan reconciliation: loanId={}, date={}", loanId, businessDate);

        final List<LoanGLDiscrepancy> discrepancies = new ArrayList<>();
        discrepancies.addAll(findAccrualMismatchesForLoan(loanId, businessDate));
        discrepancies.addAll(findUnbalancedJournalsForLoan(loanId, businessDate));
        discrepancies.addAll(findDuplicateAccrualsForLoan(loanId, businessDate));

        return ReconciliationReport.builder()
                .businessDate(businessDate)
                .generatedAt(LocalDateTime.now())
                .totalLoansScanned(1)
                .loansWithDiscrepancies(discrepancies.isEmpty() ? 0 : 1)
                .totalDiscrepancies(discrepancies.size())
                .discrepancies(discrepancies)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Check 1: Accrual mismatches
    //   m_loan_repayment_schedule.interest_writtenoff / accrued_interest
    //   vs SUM of accrual entries in acc_gl_journal_entry
    // ═══════════════════════════════════════════════════════════════════════

    private List<LoanGLDiscrepancy> findAccrualMismatches(final LocalDate tillDate) {
        log.debug("[Reconciliation] Running accrual mismatch check...");

        // This query compares:
        //   - total accrued interest tracked in m_loan_repayment_schedule
        //   - vs total CREDIT entries posted to Interest Income GL accounts
        //     for accrual transactions (transaction_type_enum = 10)
        final String sql = """
            SELECT
                l.id                            AS loan_id,
                l.account_no                    AS account_number,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name                         AS product_name,
                l.loan_status_id                AS loan_status,
                COALESCE(SUM(lrs.interest_amount), 0)
                    - COALESCE(SUM(lrs.interest_waived_derived), 0) AS expected_accrual,
                COALESCE(gl_accruals.posted_accrual, 0)            AS posted_accrual,
                COALESCE(SUM(lrs.interest_amount), 0)
                    - COALESCE(SUM(lrs.interest_waived_derived), 0)
                    - COALESCE(gl_accruals.posted_accrual, 0)       AS delta,
                gl_accruals.gl_code,
                gl_accruals.gl_name
            FROM m_loan l
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            JOIN m_loan_repayment_schedule lrs ON lrs.loan_id = l.id
                AND lrs.duedate <= ?
                AND lrs.completed_derived = 0
            LEFT JOIN (
                SELECT
                    je.loan_transaction_id,
                    lt.loan_id,
                    SUM(CASE WHEN je.type_enum = 2 THEN je.amount ELSE -je.amount END) AS posted_accrual,
                    gla.gl_code,
                    gla.name AS gl_name
                FROM acc_gl_journal_entry je
                JOIN m_loan_transaction lt ON lt.id = je.loan_transaction_id
                JOIN acc_gl_account gla ON gla.id = je.account_id
                WHERE lt.transaction_type_enum IN (10, 25)   -- ACCRUAL, ACCRUAL_ADJUSTMENT
                  AND je.reversed = 0
                  AND je.entry_date <= ?
                GROUP BY lt.loan_id, gla.gl_code, gla.name
            ) gl_accruals ON gl_accruals.loan_id = l.id
            WHERE l.loan_status_id IN (300, 400)             -- ACTIVE, OVERPAID
            GROUP BY l.id, l.account_no, client_name, product_name, l.loan_status_id,
                     gl_accruals.posted_accrual, gl_accruals.gl_code, gl_accruals.gl_name
            HAVING ABS(delta) > 0.01
            ORDER BY ABS(delta) DESC
            """;

        return jdbcTemplate.query(sql,
                new AccrualMismatchRowMapper(tillDate),
                tillDate, tillDate);
    }

    private List<LoanGLDiscrepancy> findAccrualMismatchesForLoan(final Long loanId, final LocalDate tillDate) {
        final String sql = """
            SELECT
                l.id, l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name, l.loan_status_id,
                COALESCE(SUM(lrs.interest_amount), 0)
                    - COALESCE(SUM(lrs.interest_waived_derived), 0) AS expected_accrual,
                COALESCE(gl_accruals.posted_accrual, 0) AS posted_accrual,
                COALESCE(SUM(lrs.interest_amount), 0)
                    - COALESCE(SUM(lrs.interest_waived_derived), 0)
                    - COALESCE(gl_accruals.posted_accrual, 0) AS delta,
                gl_accruals.gl_code,
                gl_accruals.gl_name
            FROM m_loan l
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            JOIN m_loan_repayment_schedule lrs ON lrs.loan_id = l.id
                AND lrs.duedate <= ? AND lrs.completed_derived = 0
            LEFT JOIN (
                SELECT lt.loan_id,
                    SUM(CASE WHEN je.type_enum = 2 THEN je.amount ELSE -je.amount END) AS posted_accrual,
                    gla.gl_code, gla.name AS gl_name
                FROM acc_gl_journal_entry je
                JOIN m_loan_transaction lt ON lt.id = je.loan_transaction_id
                JOIN acc_gl_account gla ON gla.id = je.account_id
                WHERE lt.transaction_type_enum IN (10, 25)
                  AND je.reversed = 0 AND je.entry_date <= ?
                  AND lt.loan_id = ?
                GROUP BY lt.loan_id, gla.gl_code, gla.name
            ) gl_accruals ON gl_accruals.loan_id = l.id
            WHERE l.id = ?
            GROUP BY l.id, l.account_no, client_name, lp.name, l.loan_status_id,
                     gl_accruals.posted_accrual, gl_accruals.gl_code, gl_accruals.gl_name
            HAVING ABS(delta) > 0.01
            """;

        return jdbcTemplate.query(sql,
                new AccrualMismatchRowMapper(tillDate),
                tillDate, tillDate, loanId, loanId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Check 2: Unbalanced journal entries (debits ≠ credits per loan per day)
    // ═══════════════════════════════════════════════════════════════════════

    private List<LoanGLDiscrepancy> findUnbalancedJournals(final LocalDate businessDate) {
        log.debug("[Reconciliation] Running unbalanced journal check...");

        final String sql = """
            SELECT
                l.id AS loan_id,
                l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name AS product_name,
                l.loan_status_id,
                SUM(CASE WHEN je.type_enum = 1 THEN je.amount ELSE 0 END) AS total_debits,
                SUM(CASE WHEN je.type_enum = 2 THEN je.amount ELSE 0 END) AS total_credits,
                SUM(CASE WHEN je.type_enum = 1 THEN je.amount ELSE 0 END)
                    - SUM(CASE WHEN je.type_enum = 2 THEN je.amount ELSE 0 END) AS delta,
                NULL AS gl_code, NULL AS gl_name
            FROM acc_gl_journal_entry je
            JOIN m_loan_transaction lt ON lt.id = je.loan_transaction_id
            JOIN m_loan l ON l.id = lt.loan_id
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            WHERE je.reversed = 0
              AND je.entry_date = ?
            GROUP BY l.id, l.account_no, client_name, lp.name, l.loan_status_id
            HAVING ABS(delta) > 0.01
            ORDER BY ABS(delta) DESC
            """;

        final List<LoanGLDiscrepancy> results = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            results.add(LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_no"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status_id")))
                    .discrepancyType(DiscrepancyType.UNBALANCED_JOURNAL)
                    .expectedAmount(rs.getBigDecimal("total_debits"))
                    .postedAmount(rs.getBigDecimal("total_credits"))
                    .delta(rs.getBigDecimal("delta"))
                    .detectedOn(businessDate)
                    .notes("Journal debits ≠ credits for entry_date=" + businessDate)
                    .build());
        }, businessDate);

        return results;
    }

    private List<LoanGLDiscrepancy> findUnbalancedJournalsForLoan(final Long loanId, final LocalDate businessDate) {
        final String sql = """
            SELECT
                l.id AS loan_id, l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name AS product_name, l.loan_status_id,
                SUM(CASE WHEN je.type_enum = 1 THEN je.amount ELSE 0 END) AS total_debits,
                SUM(CASE WHEN je.type_enum = 2 THEN je.amount ELSE 0 END) AS total_credits,
                SUM(CASE WHEN je.type_enum = 1 THEN je.amount ELSE 0 END)
                    - SUM(CASE WHEN je.type_enum = 2 THEN je.amount ELSE 0 END) AS delta
            FROM acc_gl_journal_entry je
            JOIN m_loan_transaction lt ON lt.id = je.loan_transaction_id
            JOIN m_loan l ON l.id = lt.loan_id
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            WHERE je.reversed = 0 AND je.entry_date = ? AND l.id = ?
            GROUP BY l.id, l.account_no, client_name, lp.name, l.loan_status_id
            HAVING ABS(delta) > 0.01
            """;

        final List<LoanGLDiscrepancy> results = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            results.add(LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_no"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status_id")))
                    .discrepancyType(DiscrepancyType.UNBALANCED_JOURNAL)
                    .expectedAmount(rs.getBigDecimal("total_debits"))
                    .postedAmount(rs.getBigDecimal("total_credits"))
                    .delta(rs.getBigDecimal("delta"))
                    .detectedOn(businessDate)
                    .notes("Journal debits ≠ credits for entry_date=" + businessDate)
                    .build());
        }, businessDate, loanId);

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Check 3: Duplicate accruals (same loan, same date, multiple non-reversed
    //          accrual transactions) — root cause of Fix 2.1
    // ═══════════════════════════════════════════════════════════════════════

    private List<LoanGLDiscrepancy> findDuplicateAccruals(final LocalDate businessDate) {
        log.debug("[Reconciliation] Running duplicate accrual check...");

        final String sql = """
            SELECT
                l.id AS loan_id,
                l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name AS product_name,
                l.loan_status_id,
                lt.transaction_date,
                COUNT(lt.id) AS accrual_count,
                SUM(lt.interest_portion_derived) AS total_interest_posted,
                SUM(lt.fee_charges_portion_derived) AS total_fee_posted
            FROM m_loan_transaction lt
            JOIN m_loan l ON l.id = lt.loan_id
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            WHERE lt.transaction_type_enum IN (10, 25)   -- ACCRUAL, ACCRUAL_ADJUSTMENT
              AND lt.is_reversed = 0
              AND lt.transaction_date = ?
            GROUP BY l.id, l.account_no, client_name, lp.name, l.loan_status_id, lt.transaction_date
            HAVING COUNT(lt.id) > 1
            ORDER BY accrual_count DESC
            """;

        final List<LoanGLDiscrepancy> results = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            final int count = rs.getInt("accrual_count");
            final BigDecimal totalInterest = rs.getBigDecimal("total_interest_posted");
            results.add(LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_no"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status_id")))
                    .discrepancyType(DiscrepancyType.DUPLICATE_ACCRUAL)
                    .expectedAmount(BigDecimal.ONE)
                    .postedAmount(BigDecimal.valueOf(count))
                    .delta(totalInterest)
                    .detectedOn(businessDate)
                    .notes(String.format(
                            "DUPLICATE ACCRUAL: %d non-reversed accrual transactions on %s. " +
                            "Total interest posted: %s. Likely caused by concurrent COB step " +
                            "AND legacy AddPeriodicAccrualEntriesTasklet running simultaneously.",
                            count, rs.getDate("transaction_date"), totalInterest))
                    .build());
        }, businessDate);

        return results;
    }

    private List<LoanGLDiscrepancy> findDuplicateAccrualsForLoan(final Long loanId, final LocalDate businessDate) {
        final String sql = """
            SELECT
                l.id AS loan_id, l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name AS product_name, l.loan_status_id,
                lt.transaction_date, COUNT(lt.id) AS accrual_count,
                SUM(lt.interest_portion_derived) AS total_interest_posted,
                SUM(lt.fee_charges_portion_derived) AS total_fee_posted
            FROM m_loan_transaction lt
            JOIN m_loan l ON l.id = lt.loan_id
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            WHERE lt.transaction_type_enum IN (10, 25)
              AND lt.is_reversed = 0
              AND lt.transaction_date = ?
              AND l.id = ?
            GROUP BY l.id, l.account_no, client_name, lp.name, l.loan_status_id, lt.transaction_date
            HAVING COUNT(lt.id) > 1
            """;

        final List<LoanGLDiscrepancy> results = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            results.add(LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_no"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status_id")))
                    .discrepancyType(DiscrepancyType.DUPLICATE_ACCRUAL)
                    .detectedOn(businessDate)
                    .notes("Duplicate accrual for loanId=" + loanId + " on " + businessDate)
                    .build());
        }, businessDate, loanId);

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Check 4: Loan transactions with NO corresponding GL entries
    // ═══════════════════════════════════════════════════════════════════════

    private List<LoanGLDiscrepancy> findMissingGLEntries(final LocalDate businessDate) {
        log.debug("[Reconciliation] Running missing GL entries check...");

        final String sql = """
            SELECT
                l.id AS loan_id,
                l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name AS product_name,
                l.loan_status_id,
                lt.amount AS expected_amount,
                lt.transaction_type_enum
            FROM m_loan_transaction lt
            JOIN m_loan l ON l.id = lt.loan_id
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            WHERE lt.is_reversed = 0
              AND lt.transaction_date = ?
              AND lt.transaction_type_enum NOT IN (
                  -- Exclude types that don't generate GL entries
                  2,   -- APPROVE
                  12,  -- WAIVE_CHARGES
                  15,  -- WRITE_OFF (may have GL or not depending on config)
                  20,  -- UNDO_DISBURSAL
                  21   -- REVERSE_TRANSFER
              )
              AND NOT EXISTS (
                  SELECT 1 FROM acc_gl_journal_entry je
                  WHERE je.loan_transaction_id = lt.id
                    AND je.reversed = 0
              )
            ORDER BY l.id
            """;

        final List<LoanGLDiscrepancy> results = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            results.add(LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_no"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status_id")))
                    .discrepancyType(DiscrepancyType.MISSING_GL_ENTRY)
                    .expectedAmount(rs.getBigDecimal("expected_amount"))
                    .postedAmount(BigDecimal.ZERO)
                    .delta(rs.getBigDecimal("expected_amount"))
                    .detectedOn(businessDate)
                    .notes("Loan transaction (type=" + rs.getInt("transaction_type_enum")
                            + ") on " + businessDate + " has no GL entries.")
                    .build());
        }, businessDate);

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Check 5: GL entries with NO corresponding loan transaction (orphans)
    // ═══════════════════════════════════════════════════════════════════════

    private List<LoanGLDiscrepancy> findOrphanGLEntries(final LocalDate businessDate) {
        log.debug("[Reconciliation] Running orphan GL entries check...");

        final String sql = """
            SELECT
                l.id AS loan_id,
                l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name AS product_name,
                l.loan_status_id,
                je.amount,
                gla.gl_code, gla.name AS gl_name
            FROM acc_gl_journal_entry je
            JOIN m_loan l ON l.id = je.entity_id AND je.entity_type_enum = 2  -- LOAN
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            JOIN acc_gl_account gla ON gla.id = je.account_id
            WHERE je.reversed = 0
              AND je.entry_date = ?
              AND je.loan_transaction_id IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM m_loan_transaction lt
                  WHERE lt.id = je.loan_transaction_id
                    AND lt.is_reversed = 0
              )
            ORDER BY l.id
            """;

        final List<LoanGLDiscrepancy> results = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            results.add(LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_no"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status_id")))
                    .discrepancyType(DiscrepancyType.ORPHAN_GL_ENTRY)
                    .expectedAmount(BigDecimal.ZERO)
                    .postedAmount(rs.getBigDecimal("amount"))
                    .delta(rs.getBigDecimal("amount"))
                    .glAccountCode(rs.getString("gl_code"))
                    .glAccountName(rs.getString("gl_name"))
                    .detectedOn(businessDate)
                    .notes("GL entry exists in acc_gl_journal_entry but linked loan_transaction is reversed or missing.")
                    .build());
        }, businessDate);

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Check 6: Principal balance mismatch
    //   m_loan.principal_outstanding_derived vs GL principal account balance
    // ═══════════════════════════════════════════════════════════════════════

    private List<LoanGLDiscrepancy> findPrincipalMismatches(final LocalDate businessDate) {
        log.debug("[Reconciliation] Running principal balance mismatch check...");

        // Compare principal_outstanding in m_loan vs net GL movement on
        // Loans Receivable (asset) account. This is a simplified check;
        // production should join acc_product_mapping for exact GL code.
        final String sql = """
            SELECT
                l.id AS loan_id,
                l.account_no,
                CONCAT(c.firstname, ' ', c.lastname) AS client_name,
                lp.name AS product_name,
                l.loan_status_id,
                l.principal_outstanding_derived AS expected_amount,
                COALESCE(gl_principal.net_principal, 0) AS posted_amount,
                l.principal_outstanding_derived - COALESCE(gl_principal.net_principal, 0) AS delta,
                gl_principal.gl_code,
                gl_principal.gl_name
            FROM m_loan l
            JOIN m_client c ON c.id = l.client_id
            JOIN m_product_loan lp ON lp.id = l.loan_product_id
            LEFT JOIN (
                SELECT
                    je.entity_id AS loan_id,
                    SUM(CASE WHEN je.type_enum = 1 THEN je.amount ELSE -je.amount END) AS net_principal,
                    gla.gl_code,
                    gla.name AS gl_name
                FROM acc_gl_journal_entry je
                JOIN acc_gl_account gla ON gla.id = je.account_id
                WHERE je.reversed = 0
                  AND je.entity_type_enum = 2      -- LOAN
                  AND gla.classification_enum = 1  -- ASSET (Loans Receivable)
                  AND je.entry_date <= ?
                GROUP BY je.entity_id, gla.gl_code, gla.name
            ) gl_principal ON gl_principal.loan_id = l.id
            WHERE l.loan_status_id IN (300, 400)
              AND ABS(l.principal_outstanding_derived - COALESCE(gl_principal.net_principal, 0)) > 0.01
            ORDER BY ABS(delta) DESC
            LIMIT 500
            """;

        final List<LoanGLDiscrepancy> results = new ArrayList<>();
        jdbcTemplate.query(sql, rs -> {
            results.add(LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_no"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status_id")))
                    .discrepancyType(DiscrepancyType.PRINCIPAL_MISMATCH)
                    .expectedAmount(rs.getBigDecimal("expected_amount"))
                    .postedAmount(rs.getBigDecimal("posted_amount"))
                    .delta(rs.getBigDecimal("delta"))
                    .glAccountCode(rs.getString("gl_code"))
                    .glAccountName(rs.getString("gl_name"))
                    .detectedOn(businessDate)
                    .notes("Principal outstanding in m_loan does not match net GL asset movement.")
                    .build());
        }, businessDate);

        return results;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private int countActiveLoans() {
        final String sql = "SELECT COUNT(*) FROM m_loan WHERE loan_status_id IN (300, 400)";
        final Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    private static String loanStatusLabel(final int statusId) {
        return switch (statusId) {
            case 100 -> "SUBMITTED";
            case 200 -> "APPROVED";
            case 300 -> "ACTIVE";
            case 400 -> "OVERPAID";
            case 500 -> "CLOSED";
            case 601 -> "WRITTEN_OFF";
            case 602 -> "RESCHEDULED";
            case 700 -> "WITHDRAWN";
            default  -> "UNKNOWN(" + statusId + ")";
        };
    }

    // ─── Row Mappers ──────────────────────────────────────────────────────

    private static class AccrualMismatchRowMapper implements RowMapper<LoanGLDiscrepancy> {
        private final LocalDate detectedOn;
        AccrualMismatchRowMapper(LocalDate detectedOn) { this.detectedOn = detectedOn; }

        @Override
        public LoanGLDiscrepancy mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            return LoanGLDiscrepancy.builder()
                    .loanId(rs.getLong("loan_id"))
                    .accountNumber(rs.getString("account_number"))
                    .clientName(rs.getString("client_name"))
                    .productName(rs.getString("product_name"))
                    .loanStatus(loanStatusLabel(rs.getInt("loan_status")))
                    .discrepancyType(DiscrepancyType.ACCRUAL_MISMATCH)
                    .expectedAmount(rs.getBigDecimal("expected_accrual"))
                    .postedAmount(rs.getBigDecimal("posted_accrual"))
                    .delta(rs.getBigDecimal("delta"))
                    .glAccountCode(rs.getString("gl_code"))
                    .glAccountName(rs.getString("gl_name"))
                    .detectedOn(detectedOn)
                    .notes("Interest accrued per schedule ≠ accrual GL entries posted.")
                    .build();
        }
    }
}
