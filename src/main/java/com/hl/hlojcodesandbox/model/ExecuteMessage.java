package com.hl.hlojcodesandbox.model;

import lombok.Data;

@Data
public class ExecuteMessage {

    private Integer exitCode;

    /** 标准输出 */
    private String message;

    /** 标准错误 */
    private String errorMessage;

    /** 执行耗时（ms） */
    private Long time;

    /** 内存峰值（KB） */
    private Long memory;
}