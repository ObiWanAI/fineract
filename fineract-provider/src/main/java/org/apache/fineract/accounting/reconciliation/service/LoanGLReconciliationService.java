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

import java.time.LocalDate;
import org.apache.fineract.accounting.reconciliation.data.ReconciliationReport;

/**
 * Contract for the nightly GL reconciliation service.
 *
 * <p>All operations are read-only. This service NEVER writes to the database.
 * It compares expected balances from m_loan/m_loan_repayment_schedule against
 * actual postings in acc_gl_journal_entry and surfaces discrepancies.
 */
public interface LoanGLReconciliationService {

    /**
     * Run a full reconciliation for the given business date.
     *
     * @param businessDate the date to reconcile against (usually yesterday for nightly job)
     * @return a {@link ReconciliationReport} with all detected discrepancies
     */
    ReconciliationReport runReconciliation(LocalDate businessDate);

    /**
     * Reconcile a single loan (useful for on-demand checks or re-processing).
     *
     * @param loanId       the loan to reconcile
     * @param businessDate the reference date
     * @return a {@link ReconciliationReport} scoped to one loan
     */
    ReconciliationReport runReconciliationForLoan(Long loanId, LocalDate businessDate);
}
