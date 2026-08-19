package com.commerce.rag.config;

import com.commerce.rag.properties.AttachmentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 附件属性绑定注册（宪法：@ConfigurationProperties 一律放 properties/，注册放 config/） */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentConfig {}
