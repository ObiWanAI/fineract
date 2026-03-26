# Release Notes — Accounting Engine Fixes
**Product:** Fiter Core Banking Platform (powered by Apache Fineract)  
**Release:** v[X.X] — Accounting Stability Update  
**Release Date:** [TBD]  
**Audience:** CFO, Head of Finance, Accounting Team  

---

## What's in This Release

This release addresses eight issues identified in the accounting engine of your core banking platform. These fixes improve the accuracy of your financial records, reduce the need for manual reconciliation, and ensure your books reflect the true state of your loan portfolio at all times.

We recommend your accounting team review the changes below and validate your General Ledger balances after the update is applied.

---

## Fixes Summary

### 1. Interest Calculated Correctly at Loan Early Settlement
**Who is affected:** All clients — any loan closed before its original maturity date.

**What was happening:** When a borrower settled their loan early, the system was calculating interest based on the original repayment schedule rather than the actual number of days elapsed since the last payment. This could result in customers being overcharged interest they had not yet accrued.

**What we fixed:** The system now calculates interest on a precise pro-rata basis up to the exact date of early settlement. The amount charged reflects only the interest that has genuinely accrued — no more, no less. The loan's General Ledger accounts (principal, accrued interest) will always close to zero upon settlement.

**What to verify after the update:** Run your "Closed Loans" report and confirm that the interest charged on recently closed loans matches your expected per-day interest rate multiplied by the actual days since the last payment.

---

### 2. Interest Posted Only Once Per Period
**Who is affected:** All clients — core interest accrual function.

**What was happening:** In certain circumstances (such as a scheduled job running twice, or a system restart during processing), the interest accrual job could post the same interest twice for the same period. This inflated your interest income figures and overstated accrued interest receivable on your Balance Sheet.

**What we fixed:** The interest accrual process is now idempotent — running it multiple times for the same period produces the same result as running it once. The system checks whether interest has already been posted for a given period before proceeding. If it has, the job logs a warning and makes no changes to the ledger.

**What to verify after the update:** Review your Interest Income and Accrued Interest Receivable accounts for the past 30 days. You should see exactly one accrual entry per loan per day — no duplicates.

---

### 3. Bulk Payments Always Produce Balanced Journal Entries
**Who is affected:** All clients using bulk or batch payment processing.

**What was happening:** When processing large batches of payments, if any individual payment within the batch encountered an error, the system could record partial accounting entries — leaving your General Ledger out of balance (total debits ≠ total credits). This made month-end closing difficult and required manual intervention to locate and correct the imbalance.

**What we fixed:** Every payment in a bulk batch is now processed atomically: either it completes fully with a balanced journal entry, or it fails completely with no impact on the ledger. Failed payments are logged in a separate exceptions report for your team to review and reprocess. Additionally, any rounding differences (sub-cent amounts arising from interest calculations) are now automatically assigned to a designated Rounding Differences account rather than left unallocated.

**What to verify after the update:** After your next bulk payment run, check the post-run report. It should show: number of payments processed, number of exceptions, and a batch balance of $0.00 (debits equal credits). Your Suspense/Clearing account should show a zero balance after each run.

---

### 4. General Ledger Account Assignments Corrected
**Who is affected:** All clients — affects how transactions are categorized in your chart of accounts.

**What was happening:** In some scenarios — particularly after product configuration changes or when processing certain lifecycle events (such as loan modifications or write-offs) — transactions were being posted to incorrect General Ledger accounts. This caused misclassification of income and expenses and made reconciliation between your loan management system and your accounting records unreliable.

**What we fixed:** The system now strictly resolves GL account assignments from your product-level configuration. If a required GL mapping is missing, the transaction is rejected with a clear error message rather than silently falling back to an incorrect default account. An audit log entry is created for every transaction, recording which GL accounts were used and which product configuration was applied.

**What to verify after the update:** Review your trial balance for any unexpected balances in accounts that should normally be zero (e.g., catch-all or suspense accounts). Run a GL mapping audit report covering the past 30 days — the result should show no misclassified transactions.

---

### 5. Credit and Reversal Notes Now Process Correctly
**Who is affected:** All clients — any scenario requiring a transaction reversal or refund.

**What was happening:** When a transaction needed to be reversed (for example, a payment posted in error, or a refund due to the customer), the system was generating incomplete reversal entries — sometimes reversing only the principal component and leaving the interest component unreversed, or generating no reversal at all. This left phantom balances in your accounting system.

**What we fixed:** Reversal entries now correctly mirror the original transaction in full — every component (principal, interest, fees) is reversed in the same amounts, and a cross-reference link is created between the original entry and the reversal. No net income or expense is generated by a reversal. All affected GL accounts return to their correct balances.

**What to verify after the update:** Review recent refund or reversal transactions. Each should appear as a matched pair (original + reversal) in your transaction ledger, and the net impact on your GL should be zero.

---

### 6. Guarantee Account Balances Can No Longer Go Negative (Friendship Bridge)
**Who is affected:** Friendship Bridge — guarantee/collateral accounts.

**What was happening:** In certain cases involving loan modifications or rescheduling, the system was releasing a larger guarantee amount than was actually on record, causing the guarantee account balance to go below zero. A negative balance in a guarantee account is not a valid accounting state and could raise flags in regulatory reports.

**What we fixed:** The system now ensures that the amount released from a guarantee account never exceeds the current recorded balance. When a loan is modified, the guarantee record is updated to reflect the new loan terms before any release is processed. The system will reject any operation that would result in a negative guarantee balance and alert the operations team.

**What to verify after the update:** Run your Guarantee Register report. All active and recently closed guarantees should show balances ≥ $0. We recommend verifying any guarantees associated with loans modified or rescheduled in the past 90 days.

---

### 7. Snooze and Refund Flows No Longer Break the Repayment Schedule (Tamara)
**Who is affected:** Tamara — customers with snooze (payment postponement) and/or refund operations.

**What was happening:** When a customer's payment was snoozed (postponed) and a refund was also applied to their account — in either order — the repayment schedule could become corrupted: showing wrong due dates, incorrect instalment amounts, or phantom zero-amount instalments.

**What we fixed:** The system now correctly combines both operations: snooze adjustments (date shifts) and refund adjustments (balance reductions) are applied sequentially and coherently to produce a clean, accurate repayment schedule. Every instalment in the resulting schedule reflects the correct date and amount, and the sum of all remaining instalments equals the outstanding balance.

**What to verify after the update:** For customers who had both a snooze and a refund applied, review their repayment schedule in the customer portal or back-office system. Confirm that all future instalment dates are correct, all amounts are positive and non-zero, and the total outstanding equals their actual remaining balance.

---

### 8. Late Payment Fees Now Recorded in the Correct Income Account
**Who is affected:** All clients using late payment / penalty charges.

**What was happening:** Late payment fees and penalty charges were being posted to the Interest Income account instead of a dedicated Penalty/Fee Income account. This mixed two distinct revenue streams into a single line, making income analysis inaccurate and complicating regulatory reporting.

**What we fixed:** The penalty job now reads the GL account configuration for each loan product and posts penalty charges to the correct Penalty Income account. Interest Income and Penalty Income will appear as separate, clearly labeled lines in your P&L going forward.

**What to verify after the update:** Check your P&L for the current period. You should see a distinct "Penalty Income" or "Late Fee Income" line separate from "Interest Income." Cross-reference the amounts against your penalty charge records.

---

## Action Required

| Action | Who | When |
|--------|-----|------|
| Review and sign off on GL balances after update | CFO / Head of Finance | Within 5 business days of release |
| Run Closed Loans report and validate interest amounts | Accounting Team | Within 3 business days of release |
| Verify Guarantee Register (Friendship Bridge) | FBR Accounting Team | Within 5 business days of release |
| Review 3 customer schedules with snooze+refund (Tamara) | Tamara Ops Team | Within 3 business days of release |
| Confirm GL mapping audit report shows clean results | Accounting Team | Within 7 business days of release |

---

## Questions?

If you have questions about these changes or need assistance verifying your ledger balances, please contact your Fiter account manager or open a support ticket at [support channel]. Our team is available to walk through any of these fixes with your accounting staff.

---

*This document was prepared by Fiter for its clients' finance and accounting teams. Technical implementation details are available separately for IT and development teams upon request.*
