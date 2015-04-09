package com.ldbc.driver;

import com.ldbc.driver.control.DriverConfiguration;
import com.ldbc.driver.generator.GeneratorFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public abstract class Workload implements Closeable {
    public static final long DEFAULT_MAXIMUM_EXPECTED_INTERLEAVE_AS_MILLI = TimeUnit.HOURS.toMillis(1);

    private boolean isInitialized = false;
    private boolean isClosed = false;

    public abstract Map<Integer, Class<? extends Operation<?>>> operationTypeToClassMapping(Map<String, String> params);

    /**
     * Called once to initialize state for workload
     */
    public final void init(DriverConfiguration params) throws WorkloadException {
        if (isInitialized)
            throw new WorkloadException("Workload may be initialized only once");
        isInitialized = true;
        onInit(params.asMap());
    }

    public abstract void onInit(Map<String, String> params) throws WorkloadException;


    public final void close() throws IOException {
        if (isClosed) {
            throw new IOException("Workload may be cleaned up only once");
        }
        isClosed = true;
        onClose();
    }

    protected abstract void onClose() throws IOException;

    public final WorkloadStreams streams(GeneratorFactory gf) throws WorkloadException {
        if (false == isInitialized)
            throw new WorkloadException("Workload has not been initialized");
        return getStreams(gf);
    }

    protected abstract WorkloadStreams getStreams(GeneratorFactory generators) throws WorkloadException;

    public DbValidationParametersFilter dbValidationParametersFilter(final Integer requiredValidationParameterCount) {
        return new DbValidationParametersFilter() {
            private final List<Operation> injectedOperations = new ArrayList<>();
            int validationParameterCount = 0;

            @Override
            public boolean useOperation(Operation<?> operation) {
                return true;
            }

            @Override
            public DbValidationParametersFilterResult useOperationAndResultForValidation(Operation<?> operation, Object operationResult) {
                if (validationParameterCount < requiredValidationParameterCount) {
                    validationParameterCount++;
                    return new DbValidationParametersFilterResult(DbValidationParametersFilterAcceptance.ACCEPT_AND_CONTINUE, injectedOperations);
                }
                return new DbValidationParametersFilterResult(DbValidationParametersFilterAcceptance.REJECT_AND_FINISH, injectedOperations);
            }
        };
    }

    public long maxExpectedInterleaveAsMilli() {
        return DEFAULT_MAXIMUM_EXPECTED_INTERLEAVE_AS_MILLI;
    }

    public abstract String serializeOperation(Operation<?> operation) throws SerializingMarshallingException;

    public abstract Operation<?> marshalOperation(String serializedOperation) throws SerializingMarshallingException;

    public abstract boolean resultsEqual(Operation operation, Object result1, Object result2) throws WorkloadException;

    public static interface DbValidationParametersFilter {
        boolean useOperation(Operation<?> operation);

        DbValidationParametersFilterResult useOperationAndResultForValidation(Operation<?> operation, Object operationResult);
    }

    public enum DbValidationParametersFilterAcceptance {
        ACCEPT_AND_CONTINUE,
        ACCEPT_AND_FINISH,
        REJECT_AND_CONTINUE,
        REJECT_AND_FINISH;
    }

    public static class DbValidationParametersFilterResult {
        private final DbValidationParametersFilterAcceptance acceptance;
        private final List<Operation> injectedOperations;

        public DbValidationParametersFilterResult(DbValidationParametersFilterAcceptance acceptance, List<Operation> injectedOperations) {
            this.acceptance = acceptance;
            this.injectedOperations = injectedOperations;
        }

        public DbValidationParametersFilterAcceptance acceptance() {
            return acceptance;
        }

        public List<Operation> injectedOperations() {
            return injectedOperations;
        }
    }

}