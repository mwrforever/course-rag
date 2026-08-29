package com.commerce.rag.config;

import com.commerce.rag.properties.CourseProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 课程域属性绑定注册（宪法：@ConfigurationProperties 放 properties/，注册集中 config/） */
@Configuration
@EnableConfigurationProperties(CourseProperties.class)
public class CourseConfig {}
