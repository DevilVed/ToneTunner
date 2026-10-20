package com.devilved.tonetunner.cloud;

import java.util.Objects;

/** Immutable snapshot of the settings needed for one cloud request. */
public final class ProviderConfig {
    public final Provider provider;
    public final String endpoint;
    public final String model;
    public final String apiKey;
    public final boolean requireZdr;

    public ProviderConfig(Provider provider, String endpoint, String model,
                          String apiKey, boolean requireZdr) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.endpoint = clean(endpoint);
        this.model = clean(model);
        this.apiKey = clean(apiKey);
        this.requireZdr = requireZdr;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
