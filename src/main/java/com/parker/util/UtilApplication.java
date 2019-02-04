package com.parker.util;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.parker.util.dao")
public class UtilApplication {

	public static void main(String[] args) {
		SpringApplication.run(UtilApplication.class, args);
	}

}

