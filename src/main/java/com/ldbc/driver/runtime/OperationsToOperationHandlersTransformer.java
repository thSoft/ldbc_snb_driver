package com.ldbc.driver.runtime;

import com.ldbc.driver.*;
import com.ldbc.driver.runtime.coordination.*;
import com.ldbc.driver.runtime.metrics.ConcurrentMetricsService;
import com.ldbc.driver.runtime.scheduling.GctDependencyCheck;
import com.ldbc.driver.runtime.scheduling.Spinner;
import com.ldbc.driver.temporal.TimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class OperationsToOperationHandlersTransformer {
    private final TimeSource TIME_SOURCE;
    private final Db db;
    private final Spinner spinner;
    private final ConcurrentCompletionTimeService completionTimeService;
    private final ConcurrentErrorReporter errorReporter;
    private final ConcurrentMetricsService metricsService;
    private final Map<Class<? extends Operation>, OperationClassification> operationClassifications;
    private LocalCompletionTimeWriter dummyLocalCompletionTimeWriter = new DummyLocalCompletionTimeWriter();
    private LocalCompletionTimeWriter blockingLocalCompletionTimeWriter;
    private LocalCompletionTimeWriter asynchronousLocalCompletionTimeWriter;
    private LocalCompletionTimeWriter windowedLocalCompletionTimeWriter;
    private GlobalCompletionTimeReader globalCompletionTimeReader;

    OperationsToOperationHandlersTransformer(TimeSource timeSource,
                                             Db db,
                                             Spinner spinner,
                                             ConcurrentCompletionTimeService completionTimeService,
                                             ConcurrentErrorReporter errorReporter,
                                             ConcurrentMetricsService metricsService,
                                             Map<Class<? extends Operation>, OperationClassification> operationClassifications) {
        this.TIME_SOURCE = timeSource;
        this.db = db;
        this.spinner = spinner;
        this.completionTimeService = completionTimeService;
        this.errorReporter = errorReporter;
        this.metricsService = metricsService;
        this.operationClassifications = operationClassifications;
        this.blockingLocalCompletionTimeWriter = null;
        this.asynchronousLocalCompletionTimeWriter = null;
        this.windowedLocalCompletionTimeWriter = null;
        this.globalCompletionTimeReader = completionTimeService;
    }

    private LocalCompletionTimeWriter getOrCreateLocalCompletionTimeWriterForBlockingExecutor() throws CompletionTimeException {
        if (null == blockingLocalCompletionTimeWriter) {
            blockingLocalCompletionTimeWriter = completionTimeService.newLocalCompletionTimeWriter();
        }
        return blockingLocalCompletionTimeWriter;
    }

    private LocalCompletionTimeWriter getOrCreateLocalCompletionTimeWriterForAsynchronousExecutor() throws CompletionTimeException {
        if (null == asynchronousLocalCompletionTimeWriter) {
            asynchronousLocalCompletionTimeWriter = completionTimeService.newLocalCompletionTimeWriter();
        }
        return asynchronousLocalCompletionTimeWriter;
    }

    private LocalCompletionTimeWriter getOrCreateLocalCompletionTimeWriterForWindowedExecutor() throws CompletionTimeException {
        if (null == windowedLocalCompletionTimeWriter) {
            windowedLocalCompletionTimeWriter = completionTimeService.newLocalCompletionTimeWriter();
        }
        return windowedLocalCompletionTimeWriter;
    }

    List<OperationHandler<?>> transform(List<Operation<?>> operations) throws WorkloadException {
        List<OperationHandler<?>> operationHandlers = new ArrayList<>();

        // create one writer for every scheduling mode that appears in the operation stream
        for (Operation<?> operation : operations) {
            if (blockingLocalCompletionTimeWriter != null && asynchronousLocalCompletionTimeWriter != null && windowedLocalCompletionTimeWriter != null) {
                // all writers have been created
                break;
            }
            OperationClassification.DependencyMode operationDependencyMode = operationClassifications.get(operation.getClass()).dependencyMode();
            OperationClassification.SchedulingMode operationSchedulingMode = operationClassifications.get(operation.getClass()).schedulingMode();

            try {
                if (operationDependencyMode.equals(OperationClassification.DependencyMode.READ_WRITE)) {
                    switch (operationSchedulingMode) {
                        case INDIVIDUAL_ASYNC:
                            getOrCreateLocalCompletionTimeWriterForAsynchronousExecutor();
                            break;
                        case INDIVIDUAL_BLOCKING:
                            getOrCreateLocalCompletionTimeWriterForBlockingExecutor();
                            break;
                        case WINDOWED:
                            getOrCreateLocalCompletionTimeWriterForWindowedExecutor();
                            break;
                        default:
                            throw new WorkloadException(String.format("Unrecognized Scheduling Mode: %s", operationClassifications.get(operation.getClass()).dependencyMode()));
                    }
                }
            } catch (CompletionTimeException e) {
                throw new WorkloadException("Error while trying to create local completion time writer", e);
            }
        }

        boolean atLeastOneLocalCompletionTimeWriterHasBeenCreated =
                asynchronousLocalCompletionTimeWriter != null || blockingLocalCompletionTimeWriter != null || windowedLocalCompletionTimeWriter != null;

        for (Operation<?> operation : operations) {
            OperationHandler<?> operationHandler;
            try {
                operationHandler = db.getOperationHandler(operation);
            } catch (DbException e) {
                throw new WorkloadException(
                        String.format("Error while trying to retrieve operation handler for operation\n%s", operation),
                        e);
            }
            OperationClassification.DependencyMode operationDependencyMode = operationClassifications.get(operation.getClass()).dependencyMode();
            OperationClassification.SchedulingMode operationSchedulingMode = operationClassifications.get(operation.getClass()).schedulingMode();

            LocalCompletionTimeWriter localCompletionTimeWriter;

            try {
                if (operationDependencyMode.equals(OperationClassification.DependencyMode.READ_WRITE)) {
                    switch (operationSchedulingMode) {
                        case INDIVIDUAL_ASYNC:
                            localCompletionTimeWriter = getOrCreateLocalCompletionTimeWriterForAsynchronousExecutor();
                            break;
                        case INDIVIDUAL_BLOCKING:
                            localCompletionTimeWriter = getOrCreateLocalCompletionTimeWriterForBlockingExecutor();
                            break;
                        case WINDOWED:
                            localCompletionTimeWriter = getOrCreateLocalCompletionTimeWriterForWindowedExecutor();
                            break;
                        default:
                            throw new WorkloadException(String.format("Unrecognized Scheduling Mode: %s", operationClassifications.get(operation.getClass()).dependencyMode()));
                    }
                } else {
                    localCompletionTimeWriter = dummyLocalCompletionTimeWriter;
                }
            } catch (CompletionTimeException e) {
                throw new WorkloadException("Error while trying to create local completion time writer", e);
            }

            try {
                switch (operationDependencyMode) {
                    case READ_WRITE:
                        operationHandler.init(TIME_SOURCE, spinner, operation, localCompletionTimeWriter, errorReporter, metricsService);
                        if (atLeastOneLocalCompletionTimeWriterHasBeenCreated) {
                            operationHandler.addCheck(new GctDependencyCheck(globalCompletionTimeReader, operation, errorReporter));
                        }
                        break;
                    case READ:
                        operationHandler.init(TIME_SOURCE, spinner, operation, localCompletionTimeWriter, errorReporter, metricsService);
                        if (atLeastOneLocalCompletionTimeWriterHasBeenCreated) {
                            operationHandler.addCheck(new GctDependencyCheck(globalCompletionTimeReader, operation, errorReporter));
                        }
                        break;
                    case NONE:
                        operationHandler.init(TIME_SOURCE, spinner, operation, localCompletionTimeWriter, errorReporter, metricsService);
                        break;
                    default:
                        throw new WorkloadException(
                                String.format("Unrecognized GctMode: %s", operationClassifications.get(operation.getClass()).dependencyMode()));
                }
            } catch (OperationException e) {
                throw new WorkloadException(String.format("Error while trying to initialize operation handler\n%s", operationHandler), e);
            }

            operationHandlers.add(operationHandler);
        }

        return operationHandlers;
    }
}