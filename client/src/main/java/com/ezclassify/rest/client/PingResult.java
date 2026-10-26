package com.ezclassify.rest.client;

/**
 * Response from {@link EzClassifyClient#ping()}.
 *
 * <p>Carries the live engine version of the connected rest-api plus optional deployment metadata
 * (git SHA and tag).
 *
 * @param engineVersion engine version currently running on the rest-api
 * @param engineSha git commit SHA of the deployed rest-api build, or an empty string when
 *     unavailable
 * @param gitTag git tag of the deployed rest-api build, or an empty string when unavailable
 */
public record PingResult(String engineVersion, String engineSha, String gitTag) {}
