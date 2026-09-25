package com.smsframework.dlr.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Holds every {@link DlrProviderAdapter} bean (ordered by @Order) and picks the one for a callback. */
@Component
public class DlrAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(DlrAdapterRegistry.class);

    private final List<DlrProviderAdapter> adapters;

    public DlrAdapterRegistry(List<DlrProviderAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        Set<String> names = adapters.stream().map(a -> a.source().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        if (names.size() != adapters.size()) {
            throw new IllegalStateException("Two DLR adapters declare the same source name: " + adapters);
        }
        log.info("DLR adapters registered: {}", sources());
    }

    /**
     * @param explicitSource canonical source from header/query, or null to detect from payload
     */
    public Optional<DlrProviderAdapter> find(String explicitSource, JsonNode payload) {
        List<DlrProviderAdapter> matches = adapters.stream().filter(a -> a.supports(explicitSource, payload)).toList();
        if (matches.size() > 1 && explicitSource == null) {
            log.warn("DLR payload matched several adapters {}; using {}",
                    matches.stream().map(DlrProviderAdapter::source).toList(), matches.get(0).source());
        }
        return matches.stream().findFirst();
    }

    public boolean isKnownSource(String source) {
        return source != null && adapters.stream().anyMatch(a -> a.source().equalsIgnoreCase(source));
    }

    public List<String> sources() {
        return adapters.stream().map(DlrProviderAdapter::source).toList();
    }
}
