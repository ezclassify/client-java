package com.ezclassify.rest.contract;

import java.util.List;

/**
 * The resolved composition and result returned by a Context operation.
 *
 * @param <T> the operation-specific result type
 * @param resolution the single Context composition used throughout the operation
 * @param result the operation's own result
 * @param operationLimitations limitations that apply to the operation as a whole
 * @param responseProjection how a document operation's response was projected, or {@code null} for
 *     an operation that does not take a document
 */
public record ContextOperationResult<T>(
    ContextResolution resolution,
    T result,
    List<AssessmentLimitation> operationLimitations,
    DocumentResponseProjection responseProjection) {}
