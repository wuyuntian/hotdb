package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author jitwxs
 * @date 2022年09月20日 8:58
 */

@Configuration
public class ExecutorServiceConfig {


    @Bean
    public ExecutorService executorService(){

        return new ThreadPoolExecutor(5,
                10,
                100,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
    }

}
