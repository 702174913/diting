package com.diting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Mapper 扫描放在 {@link com.diting.config.MybatisPlusConfig}：
 * 与 MyBatis-Plus 配置同处一地，也让 @WebMvcTest 这类只加载 Web 层的切片测试
 * 不会因为注册 mapper 而要求 SqlSessionFactory。
 */
@SpringBootApplication
@EnableScheduling
public class DitingServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DitingServerApplication.class, args);
    }
}
