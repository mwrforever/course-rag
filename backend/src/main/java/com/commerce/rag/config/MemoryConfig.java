package com.commerce.rag.config;

import com.commerce.rag.properties.MemoryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 记忆体系配置注册（宪法：@ConfigurationProperties 一律放 properties/，注册放 config/） */
@Configuration
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryConfig {}
