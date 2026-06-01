package com.bytedance.ai.common.api;

/**
 * Web API 统一响应包装。
 *
 * @param success 请求是否处理成功
 * @param message 面向调用方的处理结果说明
 * @param data    业务响应数据，失败或仅提示类响应可为空
 * @param <T>     响应数据类型
 */
public record ApiResponse<T>(boolean success, String message, T data) {

    /**
     * 构造一个成功响应，并附带提示语与数据。
     *
     * @param message 成功提示语
     * @param data    响应数据
     * @param <T>     响应数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    /**
     * 构造一个默认提示语为 ok 的成功响应。
     *
     * @param data 响应数据
     * @param <T>  响应数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ok("ok", data);
    }

    /**
     * 构造一个仅包含提示语的成功响应。
     *
     * @param message 成功提示语
     * @return 不含业务数据的成功响应
     */
    public static ApiResponse<Void> okMessage(String message) {
        return ok(message, null);
    }

    /**
     * 构造一个失败响应。
     *
     * @param message 失败原因说明
     * @return 不含业务数据的失败响应
     */
    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
