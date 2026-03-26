# BA_ANALYSIS.md — Análisis de Negocio: Motor Contable Fineract
**Proyecto:** Fineract Accounting Engine Fixes  
**Versión:** 1.0  
**Fecha:** 2026-03-25  
**Autor:** Obi (BA/Product Expert, Fiter)

---

## Resumen Ejecutivo

El motor contable de Apache Fineract representa el 24% del volumen de tickets de soporte de Fiter, con impacto simultáneo en todos los clientes: Oxygen/Access Bank (Nigeria), Tamara (Middle East), Friendship Bridge/FBR (Guatemala) y Entre Amigos/EA (LATAM).

Los 8 patrones de bugs identificados generan tres categorías de riesgo:

1. **Riesgo regulatorio y de auditoría** — Asientos desbalanceados, GL incorrectos e intereses duplicados producen estados financieros erróneos. Para clientes regulados como Access Bank Nigeria (CBN supervision) y Tamara (regulación BNPL en Arabia Saudita/UAE), esto puede derivar en sanciones regulatorias o pérdida de licencia.

2. **Riesgo financiero directo** — Bugs como el doble posting de intereses, cálculo incorrecto de accrual en preclosure y negative balances en garantías generan P&L distorsionado y posibles pagos/cobros incorrectos a clientes finales.

3. **Riesgo operacional / reputacional** — Cada ticket genera carga en el equipo de soporte de Fiter (estimado: 2–4h por ticket), escala a los clientes CFO/COO, y erosiona la confianza en la plataforma.

**Prioridades críticas identificadas:** Interest posting twice (Bug #5), Unbalanced journal entries en bulk payments (Bug #4), Accrual en advance payment/preclosure (Bug #1).

**Recomendación:** Abordar los 3 críticos en el próximo sprint y los 3 de prioridad Alta inmediatamente después. Los Medios pueden ir al backlog trimestral.

---

## Bug #1 — Accrual en Advance Payment / Preclosure

### Impacto de Negocio
Si no se corrige, el banco registra intereses acumulados incorrectos al momento del preclosure. Esto puede:
- **Sobrecobrar al prestatario** (riesgo regulatorio de consumer protection: CBN, SBS Guatemala, CNBV México)
- **Subregistrar ingresos** en el P&L (el banco cobra menos de lo que debería)
- **Distorsionar el Income Statement** en el período de cierre del préstamo
- Para FBR y EA (microfinanzas): el impacto es directo sobre poblaciones vulnerables — sobrecobros pueden generar denuncias regulatorias

### Comportamiento Esperado (IFRS 9 / Accrual Basis)
Cuando un préstamo se pre-cierra (pago anticipado total), el sistema debe:
1. Calcular interés acumulado hasta la fecha efectiva de preclosure (pro-rata temporis)
2. Revertir cualquier interés acumulado "futuro" que se haya registrado en períodos aún no devengados
3. Generar una entrada neta única: Accrued Interest Receivable → Interest Income (solo el monto devengado hasta hoy)
4. Liberar el capital pendiente: Loan Principal Receivable → Cash/Client Account

### Comportamiento Actual Defectuoso
El job de accrual calcula el interés sobre el schedule original completo, sin recalcular el devengado proporcional a la fecha de preclosure. En algunos escenarios de advance payment parcial, el interés se acumula sobre el capital original en lugar del capital reducido post-pago. Resultado: el asiento de cierre incluye interés que no se devengó.

### Criterios de Aceptación (perspectiva de negocio)
- AC1: Al pre-cerrar un préstamo en cualquier fecha intermedia, el total de interés cobrado = interés devengado según días transcurridos desde el último pago, calculado sobre el saldo de capital vigente
- AC2: El GL de Accrued Interest Receivable queda en $0.00 inmediatamente después del preclosure
- AC3: El GL de Interest Income refleja únicamente el interés devengado hasta la fecha de preclosure, no del período completo
- AC4: Un payment de advance (pago anticipado parcial) reduce la base de cálculo del interés subsiguiente
- AC5: El reporte de préstamo cerrado muestra balance = $0 en todas las cuentas relacionadas

### Prioridad de Negocio
**🔴 CRÍTICO** — Impacto financiero directo al prestatario + riesgo regulatorio de consumer protection

---

## Bug #2 — Wrong GL Mappings

### Impacto de Negocio
Las transacciones se contabilizan en cuentas GL incorrectas. Impacto:
- **Estados financieros incorrectos** en toda categoría (Balance Sheet y P&L)
- **Auditoría fallida**: los auditores identificarán discrepancias entre el libro mayor y los reportes de producto
- **Reconciliación imposible**: el equipo contable no puede cuadrar los libros sin intervención manual
- **Riesgo regulatorio**: reportes prudenciales erróneos (capital adequacy, provisioning)
- Afecta a todos los clientes — cualquier cambio de configuración o upgrade puede desencadenar remappings incorrectos

### Comportamiento Esperado
Cada tipo de transacción (disbursement, repayment, accrual, penalty, write-off) debe mapearse a las cuentas GL definidas en el chart of accounts del cliente. El mapeo debe ser determinista, configurable por producto de préstamo, y auditado en cada posting.

### Comportamiento Actual Defectuoso
En ciertos escenarios de upgrade de configuración o multi-product, el sistema resuelve el GL mapping usando defaults del sistema en lugar de la configuración específica del producto. Algunos eventos de lifecycle (ej: rescheduling, write-off parcial) no tienen mapeo explícito y caen en cuentas GL catch-all o incorrectas.

### Criterios de Aceptación
- AC1: Cada transacción loguea la cuenta GL de débito y crédito usada, con referencia al product ID y mapping version
- AC2: Un report de "GL Mapping Audit" permite verificar que cada transacción del día usó el mapeo correcto
- AC3: Ante un mapeo faltante, el sistema rechaza la transacción y genera una alerta, en lugar de usar un default silencioso
- AC4: El Chart of Accounts del cliente muestra $0 en cuentas "catch-all" no intencionadas
- AC5: Después del fix, la reconciliación manual de 30 días previos produce diferencia $0

### Prioridad de Negocio
**🟠 ALTO** — Impacto en auditoría y reconciliación; no genera pérdida directa inmediata si se detecta a tiempo, pero es un riesgo sistémico

---

## Bug #3 — Credit Note Logic

### Impacto de Negocio
La ausencia o incorreción de la lógica de notas de crédito implica:
- **Reversals incorrectos**: cuando se necesita revertir un cobro erróneo, el sistema no genera el crédito correspondiente, dejando el GL en estado inconsistente
- **Sobrecobros no reembolsados**: clientes finales a quienes se les cobró de más y no reciben el crédito correcto
- **Para Tamara (BNPL)**: particularmente crítico en flujos de refund — el credit note es la pieza central del proceso de devolución
- **Riesgo de doble exposición**: sin credit note correcto, el banco puede tener un activo registrado que ya fue pagado/reversado

### Comportamiento Esperado (IFRS)
Un credit note debe:
1. Revertir exactamente los asientos originales (mismo monto, cuentas opuestas)
2. Generar un documento de referencia cruzada (original entry ID → credit note ID)
3. No generar nuevo income/expense — es una reversión neta
4. Actualizar el saldo del cliente y el GL en el mismo batch

### Comportamiento Actual Defectuoso
El sistema genera credit notes parciales o con montos incorrectos. En algunos casos, el credit note revierte solo el componente de capital, dejando el interés sin revertir. En Tamara, los refunds post-snooze no generan credit note alguno, dejando el accrued interest en el GL sin contraparte.

### Criterios de Aceptación
- AC1: Un credit note revierte exactamente los mismos componentes (principal + interest + fees) del cargo original
- AC2: Después de aplicar un credit note, el saldo neto del GL de todos los componentes afectados = $0 o el monto esperado post-reversión
- AC3: El reporte de transacciones del cliente muestra el par (cargo original, credit note) con referencia cruzada
- AC4: Para Tamara: un refund genera automáticamente el credit note correspondiente al interés acumulado del período snoozeado
- AC5: No se generan income/expense netos en el P&L como resultado de un credit note puro

### Prioridad de Negocio
**🟠 ALTO** — Impacto directo en Tamara (flujo core de negocio) y en cualquier reversión

---

## Bug #4 — Unbalanced Journal Entries en Bulk Payments

### Impacto de Negocio
Pagos masivos (bulk payments) generan asientos donde Débitos ≠ Créditos. Consecuencias:
- **El GL queda fuera de balance** — violación del principio fundamental de partida doble
- **Cierre contable imposible**: el período no puede cerrarse hasta que se encuentre y corrija la discrepancia
- **Riesgo de auditoría**: un GL fuera de balance es una finding crítica en cualquier auditoría externa
- **Para Access Bank / Oxygen**: en Nigeria (CBN regulations), un GL desbalanceado en reportes prudenciales es una infracción grave
- **Carga operacional**: el equipo de Fiter debe investigar y corregir manualmente cada evento de bulk payment afectado

### Comportamiento Esperado
Todo journal entry, individual o agregado, debe cumplir: Σ Débitos = Σ Créditos. En bulk payments, cada pago individual debe generar su propio journal entry balanceado, O el batch completo debe generar un journal entry agregado balanceado.

### Comportamiento Actual Defectuoso
En bulk payment processing, cuando un pago individual falla o genera una excepción parcial, el sistema registra los asientos exitosos sin revertir los fallidos, dejando entries huérfanos. En algunos casos, el rounding de múltiples montos en distintas monedas genera diferencias de centavos que no se asignan a ninguna cuenta de diferencia de cambio.

### Criterios de Aceptación
- AC1: Después de cualquier bulk payment run, la suma de todos los journal entries generados en el batch cumple Σ Débitos = Σ Créditos
- AC2: Si un pago individual dentro del bulk falla, todos los asientos de ese pago individual se revierten atómicamente (todo o nada)
- AC3: El sistema genera un reporte post-bulk-run con: # payments procesados, # fallidos, balance total del batch (debe ser $0)
- AC4: Las diferencias de redondeo (si existen) se asignan a una cuenta GL de rounding differences definida en la configuración, no quedan sin asignar
- AC5: El GL de "suspense" o "clearing" queda en $0 al final de cada bulk payment run

### Prioridad de Negocio
**🔴 CRÍTICO** — Un GL fuera de balance impide el cierre contable y es una finding de auditoría crítica

---

## Bug #5 — Interest Posting Twice

### Impacto de Negocio
El job de interés ejecuta el posting dos veces en ciertos escenarios (ej: rerun del job, cambio de timezone, job queue duplicado). Consecuencias:
- **P&L inflado**: el banco registra el doble de ingresos por intereses — estados financieros incorrectos
- **Cuentas de prestatario sobrecargadas**: el cliente ve el doble de interés acumulado en su estado de cuenta
- **Reconciliación fallida**: la conciliación entre el sistema de préstamos y el GL no cuadra
- **Para todos los clientes**: este bug afecta el engine central y se puede disparar en cualquier cliente

### Comportamiento Esperado
El job de accrual de intereses debe ser **idempotente**: ejecutarlo N veces para el mismo período debe producir el mismo resultado que ejecutarlo 1 vez. El sistema debe verificar si ya existe un posting para el período antes de generar uno nuevo.

### Comportamiento Actual Defectuoso
El job no implementa un mecanismo de deduplicación por período. Si el job se ejecuta dos veces (ya sea por error operacional, reprocessing, o race condition en el scheduler), genera dos journal entries de interés para el mismo período. No hay lock o idempotency key que lo prevenga.

### Criterios de Aceptación
- AC1: Ejecutar el job de accrual dos veces para el mismo período produce exactamente el mismo resultado en el GL que ejecutarlo una vez
- AC2: El sistema registra en el log de jobs: fecha, período procesado, resultado. Un segundo run del mismo período genera un warning y no modifica el GL
- AC3: El GL de Accrued Interest Receivable e Interest Income muestra exactamente 1 entry por período por préstamo, nunca 2
- AC4: Un reporte de "Interest Accrual Audit" permite verificar que cada préstamo activo tiene exactamente 1 accrual entry por período
- AC5: En caso de reprocessing legítimo (ej: corrección de error), existe un mecanismo explícito de "force rerun" con trazabilidad de quién lo autorizó

### Prioridad de Negocio
**🔴 CRÍTICO** — Impacto directo en P&L, estados financieros y cuentas de clientes finales

---

## Bug #6 — Guarantee Negative Balances (FBR)

### Impacto de Negocio
Las garantías (collateral/guarantee accounts) muestran saldos negativos en el GL, lo cual es contablemente imposible. Consecuencias específicas para FBR (microfinanzas Guatemala):
- **Reporte regulatorio incorrecto**: la SIB Guatemala exige reportes de garantías — un saldo negativo es una anomalía que puede requerir explicación a la entidad reguladora
- **Provisioning incorrecto**: si las garantías se usan para calcular reservas (loan loss provisions), un saldo negativo distorsiona el cálculo
- **Balance Sheet incorrecto**: activos de garantía con saldo negativo son un finding de auditoría
- **Riesgo operacional en FBR**: en microfinanzas, las garantías son frecuentemente prendas comunitarias — un error contable puede generar disputas con los grupos de préstamo

### Comportamiento Esperado
El GL de garantías debe ser siempre ≥ $0. Cuando se libera una garantía, el saldo va a $0 (no negativo). Las garantías son activos contingentes: se debitan al momento del registro y se acreditan al momento de la liberación, nunca más allá de lo registrado.

### Comportamiento Actual Defectuoso
Al liberar una garantía después de un rescheduling o modification del préstamo, el sistema puede generar un crédito mayor al saldo registrado, llevando el GL a territorio negativo. El bug parece estar en que el monto de la garantía no se actualiza cuando el préstamo es modificado, pero el release usa el monto original.

### Criterios de Aceptación
- AC1: El saldo del GL de garantías nunca puede ser < $0 — cualquier operación que lo intentara debe ser rechazada con error descriptivo
- AC2: Al liberar una garantía, el monto liberado = monto registrado vigente (no el monto original si hubo modificación)
- AC3: Después del fix, ejecutar un script de verificación sobre garantías activas de FBR muestra 0 registros con saldo negativo
- AC4: Un reporte de "Guarantee Register" muestra: monto original, monto vigente, estado (activo/liberado/ejecutado), saldo GL
- AC5: Las modificaciones de préstamo (rescheduling, top-up) actualizan el registro de garantía correspondiente

### Prioridad de Negocio
**🟠 ALTO** — Impacto regulatorio en FBR + Balance Sheet incorrecto

---

## Bug #7 — Snooze + Refund Schedule Break (Tamara)

### Impacto de Negocio
En Tamara (BNPL), la combinación de operaciones snooze (postponer cuota) + refund (devolución parcial o total) rompe el repayment schedule. Consecuencias:
- **Cuotas futuras incorrectas**: el cliente ve montos o fechas erróneas en su plan de pago
- **Accrual incorrecto**: el interés se calcula sobre el schedule roto, no sobre la deuda real
- **Experiencia de cliente dañada**: en BNPL, el plan de pagos es el producto — si está mal, hay quejas directas
- **Riesgo de impago artificial**: el sistema puede marcar cuotas como vencidas cuando en realidad no lo están (o viceversa)
- **Para Tamara**: este es un flujo de negocio core — snooze y refunds son features frecuentes. Un bug aquí tiene alta frecuencia de ocurrencia

### Comportamiento Esperado
Cuando se aplica un snooze: las cuotas posteriores se recorren N días manteniendo los montos originales. Cuando se aplica un refund: el capital pendiente se reduce proporcionalmente y el schedule se recalcula distribuyendo el saldo restante. La combinación de ambos debe producir un schedule coherente: cuotas en fechas correctas (snooze aplicado) con montos correctos (capital reducido por refund).

### Comportamiento Actual Defectuoso
Al aplicar refund después de un snooze (o viceversa), el sistema recalcula el schedule desde el estado original del préstamo, ignorando el snooze previo. Resultado: las fechas del snooze se pierden, o el capital del refund no se aplica correctamente a las cuotas pendientes. En algunos casos, el schedule queda con cuotas con monto $0 o duplicadas.

### Criterios de Aceptación
- AC1: Después de snooze + refund (en cualquier orden), el schedule total de cuotas refleja: (a) fechas corregidas por el snooze, (b) montos reducidos por el refund, (c) Σ cuotas pendientes = capital pendiente + interés pendiente
- AC2: No existen cuotas con monto $0 o negativo en el schedule resultante
- AC3: El próximo vencimiento mostrado al cliente es la fecha correcta post-snooze
- AC4: El GL de principal receivable refleja la deuda real post-refund (no la original)
- AC5: Simular 10 combinaciones distintas de snooze+refund en UAT produce 10 schedules correctos verificables

### Prioridad de Negocio
**🟠 ALTO** — Bug en flujo core de Tamara, alta frecuencia de ocurrencia

---

## Bug #8 — Penalty Job GL Incorrecto

### Impacto de Negocio
El job de penalidades (late payment fees, penalty charges) usa la cuenta GL incorrecta para registrar los cargos. Consecuencias:
- **P&L incorrecto**: los ingresos por penalidades no se registran en la cuenta de fee income correcta
- **Análisis de negocio distorsionado**: el cliente no puede saber cuánto ingresa por penalidades vs. interés regular
- **Auditoría**: discrepancia entre el subledger de penalidades y el GL
- **Impacto en todos los clientes**: el job es compartido — cualquier cliente con late payment fees está afectado

### Comportamiento Esperado
El job de penalidades debe registrar:
- Débito: Penalty Receivable (o directamente Cash si se cobra al instante)
- Crédito: Penalty Income (cuenta de ingresos específica para penalidades)

La cuenta GL debe provenir del mapeo del producto, no de un default hardcodeado.

### Comportamiento Actual Defectuoso
El job usa la cuenta GL de Interest Income en lugar de Penalty Income para el crédito, mezclando dos tipos de ingresos en una misma cuenta. Esto puede deberse a un mapeo mal configurado en el job o a que el job no lee el GL mapping del producto.

### Criterios de Aceptación
- AC1: Todas las penalidades generadas se registran en la cuenta GL "Penalty Income" configurada para el producto, no en "Interest Income"
- AC2: El reporte de P&L desglosa Interest Income y Penalty Income como líneas separadas
- AC3: La reconciliación entre el subledger de penalidades y el GL de Penalty Income produce diferencia $0
- AC4: Cambiar la cuenta GL de Penalty Income en la configuración del producto se refleja en los postings futuros del job
- AC5: Los postings históricos incorrectos pueden identificarse con un query para cuantificar el reclasificación necesaria

### Prioridad de Negocio
**🟡 MEDIO** — Impacto en reporting y análisis, no genera pérdida directa inmediata

---

## Resumen de Prioridades

| # | Bug | Clientes Afectados | Prioridad |
|---|-----|-------------------|-----------|
| 5 | Interest posting twice | Todos | 🔴 CRÍTICO |
| 4 | Unbalanced journal entries bulk payments | Todos (esp. Oxygen) | 🔴 CRÍTICO |
| 1 | Accrual en advance payment/preclosure | Todos | 🔴 CRÍTICO |
| 2 | Wrong GL mappings | Todos | 🟠 ALTO |
| 3 | Credit note logic | Tamara, todos | 🟠 ALTO |
| 6 | Guarantee negative balances | FBR | 🟠 ALTO |
| 7 | Snooze+refund schedule break | Tamara | 🟠 ALTO |
| 8 | Penalty job GL incorrecto | Todos | 🟡 MEDIO |
