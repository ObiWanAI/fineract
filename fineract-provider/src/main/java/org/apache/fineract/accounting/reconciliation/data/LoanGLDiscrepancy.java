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
import lombok.Builder;
import lombok.Data;

/**
 * Represents a GL reconciliation discrepancy between m_loan balances
 * and acc_gl_journal_entry postings for a given loan.
 *
 * NON-DESTRUCTIVE: This class is purely a reporting DTO — no writes.
 */
@Data
@Builder
public class LoanGLDiscrepancy {

    /** Loan identifier */
    private Long loanId;

    /** External / display reference */
    private String accountNumber;

    /** Client name for readability in reports */
    private String clientName;

    /** Loan product name */
    private String productName;

    /** Current loan status (active, closed, etc.) */
    private String loanStatus;

    /** Type of discrepancy detected */
    private DiscrepancyType discrepancyType;

    /** Amount expected from m_loan ledger */
    private BigDecimal expectedAmount;

    /** Amount posted in acc_gl_journal_entry */
    private BigDecimal postedAmount;

    /** Absolute difference (posted - expected) */
    private BigDecimal delta;

    /** GL account code involved */
    private String glAccountCode;

    /** GL account name */
    private String glAccountName;

    /** Date of detection */
    private LocalDate detectedOn;

    /** Additional contextual notes */
    private String notes;

    public enum DiscrepancyType {
        /** Interest accrued in m_loan != accrual entries in GL */
        ACCRUAL_MISMATCH,
        /** Principal outstanding in m_loan != GL principal entries */
        PRINCIPAL_MISMATCH,
        /** Penalty/fee charges in m_loan != GL entries */
        CHARGE_MISMATCH,
        /** Journal entry exists but no corresponding loan transaction */
        ORPHAN_GL_ENTRY,
        /** Loan transaction exists but no GL entry was created */
        MISSING_GL_ENTRY,
        /** Debit != Credit on same loan (unbalanced) */
        UNBALANCED_JOURNAL,
        /** Double accrual detected (same date, same type, multiple entries) */
        DUPLICATE_ACCRUAL
    }
}
