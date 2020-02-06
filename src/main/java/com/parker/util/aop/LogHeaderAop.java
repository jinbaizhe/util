package com.parker.util.aop;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LogHeaderAop {
    public static final Logger LOGGER = LoggerFactory.getLogger(LogHeaderAop.class);

    @Pointcut("execution(* com.parker.util.controller.*.*(..))")
    public void before() {
    }

    @Before("before()")
    public void logHeader(JoinPoint joinPoint) {
        LOGGER.info("");
    }
}
