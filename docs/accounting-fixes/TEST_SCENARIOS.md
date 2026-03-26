# TEST_SCENARIOS.md — Escenarios de Prueba Funcionales (BDD)
**Proyecto:** Fineract Accounting Engine Fixes  
**Versión:** 1.0  
**Fecha:** 2026-03-25  
**Scope:** Top 3 bugs críticos: #5 Interest Posting Twice, #4 Unbalanced Bulk Payments, #1 Accrual en Preclosure

> **Nota para QA/UAT:** Estos escenarios están escritos en formato Gherkin (Given/When/Then) y pueden ejecutarse en cualquier ambiente de testing con datos de prueba controlados. Cada escenario incluye los datos mínimos necesarios y el resultado verificable esperado.

---

## BUG #5 — Interest Posting Twice

### Escenario 5.1 — Idempotencia: Job ejecutado dos veces el mismo día

```gherkin
Feature: Interest Accrual Job — Idempotencia
  Como responsable contable
  Quiero que el job de accrual de interés sea idempotente
  Para que una doble ejecución no distorsione el P&L

  Background:
    Given existe un préstamo activo con ID "LOAN-001"
    And el préstamo tiene capital pendiente de $10,000
    And la tasa de interés anual es 18% (1.5% mensual, ~$5/día)
    And el último accrual fue registrado el día anterior
    And el GL de "Accrued Interest Receivable" muestra saldo $X antes de la ejecución
    And el GL de "Interest Income" muestra saldo $Y antes de la ejecución

  Scenario: Primera ejecución del job de accrual — resultado esperado
    When el job de accrual se ejecuta para la fecha de hoy
    Then se genera exactamente 1 journal entry de accrual para LOAN-001
    And el monto del entry = interés devengado del día ($5.00 para este ejemplo)
    And el GL "Accrued Interest Receivable" aumenta en $5.00 (saldo = $X + $5.00)
    And el GL "Interest Income" aumenta en $5.00 (saldo = $Y + $5.00)
    And el log del job registra: "Accrual procesado para LOAN-001, período: [fecha hoy], monto: $5.00"

  Scenario: Segunda ejecución del job de accrual el mismo día — debe ser no-op
    Given el job de accrual ya fue ejecutado exitosamente hoy para LOAN-001
    When el job de accrual se ejecuta nuevamente para la misma fecha
    Then NO se genera ningún journal entry adicional para LOAN-001
    And el GL "Accrued Interest Receivable" mantiene el mismo saldo que después de la primera ejecución
    And el GL "Interest Income" mantiene el mismo saldo que después de la primera ejecución
    And el log del job registra: "WARNING: Accrual ya procesado para LOAN-001, período: [fecha hoy]. Operación omitida."
    And el job finaliza sin error (exit code 0)

  Scenario: Verificación de audit trail tras doble ejecución
    Given el job fue ejecutado dos veces para la misma fecha
    When se consulta el reporte "Interest Accrual Audit" para LOAN-001 en esa fecha
    Then el reporte muestra exactamente 1 registro de accrual
    And el reporte muestra en la columna "Intentos de ejecución": 2
    And el reporte muestra en la columna "Ejecutados efectivamente": 1
```

### Escenario 5.2 — Reprocessing legítimo con autorización explícita

```gherkin
  Scenario: Reprocessing autorizado de un período ya procesado
    Given el accrual del día anterior fue procesado con un monto incorrecto
    And un usuario con rol "Accounting Admin" inicia un "Force Rerun" para esa fecha
    And el sistema solicita confirmación con mensaje: "Esto revertirá el accrual existente y generará uno nuevo. ¿Confirmar?"
    And el usuario confirma la operación
    When el force rerun se ejecuta
    Then el journal entry original del accrual es revertido (contra-asiento)
    And se genera un nuevo journal entry con el monto correcto
    And el GL queda con el saldo correcto (como si el reprocessing nunca hubiera ocurrido con el monto incorrecto)
    And el audit log registra: quién autorizó el rerun, fecha/hora, motivo
```

### Escenario 5.3 — Múltiples préstamos en batch: ninguno debe duplicarse

```gherkin
  Scenario: Job batch con 100 préstamos — verificación de unicidad
    Given existen 100 préstamos activos en el sistema
    And el job de accrual se ejecuta en modo batch para todos los préstamos activos
    When el job completa su ejecución
    Then el total de journal entries generados = exactamente 100 (uno por préstamo)
    And para cada préstamo, existe exactamente 1 journal entry de accrual para la fecha de hoy
    And el reporte post-run muestra: "100 préstamos procesados, 0 duplicados, 0 errores"
    And Σ de todos los débitos en Accrued Interest Receivable = Σ de todos los créditos en Interest Income
```

---

## BUG #4 — Unbalanced Journal Entries en Bulk Payments

### Escenario 4.1 — Bulk payment exitoso: todos los entries balanceados

```gherkin
Feature: Bulk Payments — Journal Entries Balanceados
  Como responsable de operaciones de crédito
  Quiero que los pagos masivos generen asientos contables balanceados
  Para que el GL no quede fuera de balance después de un bulk run

  Background:
    Given existe un archivo de bulk payment con 50 transacciones
    And los montos son: 30 pagos de $100 + 15 pagos de $250 + 5 pagos de $1,000 (total: $12,750)
    And todos los préstamos correspondientes están activos y tienen saldo pendiente suficiente

  Scenario: Bulk payment procesado exitosamente — balance verificado
    When el bulk payment run se ejecuta
    Then todos los 50 pagos son procesados exitosamente
    And se generan 50 journal entries individuales (o 1 entry agregado por el batch)
    And para cada journal entry individual: Débitos = Créditos
    And para el batch completo: Σ Débitos = Σ Créditos = $12,750
    And el reporte post-run muestra: "Balance del batch: $0.00 (balanceado)"
    And el GL de "Suspense / Clearing" queda en $0.00
    And el GL de "Loan Principal Receivable" se reduce en el monto de capital de cada pago
    And el GL de "Cash / Client Account" se reduce en $12,750 total
```

### Escenario 4.2 — Bulk payment con fallos parciales: atomicidad por transacción

```gherkin
  Scenario: Bulk payment con 3 pagos fallidos — los fallidos se revierten completamente
    Given existe un archivo de bulk payment con 50 transacciones
    And 3 de los préstamos tienen estado "Closed" y no pueden recibir pagos
    When el bulk payment run se ejecuta
    Then 47 pagos son procesados exitosamente
    And 3 pagos son rechazados con error: "Loan closed — payment rejected"
    And para los 3 pagos rechazados: NO se generó ningún journal entry en el GL
    And para los 47 pagos exitosos: Σ Débitos = Σ Créditos
    And el reporte post-run muestra: "47 exitosos, 3 fallidos, Balance del batch: $0.00"
    And el GL de "Suspense / Clearing" queda en $0.00
    And el batch fallido genera un archivo de excepciones con los 3 registros rechazados para reprocessing manual
```

### Escenario 4.3 — Bulk payment con diferencias de redondeo

```gherkin
  Scenario: Bulk payment con montos en múltiples monedas — rounding controlado
    Given existe un bulk payment con 20 transacciones en NGN (Naira nigeriana)
    And algunos montos generan fracciones de kobo (centavos) en el cálculo de interés
    When el bulk payment run se ejecuta
    Then todas las diferencias de redondeo (< 0.01 NGN por transacción) se asignan al GL "Rounding Differences"
    And el GL "Rounding Differences" muestra un saldo neto acumulado del batch
    And el saldo de "Rounding Differences" es visible en el reporte de cierre diario
    And el reporte muestra: "Rounding differences totales: X kobo (dentro del umbral permitido: 100 kobo)"
    And Σ Débitos = Σ Créditos cuando se incluye "Rounding Differences" en el cálculo
```

### Escenario 4.4 — Verificación post-run: GL fuera de balance debe generar alerta

```gherkin
  Scenario: Detección proactiva de GL fuera de balance post-bulk
    Given el bulk payment run acaba de completarse
    When el sistema ejecuta la verificación post-run de balance
    And detecta que Σ Débitos ≠ Σ Créditos (diferencia de $5.00)
    Then el sistema genera una alerta inmediata al equipo de contabilidad
    And la alerta incluye: ID del batch, monto de la diferencia, timestamp
    And el sistema marca el batch como "REQUIRES REVIEW" en el log
    And NO permite procesar el siguiente bulk run hasta que el equipo resuelva la discrepancia
    And el journal entry o entries causantes de la diferencia son identificados en el log
```

---

## BUG #1 — Accrual en Advance Payment / Preclosure

### Escenario 1.1 — Preclosure en fecha intermedia: interés pro-rata correcto

```gherkin
Feature: Preclosure — Cálculo de Accrual Pro-Rata
  Como prestatario / Como auditor
  Quiero que el interés cobrado en un preclosure sea exactamente el devengado hasta la fecha
  Para que el cobro sea justo y cumplir con regulaciones de consumer protection

  Background:
    Given existe un préstamo LOAN-002 con las siguientes características:
      | Capital original       | $12,000      |
      | Tasa anual             | 24%          |
      | Plazo                  | 12 meses     |
      | Fecha de desembolso    | 2026-01-01   |
      | Fecha de último pago   | 2026-03-01   |
      | Capital pendiente      | $9,000       |
      | Interés del período actual devengado al 2026-03-01 | $0.00 |

  Scenario: Preclosure el día 15 del mes — solo se cobra interés de los 15 días
    Given el cliente solicita preclosure el 2026-03-15
    And han transcurrido 15 días desde el 2026-03-01 (último pago)
    And la tasa diaria = 24% / 365 = 0.065753%
    And el interés devengado en 15 días sobre $9,000 = $88.77 (aprox)
    When el sistema procesa el preclosure
    Then el monto total cobrado = $9,000 (capital) + $88.77 (interés 15 días)
    And el sistema genera un journal entry de cierre con:
      | Débito  | Cash / Client Account     | $9,088.77 |
      | Crédito | Loan Principal Receivable | $9,000.00 |
      | Crédito | Accrued Interest Receivable | $88.77  |
    And el GL "Accrued Interest Receivable" para LOAN-002 queda en $0.00
    And el GL "Loan Principal Receivable" para LOAN-002 queda en $0.00
    And el estado del préstamo cambia a "Closed (Preclosed)"
    And el reporte del préstamo muestra: interés total cobrado en vida = interés calculado correctamente

  Scenario: Preclosure mismo día del desembolso — interés = $0
    Given existe un préstamo LOAN-003 desembolsado hoy (2026-03-25)
    And no han transcurrido días desde el desembolso
    When el cliente solicita preclosure el mismo día del desembolso
    Then el monto total cobrado = capital desembolsado únicamente (sin interés)
    And el GL "Accrued Interest Receivable" para LOAN-003 = $0.00
    And el GL "Interest Income" no registra ningún monto para LOAN-003
```

### Escenario 1.2 — Advance payment parcial: la base de interés futura se reduce

```gherkin
  Scenario: Advance payment parcial — el accrual siguiente usa el capital reducido
    Given existe LOAN-004 con capital pendiente $10,000
    And el próximo vencimiento de cuota es en 20 días
    When el cliente hace un advance payment de $2,000 (reducción de capital)
    Then el capital pendiente de LOAN-004 = $8,000 (no $10,000)
    And el sistema registra el advance payment con journal entry:
      | Débito  | Cash / Client Account     | $2,000 |
      | Crédito | Loan Principal Receivable | $2,000 |
    And el siguiente accrual diario (mañana) calcula el interés sobre $8,000
    And NO calcula el interés sobre $10,000
    And el schedule de cuotas restante se actualiza para reflejar el nuevo saldo de capital
    And el GL "Accrued Interest Receivable" acumula desde mañana usando la base $8,000

  Scenario: Verificación post-preclosure — todos los GL del préstamo en $0
    Given LOAN-005 fue preclose exitosamente
    When se ejecuta la verificación post-close
    Then los siguientes GL para LOAN-005 muestran saldo $0.00:
      | GL Account                    |
      | Loan Principal Receivable     |
      | Accrued Interest Receivable   |
      | Unearned Income               |
      | Penalty Receivable (si aplica)|
    And el estado del préstamo en el sistema = "Closed"
    And el reporte de "Loan Portfolio" no incluye LOAN-005 como activo
    And el reporte de "Closed Loans" incluye LOAN-005 con fecha de cierre correcta
```

### Escenario 1.3 — Preclosure después de un advance payment previo

```gherkin
  Scenario: Preclosure de préstamo que ya tuvo un advance payment — base correcta
    Given LOAN-006 tenía capital original $15,000
    And el cliente hizo un advance payment de $5,000 hace 30 días (capital reducido a $10,000)
    And desde el advance payment, el accrual se calculó correctamente sobre $10,000
    And el accrual acumulado desde el advance payment = $150 (30 días × tasa diaria × $10,000)
    When el cliente solicita preclosure hoy
    Then el monto total cobrado = $10,000 (capital) + $150 (interés devengado post-advance payment)
    And el sistema NO cobra interés calculado sobre los $15,000 originales
    And los GL quedan en $0.00 después del cierre
    And el audit trail muestra: advance payment original + preclosure, con montos coherentes
```

---

## Matriz de Cobertura UAT

| Escenario | Bug | Ejecutable por Cliente | Datos requeridos |
|-----------|-----|----------------------|-----------------|
| 5.1 | Interest twice | QA / Cliente | 1 préstamo activo, acceso a job runner |
| 5.2 | Interest twice | QA | Rol Admin + ambiente de test |
| 5.3 | Interest twice | QA | 100 préstamos en test |
| 4.1 | Bulk balance | QA / Cliente | Archivo CSV de 50 pagos |
| 4.2 | Bulk balance | QA | 3 préstamos cerrados en test |
| 4.3 | Bulk balance | QA | Transacciones en NGN |
| 4.4 | Bulk balance | QA | Capacidad de forzar un desbalance en test |
| 1.1 | Preclosure accrual | QA / Cliente | 1 préstamo con historial claro |
| 1.2 | Preclosure accrual | QA / Cliente | 1 préstamo con advance payment |
| 1.3 | Preclosure accrual | QA / Cliente | 1 préstamo con ambos eventos |

> **Para UAT con cliente:** Los escenarios marcados como "Cliente" son ejecutables por el equipo contable del cliente en ambiente UAT con sus propios datos anonimizados. Requieren acceso al frontend de Fineract + reporte GL.
