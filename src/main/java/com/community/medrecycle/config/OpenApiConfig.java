package com.community.medrecycle.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3.x 文档元信息；Swagger UI: /swagger-ui.html，JSON: /v3/api-docs */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI medRecycleOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("社区过期药回收称重兑换与环保交接服务 API")
                .version("1.0.0")
                .description("覆盖预约、登记称重、针剂安全封装复核(锐器盒)、人工复核、积分、暂存、环保交接、异常与追溯"));
    }
}
