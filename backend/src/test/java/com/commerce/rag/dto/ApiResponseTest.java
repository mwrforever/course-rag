package com.commerce.rag.dto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ApiResponse / PageResponse DTO 单元测试
 *
 * @author commerce-rag
 */
@DisplayName("ApiResponse / PageResponse DTO 测试")
class ApiResponseTest {

    @Test
    @DisplayName("ApiResponse.ok(data) → code=0, message=success, data=传入值")
    void okWithData_returnsSuccessResponse() {
        ApiResponse<String> result = ApiResponse.ok("hello");
        assertEquals(0, result.code());
        assertEquals("success", result.message());
        assertEquals("hello", result.data());
    }

    @Test
    @DisplayName("ApiResponse.ok() → code=0, data=null")
    void okNoData_returnsSuccessResponse() {
        ApiResponse<Void> result = ApiResponse.ok();
        assertEquals(0, result.code());
        assertNull(result.data());
    }

    @Test
    @DisplayName("ApiResponse.fail(code, message) → 自定义错误码和消息")
    void failWithCodeAndMessage_returnsFailResponse() {
        ApiResponse<String> result = ApiResponse.fail(404, "not found");
        assertEquals(404, result.code());
        assertEquals("not found", result.message());
        assertNull(result.data());
    }

    @Test
    @DisplayName("ApiResponse.fail(message) → 默认 500 错误码")
    void failWithMessageOnly_returns500() {
        ApiResponse<String> result = ApiResponse.fail("server error");
        assertEquals(500, result.code());
        assertEquals("server error", result.message());
    }

    @Test
    @DisplayName("PageResponse → 正确存储 records/total/page/size")
    void pageResponse_storesAllFields() {
        var records = java.util.List.of("a", "b", "c");
        PageResponse<String> response = new PageResponse<>(records, 100, 1, 20);

        assertEquals(3, response.records().size());
        assertEquals(100, response.total());
        assertEquals(1, response.page());
        assertEquals(20, response.size());
    }
}
