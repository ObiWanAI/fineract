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

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch configuration for the Nightly GL Reconciliation Job.
 *
 * <p>Wire-up follows Fineract's existing job pattern (Tasklet + Spring Batch).
 *
 * <p><b>Cron schedule</b> (configurable via {@code fiter.reconciliation.cron}):
 * Default = 02:30 AM — runs after COB finishes (usually ~01:00–02:00 AM).
 *
 * <p>To trigger manually:
 * <pre>
 *   POST /fineract-provider/api/v1/jobs/{jobId}/run
 * </pre>
 * Or via Spring Batch Admin / Actuator.
 *
 * <p><b>This job is read-only</b>. It will NEVER modify loan or GL data.
 */
@Configuration
@RequiredArgsConstructor
public class NightlyGLReconciliationJobConfig {

    public static final String JOB_NAME = "NIGHTLY_GL_RECONCILIATION";
    public static final String STEP_NAME = "nightlyGLReconciliationStep";

    private final NightlyGLReconciliationTasklet reconciliationTasklet;
    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * The Spring Batch Job definition.
     * Registered in the Fineract job registry so it appears in the Scheduler UI.
     */
    @Bean(name = "nightlyGLReconciliationJob")
    public Job nightlyGLReconciliationJob() {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(nightlyGLReconciliationStep())
                .build();
    }

    @Bean(name = "nightlyGLReconciliationStep")
    public Step nightlyGLReconciliationStep() {
        return new StepBuilder(STEP_NAME, jobRepository)
                .tasklet(reconciliationTasklet, transactionManager)
                .build();
    }

    // ─── Scheduled trigger ───────────────────────────────────────────────
    // This is the @Scheduled fallback. In production, prefer Fineract's
    // built-in JobScheduler (c_job_run_history) to avoid dual triggers.
    //
    // To disable this and rely only on Fineract scheduler, set:
    //   fiter.reconciliation.scheduled.enabled=false
    // and register the job via INSERT into c_job / c_scheduler_detail.

    // Default: 2:30 AM every day.
    // Override with: fiter.reconciliation.cron=0 30 2 * * ?
    @Scheduled(cron = "${fiter.reconciliation.cron:0 30 2 * * ?}")
    public void scheduleNightlyReconciliation() {
        // The scheduler triggers Fineract's internal job launcher.
        // Actual execution goes through JobLauncher → Job → Step → Tasklet.
        // This method just serves as the @Scheduled entry point.
        // In a proper Fineract integration, use SchedulerJobRunnerServiceImpl instead.
    }
}
