package vn.thanhtuanle.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.util.StringUtils;
import vn.thanhtuanle.oj.common.grpc.PeerIdentityServerInterceptor;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenServerInterceptor;
import vn.thanhtuanle.oj.common.tls.OjTlsProperties;

import java.util.List;
import java.util.Set;

/**
 * Who may call the internal gRPC API. With {@code oj.tls.grpc-allowed-clients} set (the tls profile), only a client
 * certificate whose CN is listed gets through (PERMISSION_DENIED otherwise). With {@code oj.rpc.token} set (32+
 * characters), every call must also carry the service token. With neither, the service does not start: the API is
 * never open.
 */
@Configuration
@EnableConfigurationProperties(OjTlsProperties.class)
public class GrpcServerConfig {

    static final String ALLOWED_CLIENTS = "oj.tls.grpc-allowed-clients";
    static final String TOKEN = "oj.rpc.token";

    public GrpcServerConfig(Environment environment) {
        if (!allowedClientsSet(environment) && !tokenSet(environment)) {
            throw new IllegalStateException("problem-service gRPC needs " + ALLOWED_CLIENTS + " or " + TOKEN);
        }
    }

    @Bean
    @GlobalServerInterceptor
    @Conditional(AllowedClientsSet.class)
    public PeerIdentityServerInterceptor peerIdentityServerInterceptor(OjTlsProperties tls) {
        return new PeerIdentityServerInterceptor(Set.copyOf(tls.grpcAllowedClients()));
    }

    @Bean
    @GlobalServerInterceptor
    @Conditional(TokenSet.class)
    public ServiceTokenServerInterceptor serviceTokenServerInterceptor(Environment environment) {
        return new ServiceTokenServerInterceptor(environment.getProperty(TOKEN));
    }

    /** Binds the list, so comma form, YAML lists and {@code [0]=} indexed properties all count. */
    static boolean allowedClientsSet(Environment environment) {
        return !Binder.get(environment).bind(ALLOWED_CLIENTS, Bindable.listOf(String.class)).orElse(List.of()).isEmpty();
    }

    static boolean tokenSet(Environment environment) {
        return StringUtils.hasText(environment.getProperty(TOKEN));
    }

    static class AllowedClientsSet implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return allowedClientsSet(context.getEnvironment());
        }
    }

    static class TokenSet implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return tokenSet(context.getEnvironment());
        }
    }
}
