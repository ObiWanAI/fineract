# Fix 2.1 — Interest/Accrual Posting Twice: Root Cause & Patch

## TL;DR

**Root cause**: Two separate execution paths can run `addPeriodicAccruals()` on the same loan on the same business date:
1. The **COB (Close of Business) pipeline** — `AddPeriodicAccrualEntriesBusinessStep` (per-loan)
2. The **legacy batch job** — `AddPeriodicAccrualEntriesTasklet` (all-loans sweep)

When both are active simultaneously, non-progressive-accrual loans skip the deduplication guard and receive double accrual entries.

---

## Affected Classes

| File | Role |
|------|------|
| `fineract-provider/.../cob/loan/AddPeriodicAccrualEntriesBusinessStep.java` | COB step — runs per loan during nightly COB |
| `fineract-loan/.../jobs/addperiodicaccrualentries/AddPeriodicAccrualEntriesTasklet.java` | Legacy batch job — runs for ALL loans |
| `fineract-provider/.../service/LoanAccrualsProcessingServiceImpl.java` | Core logic — `addAccruals()` method |

---

## Root Cause Analysis

### The deduplication guard (LoanAccrualsProcessingServiceImpl.java, line ~353)

```java
if (progressiveAccrual && accruedTill != null && !DateUtils.isAfter(tillDate, accruedTill)) {
    if (isFinal) {
        reverseTransactionsAfter(loan, ACCRUAL_TYPES, accrualDate, addJournal);
    } else if (loanTransactionRepository.existsNonReversedByLoanAndTypesAndOnOrAfterDate(loan, ACCRUAL_TYPES, accrualDate)
            && hasNoActiveChargeOnDate(loan, accrualDate)) {
        return;  // ← Only fires for progressiveAccrual = true
    }
}
```

**The guard only applies when `progressiveAccrual = true`**.

For **non-progressive (legacy) accrual** loans:
- `progressiveAccrual = false`
- The guard is **skipped entirely**
- If both COB step AND the batch Tasklet run → `accrueTransaction()` is called twice
- Two `m_loan_transaction` records with `transaction_type_enum = 10 (ACCRUAL)` on the same date
- Two sets of `acc_gl_journal_entry` rows → **double posting**

### Trigger conditions

The bug manifests when:
1. COB is enabled AND the `ADD_PERIODIC_ACCRUAL_ENTRIES` business step is active
2. AND the legacy `Add Periodic Accrual Entries` Spring Batch job is also scheduled
3. AND the loan uses non-progressive (CUMULATIVE) accrual accounting

This commonly happens after Fineract upgrades where COB was introduced but the old batch job was not disabled.

---

## Proposed Fix

### Option A (RECOMMENDED): Disable the legacy batch job

The cleanest fix. If COB is handling accruals, the legacy Tasklet is redundant.

**In Fineract scheduler (c_job / c_scheduler_detail):**
```sql
-- Disable the legacy periodic accrual job
UPDATE c_job
SET currently_running = 0, enabled = 0
WHERE name = 'Add Periodic Accrual Entries';
```

**Or via API:**
```
PUT /fineract-provider/api/v1/scheduler/jobs/{jobId}
{ "active": false }
```

### Option B: Add deduplication guard for non-progressive loans

Patch `LoanAccrualsProcessingServiceImpl.addAccruals()` to also guard non-progressive loans:

```java
// PATCH: extend guard to non-progressive loans (Fix 2.1)
// Location: LoanAccrualsProcessingServiceImpl.java, after line ~352

final LocalDate accruedTill = loan.getAccruedTill();

// NEW: For non-progressive accrual, check if already accrued today
if (!progressiveAccrual && !isFinal && accruedTill != null
        && !DateUtils.isAfter(tillDate, accruedTill)) {
    // Already ran today — skip to prevent double-posting
    if (loanTransactionRepository.existsNonReversedByLoanAndTypesAndOnOrAfterDate(
            loan, ACCRUAL_TYPES, tillDate)) {
        log.debug("[Accrual] Skipping loan {} — already accrued for date {} (non-progressive guard)",
                loan.getId(), tillDate);
        return;
    }
}
```

**Full patched block (replace lines 346–360 in `LoanAccrualsProcessingServiceImpl.java`):**

```java
final boolean progressiveAccrual = isProgressiveAccrual(loan);
final LocalDate accruedTill = loan.getAccruedTill();
final LocalDate businessDate = DateUtils.getBusinessLocalDate();
final LocalDate accrualDate = isFinal
        ? (progressiveAccrual ? (DateUtils.isBefore(lastDueDate, businessDate) ? lastDueDate : businessDate)
                : getFinalAccrualTransactionDate(loan))
        : tillDate;

// ── Deduplication guard ──────────────────────────────────────────
// Prevents double-posting when both COB step and legacy batch job
// execute on the same business date (Fix 2.1 — Fiter patch).
if (accruedTill != null && !DateUtils.isAfter(tillDate, accruedTill)) {
    if (isFinal) {
        reverseTransactionsAfter(loan, ACCRUAL_TYPES, accrualDate, addJournal);
    } else if (loanTransactionRepository.existsNonReversedByLoanAndTypesAndOnOrAfterDate(
                       loan, ACCRUAL_TYPES, accrualDate)
               && hasNoActiveChargeOnDate(loan, accrualDate)) {
        // ↑ Guard now covers BOTH progressive AND non-progressive loans
        log.debug("[Accrual] Skip loan {} — already accrued for {} (progressiveAccrual={})",
                loan.getId(), accrualDate, progressiveAccrual);
        return;
    }
}
// ────────────────────────────────────────────────────────────────
```

---

## Testing

### Reproduce the bug
```sql
-- Check for duplicate accruals on any date
SELECT loan_id, transaction_date, COUNT(*) AS cnt,
       SUM(interest_portion_derived) AS total_interest
FROM m_loan_transaction
WHERE transaction_type_enum = 10   -- ACCRUAL
  AND is_reversed = 0
GROUP BY loan_id, transaction_date
HAVING COUNT(*) > 1
ORDER BY cnt DESC;
```

### Validate the fix
After applying Fix Option B or disabling the legacy job:
```sql
-- Should return 0 rows after fix
SELECT loan_id, transaction_date, COUNT(*) AS duplicates
FROM m_loan_transaction
WHERE transaction_type_enum IN (10, 25)
  AND is_reversed = 0
  AND transaction_date >= CURDATE() - INTERVAL 7 DAY
GROUP BY loan_id, transaction_date
HAVING COUNT(*) > 1;
```

---

## Risk Assessment

| Option | Risk | Effort | Recommendation |
|--------|------|--------|----------------|
| A: Disable legacy job | Low — COB already handles it | Minutes | ✅ Do first |
| B: Code patch | Medium — needs UAT | 1–2 days | ✅ Do after A to be safe |

**Recommended approach**: Apply Option A immediately, validate with the reconciliation job (Fix 2.5), then apply Option B as a defense-in-depth measure.

---

## Impacted Clients

All clients running Fineract with:
- COB enabled (`ADD_PERIODIC_ACCRUAL_ENTRIES` step active)
- Legacy accrual batch job still scheduled
- Non-progressive (CUMULATIVE) loan products

**Confirmed affected**: Oxygen/Access Bank NG, Tamara, Friendship Bridge, Entre Amigos.
