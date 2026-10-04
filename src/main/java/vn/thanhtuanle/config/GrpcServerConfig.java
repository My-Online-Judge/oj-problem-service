package vn.thanhtuanle.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import vn.thanhtuanle.oj.common.grpc.ServiceTokenServerInterceptor;

/** Every internal call must carry the service token; the service does not start without one (32+ chars). */
@Configuration
public class GrpcServerConfig {

    @Bean
    @GlobalServerInterceptor
    public ServiceTokenServerInterceptor serviceTokenServerInterceptor(@Value("${oj.rpc.token:}") String token) {
        return new ServiceTokenServerInterceptor(token);
    }
}
