/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.scheduling;

import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.CONNECTION_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.JOB_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.WORKFLOW_TRACE_OPERATION_NAME;

import com.fasterxml.jackson.databind.JsonNode;
import datadog.trace.api.Trace;
import io.airbyte.commons.temporal.TemporalJobType;
import io.airbyte.commons.temporal.TemporalWorkflowUtils;
import io.airbyte.commons.temporal.exception.RetryableException;
import io.airbyte.commons.temporal.scheduling.ConnectionManagerWorkflow;
import io.airbyte.commons.temporal.scheduling.ConnectionUpdaterInput;
import io.airbyte.commons.temporal.scheduling.SyncWorkflow;
import io.airbyte.commons.temporal.scheduling.state.WorkflowInternalState;
import io.airbyte.commons.temporal.scheduling.state.WorkflowState;
import io.airbyte.commons.temporal.scheduling.state.listener.NoopStateListener;
import io.airbyte.config.ConnectorJobOutput;
import io.airbyte.config.ConnectorJobOutput.OutputType;
import io.airbyte.config.FailureReason;
import io.airbyte.config.FailureReason.FailureType;
import io.airbyte.config.NormalizationSummary;
import io.airbyte.config.StandardCheckConnectionInput;
import io.airbyte.config.StandardCheckConnectionOutput;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.config.StandardSyncSummary;
import io.airbyte.config.StandardSyncSummary.ReplicationStatus;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import io.airbyte.persistence.job.models.IntegrationLauncherConfig;
import io.airbyte.persistence.job.models.JobRunConfig;
import io.airbyte.workers.WorkerConstants;
import io.airbyte.workers.helper.FailureHelper;
import io.airbyte.workers.temporal.annotations.TemporalActivityStub;
import io.airbyte.workers.temporal.check.connection.CheckConnectionActivity;
import io.airbyte.workers.temporal.check.connection.CheckConnectionActivity.CheckConnectionInput;
import io.airbyte.workers.temporal.scheduling.activities.AutoDisableConnectionActivity;
import io.airbyte.workers.temporal.scheduling.activities.AutoDisableConnectionActivity.AutoDisableConnectionActivityInput;
import io.airbyte.workers.temporal.scheduling.activities.AutoDisableConnectionActivity.AutoDisableConnectionOutput;
import io.airbyte.workers.temporal.scheduling.activities.ConfigFetchActivity;
import io.airbyte.workers.temporal.scheduling.activities.ConfigFetchActivity.ScheduleRetrieverInput;
import io.airbyte.workers.temporal.scheduling.activities.ConfigFetchActivity.ScheduleRetrieverOutput;
import io.airbyte.workers.temporal.scheduling.activities.ConnectionDeletionActivity;
import io.airbyte.workers.temporal.scheduling.activities.ConnectionDeletionActivity.ConnectionDeletionInput;
import io.airbyte.workers.temporal.scheduling.activities.GenerateInputActivity;
import io.airbyte.workers.temporal.scheduling.activities.GenerateInputActivity.GeneratedJobInput;
import io.airbyte.workers.temporal.scheduling.activities.GenerateInputActivity.SyncInput;
import io.airbyte.workers.temporal.scheduling.activities.GenerateInputActivity.SyncInputWithAttemptNumber;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.AttemptCreationInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.AttemptCreationOutput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.AttemptFailureInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.AttemptNumberCreationOutput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.AttemptNumberFailureInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.EnsureCleanJobStateInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobCancelledInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobCancelledInputWithAttemptNumber;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobCheckFailureInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobCreationInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobCreationOutput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobFailureInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobSuccessInput;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.JobSuccessInputWithAttemptNumber;
import io.airbyte.workers.temporal.scheduling.activities.JobCreationAndStatusUpdateActivity.ReportJobStartInput;
import io.airbyte.workers.temporal.scheduling.activities.RecordMetricActivity;
import io.airbyte.workers.temporal.scheduling.activities.RecordMetricActivity.FailureCause;
import io.airbyte.workers.temporal.scheduling.activities.RecordMetricActivity.RecordMetricInput;
import io.airbyte.workers.temporal.scheduling.activities.RouteToSyncTaskQueueActivity;
import io.airbyte.workers.temporal.scheduling.activities.RouteToSyncTaskQueueActivity.RouteToSyncTaskQueueInput;
import io.airbyte.workers.temporal.scheduling.activities.RouteToSyncTaskQueueActivity.RouteToSyncTaskQueueOutput;
import io.airbyte.workers.temporal.scheduling.activities.StreamResetActivity;
import io.airbyte.workers.temporal.scheduling.activities.StreamResetActivity.DeleteStreamResetRecordsForJobInput;
import io.airbyte.workers.temporal.scheduling.activities.WorkflowConfigActivity;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class ConnectionManagerWorkflowImpl implements ConnectionManagerWorkflow {

  private static final int TASK_QUEUE_CHANGE_CURRENT_VERSION = 1;
  private static final int AUTO_DISABLE_FAILING_CONNECTION_CHANGE_CURRENT_VERSION = 1;

  private static final String RENAME_ATTEMPT_ID_TO_NUMBER_TAG = "rename_attempt_id_to_number";
  private static final int RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION = 1;

  private static final String CHECK_PREVIOUS_JOB_OR_ATTEMPT_TAG = "check_previous_job_or_attempt";
  private static final int CHECK_PREVIOUS_JOB_OR_ATTEMPT_TAG_CURRENT_VERSION = 1;

  private static final String ENSURE_CLEAN_JOB_STATE = "ensure_clean_job_state";
  private static final int ENSURE_CLEAN_JOB_STATE_CURRENT_VERSION = 1;

  private static final String CHECK_BEFORE_SYNC_TAG = "check_before_sync";
  private static final int CHECK_BEFORE_SYNC_CURRENT_VERSION = 1;

  private static final String CHECK_JOB_OUTPUT_TAG = "check_job_output";
  private static final int CHECK_JOB_OUTPUT_TAG_CURRENT_VERSION = 1;

  private static final String DELETE_RESET_JOB_STREAMS_TAG = "delete_reset_job_streams";
  private static final int DELETE_RESET_JOB_STREAMS_CURRENT_VERSION = 1;
  private static final String RECORD_METRIC_TAG = "record_metric";
  private static final int RECORD_METRIC_CURRENT_VERSION = 1;
  private static final String WORKFLOW_CONFIG_TAG = "workflow_config";
  private static final int WORKFLOW_CONFIG_CURRENT_VERSION = 1;

  private static final String ROUTE_ACTIVITY_TAG = "route_activity";
  private static final int ROUTE_ACTIVITY_CURRENT_VERSION = 1;

  private WorkflowState workflowState = new WorkflowState(UUID.randomUUID(), new NoopStateListener());

  private final WorkflowInternalState workflowInternalState = new WorkflowInternalState();

  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private GenerateInputActivity getSyncInputActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private JobCreationAndStatusUpdateActivity jobCreationAndStatusUpdateActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private ConfigFetchActivity configFetchActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private ConnectionDeletionActivity connectionDeletionActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private AutoDisableConnectionActivity autoDisableConnectionActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private CheckConnectionActivity checkActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private StreamResetActivity streamResetActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private RecordMetricActivity recordMetricActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private WorkflowConfigActivity workflowConfigActivity;
  @TemporalActivityStub(activityOptionsBeanName = "shortActivityOptions")
  private RouteToSyncTaskQueueActivity routeToSyncTaskQueueActivity;

  private CancellationScope cancellableSyncWorkflow;

  private UUID connectionId;

  private Duration workflowDelay;

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void run(final ConnectionUpdaterInput connectionUpdaterInput) throws RetryableException {
    try {
      ApmTraceUtils.addTagsToTrace(Map.of(CONNECTION_ID_KEY, connectionUpdaterInput.getConnectionId()));
      recordMetric(new RecordMetricInput(connectionUpdaterInput, Optional.empty(), OssMetricsRegistry.TEMPORAL_WORKFLOW_ATTEMPT, null));
      workflowDelay = getWorkflowRestartDelaySeconds();

      try {
        cancellableSyncWorkflow = generateSyncWorkflowRunnable(connectionUpdaterInput);
        cancellableSyncWorkflow.run();
      } catch (final CanceledFailure cf) {
        // When a scope is cancelled temporal will throw a CanceledFailure as you can see here:
        // https://github.com/temporalio/sdk-java/blob/master/temporal-sdk/src/main/java/io/temporal/workflow/CancellationScope.java#L72
        // The naming is very misleading, it is not a failure but the expected behavior...
        recordMetric(
            new RecordMetricInput(connectionUpdaterInput, Optional.of(FailureCause.CANCELED), OssMetricsRegistry.TEMPORAL_WORKFLOW_FAILURE, null));
      }

      if (workflowState.isDeleted()) {
        if (workflowState.isRunning()) {
          log.info("Cancelling the current running job because a connection deletion was requested");
          reportCancelled(connectionUpdaterInput.getConnectionId());
        }
        log.info("Workflow deletion was requested. Calling deleteConnection activity before terminating the workflow.");
        deleteConnectionBeforeTerminatingTheWorkflow();
        return;
      }

      // this means that the current workflow is being cancelled so that a reset can be run instead.
      if (workflowState.isCancelledForReset()) {
        reportCancelledAndContinueWith(true, connectionUpdaterInput);
      }

      // "Cancel" button was pressed on a job
      if (workflowState.isCancelled()) {
        reportCancelledAndContinueWith(false, connectionUpdaterInput);
      }

    } catch (final Exception e) {
      log.error("The connection update workflow has failed, will create a new attempt.", e);
      reportFailure(connectionUpdaterInput, null, FailureCause.UNKNOWN);
      prepareForNextRunAndContinueAsNew(connectionUpdaterInput);
    }
  }

  @SuppressWarnings("PMD.EmptyIfStmt")
  private CancellationScope generateSyncWorkflowRunnable(final ConnectionUpdaterInput connectionUpdaterInput) {
    return Workflow.newCancellationScope(() -> {
      connectionId = connectionUpdaterInput.getConnectionId();

      // workflow state is only ever set in test cases. for production cases, it will always be null.
      if (connectionUpdaterInput.getWorkflowState() != null) {
        workflowState = connectionUpdaterInput.getWorkflowState();
      }

      if (connectionUpdaterInput.isSkipScheduling()) {
        workflowState.setSkipScheduling(true);
      }

      // Clean the job state by failing any jobs for this connection that are currently non-terminal.
      // This catches cases where the temporal workflow was terminated and restarted while a job was
      // actively running, leaving that job in an orphaned and non-terminal state.
      ensureCleanJobState(connectionUpdaterInput);

      final Duration timeToWait = getTimeToWait(connectionUpdaterInput.getConnectionId());

      Workflow.await(timeToWait,
          () -> skipScheduling() || connectionUpdaterInput.isFromFailure());

      workflowState.setDoneWaiting(true);

      if (workflowState.isDeleted()) {
        log.info("Returning from workflow cancellation scope because workflow deletion was requested.");
        return;
      }

      if (workflowState.isUpdated()) {
        // Act as a return
        prepareForNextRunAndContinueAsNew(connectionUpdaterInput);
      }

      workflowInternalState.setJobId(getOrCreateJobId(connectionUpdaterInput));
      workflowInternalState.setAttemptNumber(createAttempt(workflowInternalState.getJobId()));

      final GeneratedJobInput jobInputs = getJobInput();

      reportJobStarting(connectionUpdaterInput.getConnectionId());
      StandardSyncOutput standardSyncOutput = null;

      try {
        final SyncCheckConnectionFailure syncCheckConnectionFailure = checkConnections(jobInputs);
        if (syncCheckConnectionFailure.isFailed()) {
          final StandardSyncOutput checkFailureOutput = syncCheckConnectionFailure.buildFailureOutput();
          workflowState.setFailed(getFailStatus(checkFailureOutput));
          reportFailure(connectionUpdaterInput, checkFailureOutput, FailureCause.CONNECTION);
        } else {
          standardSyncOutput = runChildWorkflow(jobInputs);
          workflowState.setFailed(getFailStatus(standardSyncOutput));

          if (workflowState.isFailed()) {
            reportFailure(connectionUpdaterInput, standardSyncOutput, FailureCause.UNKNOWN);
          } else {
            reportSuccess(connectionUpdaterInput, standardSyncOutput);
          }
        }

        prepareForNextRunAndContinueAsNew(connectionUpdaterInput);
      } catch (final ChildWorkflowFailure childWorkflowFailure) {
        // when we cancel a method, we call the cancel method of the cancellation scope. This will throw an
        // exception since we expect it, we just
        // silently ignore it.
        if (childWorkflowFailure.getCause() instanceof CanceledFailure) {
          log.debug("Ignoring canceled failure as it is handled by the cancellation scope.");
          // do nothing, cancellation handled by cancellationScope
        } else if (childWorkflowFailure.getCause()instanceof final ActivityFailure af) {
          // Allows us to classify unhandled failures from the sync workflow. e.g. If the normalization
          // activity throws an exception, for
          // example, this lets us set the failureOrigin to normalization.
          workflowInternalState.getFailures().add(FailureHelper.failureReasonFromWorkflowAndActivity(
              childWorkflowFailure.getWorkflowType(),
              af.getActivityType(),
              af.getCause(),
              workflowInternalState.getJobId(),
              workflowInternalState.getAttemptNumber()));
          reportFailure(connectionUpdaterInput, standardSyncOutput, FailureCause.ACTIVITY);
          prepareForNextRunAndContinueAsNew(connectionUpdaterInput);
        } else {
          workflowInternalState.getFailures().add(
              FailureHelper.unknownOriginFailure(childWorkflowFailure.getCause(), workflowInternalState.getJobId(),
                  workflowInternalState.getAttemptNumber()));
          reportFailure(connectionUpdaterInput, standardSyncOutput, FailureCause.WORKFLOW);
          prepareForNextRunAndContinueAsNew(connectionUpdaterInput);
        }
      }
    });
  }

  private void reportSuccess(final ConnectionUpdaterInput connectionUpdaterInput, final StandardSyncOutput standardSyncOutput) {
    workflowState.setSuccess(true);
    final int attemptCreationVersion =
        Workflow.getVersion(RENAME_ATTEMPT_ID_TO_NUMBER_TAG, Workflow.DEFAULT_VERSION, RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION);

    if (attemptCreationVersion < RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION) {
      runMandatoryActivity(jobCreationAndStatusUpdateActivity::jobSuccess, new JobSuccessInput(
          workflowInternalState.getJobId(),
          workflowInternalState.getAttemptNumber(),
          connectionUpdaterInput.getConnectionId(),
          standardSyncOutput));
    } else {
      runMandatoryActivity(jobCreationAndStatusUpdateActivity::jobSuccessWithAttemptNumber, new JobSuccessInputWithAttemptNumber(
          workflowInternalState.getJobId(),
          workflowInternalState.getAttemptNumber(),
          connectionUpdaterInput.getConnectionId(),
          standardSyncOutput));
    }

    deleteResetJobStreams();

    // Record the success metric
    recordMetric(new RecordMetricInput(connectionUpdaterInput, Optional.empty(), OssMetricsRegistry.TEMPORAL_WORKFLOW_SUCCESS, null));

    resetNewConnectionInput(connectionUpdaterInput);
  }

  private void reportFailure(final ConnectionUpdaterInput connectionUpdaterInput,
                             final StandardSyncOutput standardSyncOutput,
                             final FailureCause failureCause) {
    final int attemptCreationVersion =
        Workflow.getVersion(RENAME_ATTEMPT_ID_TO_NUMBER_TAG, Workflow.DEFAULT_VERSION, RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION);

    if (attemptCreationVersion < RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION) {
      runMandatoryActivity(jobCreationAndStatusUpdateActivity::attemptFailure, new AttemptFailureInput(
          workflowInternalState.getJobId(),
          workflowInternalState.getAttemptNumber(),
          connectionUpdaterInput.getConnectionId(),
          standardSyncOutput,
          FailureHelper.failureSummary(workflowInternalState.getFailures(), workflowInternalState.getPartialSuccess())));
    } else {
      runMandatoryActivity(jobCreationAndStatusUpdateActivity::attemptFailureWithAttemptNumber, new AttemptNumberFailureInput(
          workflowInternalState.getJobId(),
          workflowInternalState.getAttemptNumber(),
          connectionUpdaterInput.getConnectionId(),
          standardSyncOutput,
          FailureHelper.failureSummary(workflowInternalState.getFailures(), workflowInternalState.getPartialSuccess())));
    }

    final int maxAttempt = configFetchActivity.getMaxAttempt().getMaxAttempt();
    final int attemptNumber = connectionUpdaterInput.getAttemptNumber();

    final FailureType failureType =
        standardSyncOutput != null ? standardSyncOutput.getFailures().isEmpty() ? null : standardSyncOutput.getFailures().get(0).getFailureType()
            : null;
    if (maxAttempt > attemptNumber && failureType != FailureType.CONFIG_ERROR) {
      // restart from failure
      connectionUpdaterInput.setAttemptNumber(attemptNumber + 1);
      connectionUpdaterInput.setFromFailure(true);
    } else {
      final String failureReason = failureType == FailureType.CONFIG_ERROR ? "Connection Check Failed " + connectionId
          : "Job failed after too many retries for connection " + connectionId;
      runMandatoryActivity(jobCreationAndStatusUpdateActivity::jobFailure, new JobFailureInput(connectionUpdaterInput.getJobId(),
          connectionUpdaterInput.getAttemptNumber(), connectionUpdaterInput.getConnectionId(), failureReason));

      final int autoDisableConnectionVersion =
          Workflow.getVersion("auto_disable_failing_connection", Workflow.DEFAULT_VERSION, AUTO_DISABLE_FAILING_CONNECTION_CHANGE_CURRENT_VERSION);

      if (autoDisableConnectionVersion != Workflow.DEFAULT_VERSION) {
        final AutoDisableConnectionActivityInput autoDisableConnectionActivityInput =
            new AutoDisableConnectionActivityInput(connectionId, Instant.ofEpochMilli(Workflow.currentTimeMillis()));
        final AutoDisableConnectionOutput output = runMandatoryActivityWithOutput(
            autoDisableConnectionActivity::autoDisableFailingConnection, autoDisableConnectionActivityInput);
        if (output.isDisabled()) {
          log.info("Auto-disabled for constantly failing for Connection {}", connectionId);
        }
      }

      // Record the failure metric
      recordMetric(new RecordMetricInput(connectionUpdaterInput, Optional.of(failureCause), OssMetricsRegistry.TEMPORAL_WORKFLOW_FAILURE, null));

      resetNewConnectionInput(connectionUpdaterInput);
    }
  }

  private ConnectorJobOutput getCheckResponse(final CheckConnectionInput checkInput) {
    final int checkJobOutputVersion =
        Workflow.getVersion(CHECK_JOB_OUTPUT_TAG, Workflow.DEFAULT_VERSION, CHECK_JOB_OUTPUT_TAG_CURRENT_VERSION);

    if (checkJobOutputVersion < CHECK_JOB_OUTPUT_TAG_CURRENT_VERSION) {
      final StandardCheckConnectionOutput checkOutput = runMandatoryActivityWithOutput(checkActivity::run, checkInput);
      return new ConnectorJobOutput().withOutputType(OutputType.CHECK_CONNECTION).withCheckConnection(checkOutput);
    }

    return runMandatoryActivityWithOutput(checkActivity::runWithJobOutput, checkInput);
  }

  private SyncCheckConnectionFailure checkConnections(final GenerateInputActivity.GeneratedJobInput jobInputs) {
    final JobRunConfig jobRunConfig = jobInputs.getJobRunConfig();
    final StandardSyncInput syncInput = jobInputs.getSyncInput();
    final JsonNode sourceConfig = syncInput.getSourceConfiguration();
    final JsonNode destinationConfig = syncInput.getDestinationConfiguration();
    final IntegrationLauncherConfig sourceLauncherConfig = jobInputs.getSourceLauncherConfig();
    final IntegrationLauncherConfig destinationLauncherConfig = jobInputs.getDestinationLauncherConfig();
    final SyncCheckConnectionFailure checkFailure = new SyncCheckConnectionFailure(jobRunConfig);

    final int attemptCreationVersion =
        Workflow.getVersion(CHECK_BEFORE_SYNC_TAG, Workflow.DEFAULT_VERSION, CHECK_BEFORE_SYNC_CURRENT_VERSION);

    if (attemptCreationVersion < CHECK_BEFORE_SYNC_CURRENT_VERSION) {
      // return early if this instance of the workflow was created beforehand
      return checkFailure;
    }

    final StandardCheckConnectionInput sourceConfiguration = new StandardCheckConnectionInput().withConnectionConfiguration(sourceConfig);
    final CheckConnectionInput checkSourceInput = new CheckConnectionInput(jobRunConfig, sourceLauncherConfig, sourceConfiguration);

    final int checkJobOutputVersion =
        Workflow.getVersion(CHECK_PREVIOUS_JOB_OR_ATTEMPT_TAG, Workflow.DEFAULT_VERSION, CHECK_PREVIOUS_JOB_OR_ATTEMPT_TAG_CURRENT_VERSION);
    boolean isLastJobOrAttemptFailure = true;

    if (checkJobOutputVersion >= CHECK_PREVIOUS_JOB_OR_ATTEMPT_TAG_CURRENT_VERSION) {
      final JobCheckFailureInput jobStateInput =
          new JobCheckFailureInput(Long.parseLong(jobRunConfig.getJobId()), jobRunConfig.getAttemptId().intValue(), connectionId);
      isLastJobOrAttemptFailure = runMandatoryActivityWithOutput(jobCreationAndStatusUpdateActivity::isLastJobOrAttemptFailure, jobStateInput);
    }
    if (isResetJob(sourceLauncherConfig) || checkFailure.isFailed() || !isLastJobOrAttemptFailure) {
      // reset jobs don't need to connect to any external source, so check connection is unnecessary
      log.info("SOURCE CHECK: Skipped");
    } else {
      log.info("SOURCE CHECK: Starting");
      final ConnectorJobOutput sourceCheckResponse = getCheckResponse(checkSourceInput);
      if (SyncCheckConnectionFailure.isOutputFailed(sourceCheckResponse)) {
        checkFailure.setFailureOrigin(FailureReason.FailureOrigin.SOURCE);
        checkFailure.setFailureOutput(sourceCheckResponse);
        log.info("SOURCE CHECK: Failed");
      } else {
        log.info("SOURCE CHECK: Successful");
      }
    }

    final StandardCheckConnectionInput destinationConfiguration = new StandardCheckConnectionInput().withConnectionConfiguration(destinationConfig);
    final CheckConnectionInput checkDestinationInput = new CheckConnectionInput(jobRunConfig, destinationLauncherConfig, destinationConfiguration);

    if (checkFailure.isFailed() || !isLastJobOrAttemptFailure) {
      log.info("DESTINATION CHECK: Skipped");
    } else {
      log.info("DESTINATION CHECK: Starting");
      final ConnectorJobOutput destinationCheckResponse = getCheckResponse(checkDestinationInput);
      if (SyncCheckConnectionFailure.isOutputFailed(destinationCheckResponse)) {
        checkFailure.setFailureOrigin(FailureReason.FailureOrigin.DESTINATION);
        checkFailure.setFailureOutput(destinationCheckResponse);
        log.info("DESTINATION CHECK: Failed");
      } else {
        log.info("DESTINATION CHECK: Successful");
      }
    }

    return checkFailure;
  }

  private boolean isResetJob(final IntegrationLauncherConfig sourceLauncherConfig) {
    return WorkerConstants.RESET_JOB_SOURCE_DOCKER_IMAGE_STUB.equals(sourceLauncherConfig.getDockerImage());
  }

  // reset the ConnectionUpdaterInput back to a default state
  private void resetNewConnectionInput(final ConnectionUpdaterInput connectionUpdaterInput) {
    connectionUpdaterInput.setJobId(null);
    connectionUpdaterInput.setAttemptNumber(1);
    connectionUpdaterInput.setFromFailure(false);
    connectionUpdaterInput.setSkipScheduling(false);
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void submitManualSync() {
    traceConnectionId();
    if (workflowState.isRunning()) {
      log.info("Can't schedule a manual workflow if a sync is running for connection {}", connectionId);
      return;
    }

    workflowState.setSkipScheduling(true);
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void cancelJob() {
    traceConnectionId();
    if (!workflowState.isRunning()) {
      log.info("Can't cancel a non-running sync for connection {}", connectionId);
      return;
    }
    workflowState.setCancelled(true);
    cancellableSyncWorkflow.cancel();
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void deleteConnection() {
    traceConnectionId();
    workflowState.setDeleted(true);
    cancelJob();
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void connectionUpdated() {
    traceConnectionId();
    workflowState.setUpdated(true);
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void resetConnection() {
    traceConnectionId();

    // Assumes that the streams_reset has already been populated with streams to reset for this
    // connection
    if (workflowState.isDoneWaiting()) {
      workflowState.setCancelledForReset(true);
      cancellableSyncWorkflow.cancel();
    } else {
      workflowState.setSkipScheduling(true);
    }
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void resetConnectionAndSkipNextScheduling() {
    traceConnectionId();

    if (workflowState.isDoneWaiting()) {
      workflowState.setCancelledForReset(true);
      workflowState.setSkipSchedulingNextWorkflow(true);
      cancellableSyncWorkflow.cancel();
    } else {
      workflowState.setSkipScheduling(true);
      workflowState.setSkipSchedulingNextWorkflow(true);
    }
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public void retryFailedActivity() {
    traceConnectionId();
    workflowState.setRetryFailedActivity(true);
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public WorkflowState getState() {
    traceConnectionId();
    return workflowState;
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public JobInformation getJobInformation() {
    final Long jobId = workflowInternalState.getJobId() != null ? workflowInternalState.getJobId() : NON_RUNNING_JOB_ID;
    final Integer attemptNumber = workflowInternalState.getAttemptNumber();
    ApmTraceUtils.addTagsToTrace(Map.of(CONNECTION_ID_KEY, connectionId, JOB_ID_KEY, jobId));
    return new JobInformation(
        jobId,
        attemptNumber == null ? NON_RUNNING_ATTEMPT_ID : attemptNumber);
  }

  @Trace(operationName = WORKFLOW_TRACE_OPERATION_NAME)
  @Override
  public QuarantinedInformation getQuarantinedInformation() {
    final Long jobId = workflowInternalState.getJobId() != null ? workflowInternalState.getJobId() : NON_RUNNING_JOB_ID;
    final Integer attemptNumber = workflowInternalState.getAttemptNumber();
    ApmTraceUtils.addTagsToTrace(Map.of(CONNECTION_ID_KEY, connectionId, JOB_ID_KEY, jobId));
    return new QuarantinedInformation(
        connectionId,
        jobId,
        attemptNumber == null ? NON_RUNNING_ATTEMPT_ID : attemptNumber,
        workflowState.isQuarantined());
  }

  /**
   * return true if the workflow is in a state that require it to continue. If the state is to process
   * an update or delete the workflow, it won't continue with a run of the {@link SyncWorkflow} but it
   * will: - restart for an update - Update the connection status and terminate the workflow for a
   * delete
   */
  private Boolean skipScheduling() {
    return workflowState.isSkipScheduling() || workflowState.isDeleted() || workflowState.isUpdated();
  }

  private void prepareForNextRunAndContinueAsNew(final ConnectionUpdaterInput connectionUpdaterInput) {
    // Continue the workflow as new
    workflowInternalState.getFailures().clear();
    workflowInternalState.setPartialSuccess(null);
    final boolean isDeleted = workflowState.isDeleted();
    if (workflowState.isSkipSchedulingNextWorkflow()) {
      connectionUpdaterInput.setSkipScheduling(true);
    }
    workflowState.reset();
    if (!isDeleted) {
      Workflow.continueAsNew(connectionUpdaterInput);
    }
  }

  /**
   * This is running a lambda function that takes {@param input} as an input. If the run of the lambda
   * throws an exception, the workflow will retried after a short delay.
   *
   * Note that if the lambda activity is configured to have retries, the exception will only be caught
   * after the activity has been retried the maximum number of times.
   *
   * This method is meant to be used for calling temporal activities.
   */
  private <INPUT, OUTPUT> OUTPUT runMandatoryActivityWithOutput(final Function<INPUT, OUTPUT> mapper, final INPUT input) {
    try {
      return mapper.apply(input);
    } catch (final Exception e) {
      log.error(
          "[ACTIVITY-FAILURE] Connection {} failed to run an activity.({}).  Connection manager workflow will be restarted after a delay of {}.",
          connectionId, input.getClass().getSimpleName(), workflowDelay, e);
      // TODO (https://github.com/airbytehq/airbyte/issues/13773) add tracking/notification

      // Wait a short delay before restarting workflow. This is important if, for example, the failing
      // activity was configured to not have retries.
      // Without this delay, that activity could cause the workflow to loop extremely quickly,
      // overwhelming temporal.
      log.info("Waiting {} before restarting the workflow for connection {}, to prevent spamming temporal with restarts.", workflowDelay,
          connectionId);
      Workflow.await(workflowDelay, () -> workflowState.isRetryFailedActivity());

      // Accept a manual signal to retry the failed activity during this window
      if (workflowState.isRetryFailedActivity()) {
        log.info("Received RetryFailedActivity signal for connection {}. Retrying activity.", connectionId);
        workflowState.setRetryFailedActivity(false);
        return runMandatoryActivityWithOutput(mapper, input);
      }

      log.info("Finished wait for connection {}, restarting connection manager workflow", connectionId);

      final ConnectionUpdaterInput newWorkflowInput = TemporalWorkflowUtils.buildStartWorkflowInput(connectionId);

      Workflow.continueAsNew(newWorkflowInput);

      throw new IllegalStateException("This statement should never be reached, as the ConnectionManagerWorkflow for connection "
          + connectionId + " was continued as new.", e);
    }
  }

  /**
   * Similar to runMandatoryActivityWithOutput but for methods that don't return
   */
  private <INPUT> void runMandatoryActivity(final Consumer<INPUT> consumer, final INPUT input) {
    runMandatoryActivityWithOutput((inputInternal) -> {
      consumer.accept(inputInternal);
      return null;
    }, input);
  }

  /**
   * Calculate the duration to wait so the workflow adheres to its schedule. This lets us 'schedule'
   * the next run.
   *
   * This is calculated by {@link ConfigFetchActivity#getTimeToWait(ScheduleRetrieverInput)} and
   * depends on the last successful run and the schedule.
   *
   * Wait time is infinite If the workflow is manual or disabled since we never want to schedule this.
   */
  private Duration getTimeToWait(final UUID connectionId) {
    // Scheduling
    final ScheduleRetrieverInput scheduleRetrieverInput = new ScheduleRetrieverInput(connectionId);

    final ScheduleRetrieverOutput scheduleRetrieverOutput = runMandatoryActivityWithOutput(configFetchActivity::getTimeToWait,
        scheduleRetrieverInput);

    return scheduleRetrieverOutput.getTimeToWait();
  }

  private void ensureCleanJobState(final ConnectionUpdaterInput connectionUpdaterInput) {
    final int ensureCleanJobStateVersion =
        Workflow.getVersion(ENSURE_CLEAN_JOB_STATE, Workflow.DEFAULT_VERSION, ENSURE_CLEAN_JOB_STATE_CURRENT_VERSION);

    // For backwards compatibility and determinism, skip if workflow existed before this change
    if (ensureCleanJobStateVersion < ENSURE_CLEAN_JOB_STATE_CURRENT_VERSION) {
      return;
    }

    if (connectionUpdaterInput.getJobId() != null) {
      log.info("This workflow is already attached to a job, so no need to clean job state.");
      return;
    }

    runMandatoryActivity(jobCreationAndStatusUpdateActivity::ensureCleanJobState, new EnsureCleanJobStateInput(connectionId));
  }

  private void recordMetric(final RecordMetricInput recordMetricInput) {
    final int recordMetricVersion =
        Workflow.getVersion(RECORD_METRIC_TAG, Workflow.DEFAULT_VERSION, RECORD_METRIC_CURRENT_VERSION);

    if (recordMetricVersion < RECORD_METRIC_CURRENT_VERSION) {
      return;
    }

    runMandatoryActivity(recordMetricActivity::recordWorkflowCountMetric, recordMetricInput);
  }

  /**
   * Creates a new job if it is not present in the input. If the jobId is specified in the input of
   * the connectionManagerWorkflow, we will return it. Otherwise we will create a job and return its
   * id.
   */
  private Long getOrCreateJobId(final ConnectionUpdaterInput connectionUpdaterInput) {
    if (connectionUpdaterInput.getJobId() != null) {
      return connectionUpdaterInput.getJobId();
    }

    final JobCreationOutput jobCreationOutput =
        runMandatoryActivityWithOutput(
            jobCreationAndStatusUpdateActivity::createNewJob,
            new JobCreationInput(connectionUpdaterInput.getConnectionId()));
    connectionUpdaterInput.setJobId(jobCreationOutput.getJobId());

    return jobCreationOutput.getJobId();
  }

  /**
   * Create a new attempt for a given jobId
   *
   * @param jobId - the jobId associated with the new attempt
   *
   * @return The attempt number
   */
  private Integer createAttempt(final long jobId) {
    final int attemptCreationVersion =
        Workflow.getVersion(RENAME_ATTEMPT_ID_TO_NUMBER_TAG, Workflow.DEFAULT_VERSION, RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION);

    // Retrieve the attempt number but name it attempt id
    if (attemptCreationVersion < RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION) {
      final AttemptCreationOutput attemptCreationOutput =
          runMandatoryActivityWithOutput(
              jobCreationAndStatusUpdateActivity::createNewAttempt,
              new AttemptCreationInput(
                  jobId));
      return attemptCreationOutput.getAttemptId();
    }

    final AttemptNumberCreationOutput attemptNumberCreationOutput =
        runMandatoryActivityWithOutput(
            jobCreationAndStatusUpdateActivity::createNewAttemptNumber,
            new AttemptCreationInput(
                jobId));
    return attemptNumberCreationOutput.getAttemptNumber();
  }

  /**
   * Generate the input that is needed by the job. It will generate the configuration needed by the
   * job and will generate a different output if the job is a sync or a reset.
   */
  private GeneratedJobInput getJobInput() {
    final Long jobId = workflowInternalState.getJobId();
    final Integer attemptNumber = workflowInternalState.getAttemptNumber();
    final int attemptCreationVersion =
        Workflow.getVersion(RENAME_ATTEMPT_ID_TO_NUMBER_TAG, Workflow.DEFAULT_VERSION, RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION);

    if (attemptCreationVersion < RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION) {
      final SyncInput getSyncInputActivitySyncInput = new SyncInput(
          attemptNumber,
          jobId);

      final GeneratedJobInput syncWorkflowInputs = runMandatoryActivityWithOutput(
          getSyncInputActivity::getSyncWorkflowInput,
          getSyncInputActivitySyncInput);

      return syncWorkflowInputs;
    }

    final SyncInputWithAttemptNumber getSyncInputActivitySyncInput = new SyncInputWithAttemptNumber(
        attemptNumber,
        jobId);

    final GeneratedJobInput syncWorkflowInputs = runMandatoryActivityWithOutput(
        getSyncInputActivity::getSyncWorkflowInputWithAttemptNumber,
        getSyncInputActivitySyncInput);

    return syncWorkflowInputs;
  }

  private String getSyncTaskQueue() {
    final int taskQueueChangeVersion =
        Workflow.getVersion("task_queue_change_from_connection_updater_to_sync", Workflow.DEFAULT_VERSION, TASK_QUEUE_CHANGE_CURRENT_VERSION);

    if (taskQueueChangeVersion < TASK_QUEUE_CHANGE_CURRENT_VERSION) {
      return TemporalJobType.CONNECTION_UPDATER.name();
    }

    final int routeActivityVersion = Workflow.getVersion(ROUTE_ACTIVITY_TAG, Workflow.DEFAULT_VERSION, ROUTE_ACTIVITY_CURRENT_VERSION);

    if (routeActivityVersion < ROUTE_ACTIVITY_CURRENT_VERSION) {
      return TemporalJobType.SYNC.name();
    }

    final RouteToSyncTaskQueueInput routeToSyncTaskQueueInput = new RouteToSyncTaskQueueInput(connectionId);
    final RouteToSyncTaskQueueOutput routeToSyncTaskQueueOutput = runMandatoryActivityWithOutput(
        routeToSyncTaskQueueActivity::route,
        routeToSyncTaskQueueInput);

    return routeToSyncTaskQueueOutput.getTaskQueue();
  }

  /**
   * Report the job as started in the job tracker and set it as running in the workflow internal
   * state.
   *
   * @param connectionId The connection ID associated with this execution of the workflow.
   */
  private void reportJobStarting(final UUID connectionId) {
    runMandatoryActivity(
        jobCreationAndStatusUpdateActivity::reportJobStart,
        new ReportJobStartInput(
            workflowInternalState.getJobId(), connectionId));

    workflowState.setRunning(true);
  }

  /**
   * Start the child {@link SyncWorkflow}. We are using a child workflow here for two main reason:
   * <p>
   * - Originally the Sync workflow was living by himself and was launch by the scheduler. In order to
   * limit the potential migration issues, we kept the {@link SyncWorkflow} as is and launch it as a
   * child workflow.
   * <p>
   * - The {@link SyncWorkflow} has different requirements than the {@link ConnectionManagerWorkflow}
   * since the latter is a long running workflow, in the future, using a different Node pool would
   * make sense.
   */
  private StandardSyncOutput runChildWorkflow(final GeneratedJobInput jobInputs) {
    final String taskQueue = getSyncTaskQueue();

    final SyncWorkflow childSync = Workflow.newChildWorkflowStub(SyncWorkflow.class,
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId("sync_" + workflowInternalState.getJobId())
            .setTaskQueue(taskQueue)
            // This will cancel the child workflow when the parent is terminated
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
            .build());

    return childSync.run(
        jobInputs.getJobRunConfig(),
        jobInputs.getSourceLauncherConfig(),
        jobInputs.getDestinationLauncherConfig(),
        jobInputs.getSyncInput(),
        connectionId);
  }

  /**
   * Set the internal status as failed and save the failures reasons
   *
   * @return True if the job failed, false otherwise
   */
  private boolean getFailStatus(final StandardSyncOutput standardSyncOutput) {
    final StandardSyncSummary standardSyncSummary = standardSyncOutput.getStandardSyncSummary();

    if (standardSyncSummary != null && standardSyncSummary.getStatus() == ReplicationStatus.FAILED) {
      workflowInternalState.getFailures().addAll(standardSyncOutput.getFailures());
      workflowInternalState.setPartialSuccess(standardSyncSummary.getTotalStats().getRecordsCommitted() > 0);
      return true;
    }

    // catch normalization failure reasons
    final NormalizationSummary normalizationSummary = standardSyncOutput.getNormalizationSummary();
    if (normalizationSummary != null && normalizationSummary.getFailures() != null &&
        !normalizationSummary.getFailures().isEmpty()) {
      workflowInternalState.getFailures().addAll(normalizationSummary.getFailures());
      return true;
    }

    return false;
  }

  /**
   * Delete a connection
   */
  private void deleteConnectionBeforeTerminatingTheWorkflow() {
    final ConnectionDeletionInput connectionDeletionInput = new ConnectionDeletionInput(connectionId);
    runMandatoryActivity(connectionDeletionActivity::deleteConnection, connectionDeletionInput);
  }

  /**
   * Set a job as cancel and continue to the next job if and continue as a reset if needed
   */
  private void reportCancelledAndContinueWith(final boolean skipSchedulingNextRun, final ConnectionUpdaterInput connectionUpdaterInput) {
    if (workflowInternalState.getJobId() != null && workflowInternalState.getAttemptNumber() != null) {
      reportCancelled(connectionUpdaterInput.getConnectionId());
    }
    resetNewConnectionInput(connectionUpdaterInput);
    connectionUpdaterInput.setSkipScheduling(skipSchedulingNextRun);
    prepareForNextRunAndContinueAsNew(connectionUpdaterInput);
  }

  private void reportCancelled(final UUID connectionId) {
    final Long jobId = workflowInternalState.getJobId();
    final Integer attemptNumber = workflowInternalState.getAttemptNumber();
    final Set<FailureReason> failures = workflowInternalState.getFailures();
    final Boolean partialSuccess = workflowInternalState.getPartialSuccess();
    final int attemptCreationVersion =
        Workflow.getVersion(RENAME_ATTEMPT_ID_TO_NUMBER_TAG, Workflow.DEFAULT_VERSION, RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION);

    if (attemptCreationVersion < RENAME_ATTEMPT_ID_TO_NUMBER_CURRENT_VERSION) {
      runMandatoryActivity(jobCreationAndStatusUpdateActivity::jobCancelled,
          new JobCancelledInput(
              jobId,
              attemptNumber,
              connectionId,
              FailureHelper.failureSummaryForCancellation(jobId, attemptNumber, failures, partialSuccess)));
    } else {
      runMandatoryActivity(jobCreationAndStatusUpdateActivity::jobCancelledWithAttemptNumber,
          new JobCancelledInputWithAttemptNumber(
              jobId,
              attemptNumber,
              connectionId,
              FailureHelper.failureSummaryForCancellation(jobId, attemptNumber, failures, partialSuccess)));
    }
  }

  private void deleteResetJobStreams() {
    final int deleteResetJobStreamsVersion =
        Workflow.getVersion(DELETE_RESET_JOB_STREAMS_TAG, Workflow.DEFAULT_VERSION, DELETE_RESET_JOB_STREAMS_CURRENT_VERSION);

    if (deleteResetJobStreamsVersion < DELETE_RESET_JOB_STREAMS_CURRENT_VERSION) {
      return;
    }

    runMandatoryActivity(streamResetActivity::deleteStreamResetRecordsForJob,
        new DeleteStreamResetRecordsForJobInput(connectionId, workflowInternalState.getJobId()));
  }

  private Duration getWorkflowRestartDelaySeconds() {
    final int workflowConfigVersion =
        Workflow.getVersion(WORKFLOW_CONFIG_TAG, Workflow.DEFAULT_VERSION, WORKFLOW_CONFIG_CURRENT_VERSION);

    if (workflowConfigVersion < WORKFLOW_CONFIG_CURRENT_VERSION) {
      return Duration.ofMinutes(10L);
    }

    return workflowConfigActivity.getWorkflowRestartDelaySeconds();
  }

  private void traceConnectionId() {
    if (connectionId != null) {
      ApmTraceUtils.addTagsToTrace(Map.of(CONNECTION_ID_KEY, connectionId));
    }
  }

}
