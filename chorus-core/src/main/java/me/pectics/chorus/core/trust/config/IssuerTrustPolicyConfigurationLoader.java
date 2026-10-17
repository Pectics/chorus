/*
 * @context
 * refs: ARCHITECTURE.md, sections 3.5 and 3.9
 * related: chorus-core/src/main/java/me/pectics/chorus/core/trust/IssuerTrustPolicy.java
 * sync: Load shared identity permissions consistently for issuance and local issuer approval.
 * @endcontext
 */
package me.pectics.chorus.core.trust.config;

import java.nio.file.Path;
import java.util.HashSet;
import me.pectics.chorus.core.config.ConfigurationSectionLoader;
import me.pectics.chorus.core.config.ConfigurationValueParser;
import me.pectics.chorus.core.identity.AuthenticationSourceType;
import me.pectics.chorus.core.trust.IssuerTrustPolicy;
import tools.jackson.databind.JsonNode;

/** Stateless loading of shared, schema-approved identity assertion permissions. */
public final class IssuerTrustPolicyConfigurationLoader
        implements ConfigurationSectionLoader<IssuerTrustPolicy> {
    @Override
    public IssuerTrustPolicy load(JsonNode value, Path base) {
        var types = new HashSet<AuthenticationSourceType>();
        value.get("allowed-auth-schemes")
                .forEach(item -> types.add(AuthenticationSourceType.valueOf(item.asString())));
        return new IssuerTrustPolicy(
                types, ConfigurationValueParser.strings(value.get("allowed-providers")));
    }
}
